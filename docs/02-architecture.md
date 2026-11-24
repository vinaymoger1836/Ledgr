# 02 — Architecture

## 1. Shape

A single Android app, no server. Business logic lives in a **pure Kotlin `:domain` module** (no Android imports) so the parser, filters, matcher and forecast run on the JVM in the golden checks. Android-specific code (SMS provider, Room, files, WorkManager, UI) sits around it.

```
┌──────────────────────────── :app (Compose UI) ────────────────────────────┐
│  Home · Dashboard · Reconcile · Profile · Needs review · Settings           │
│  ViewModels (UDF: State + Intents) · Navigation · Theme tokens · Motion     │
└───────────────▲───────────────────────────────────────────▲───────────────┘
                │ Flow<UiState>                             │ use cases
┌───────────────┴──────────── :data ────────────────────────┴───────────────┐
│ SmsSource (Telephony provider, receiver) · StatementReaders (CSV/XLSX/XLS/  │
│ PDF) · Room + SQLCipher DAOs · Rule data loader (assets + user overrides) · │
│ WorkManager workers (MonthScan, CatchUp, Purge) · DataStore settings        │
└───────────────▲─────────────────────────────────────────────────────────────┘
                │ plain Kotlin models
┌───────────────┴──────────── :domain (pure Kotlin/JVM) ─────────────────────┐
│ SenderNormalizer · SpamFilter · Classifier (txn / OTP / reminder / promo) · │
│ TemplateEngine · MerchantResolver · Categorizer · Deduplicator ·            │
│ TransferLinker · LedgerRules (counting) · Reconciler · Forecaster ·         │
│ Aggregations (daily/weekly/monthly/category/merchant)                        │
└──────────────────────────────────────────────────────────────────────────────┘
```

Gradle modules: `:app`, `:data`, `:domain`, plus `:golden` (JVM-only runner for the golden checks, see doc 12).

## 2. Tech stack

| Concern | Choice | Why |
|---|---|---|
| Language | Kotlin (latest stable), coroutines + Flow | Native SMS/file APIs, one language |
| UI | Jetpack Compose, Material 3, Navigation Compose | Declarative, strong animation APIs |
| DI | Hilt | Standard, works with WorkManager |
| Database | Room + SQLCipher (`net.zetetic:sqlcipher-android`) | Encrypted at rest, typed queries, Flow |
| Key storage | Android Keystore wraps a random DB key | Key never stored in plain text |
| Settings | DataStore (Preferences) | Small key-value settings |
| Background | WorkManager (foreground worker for long scans) | Survives process death, shows progress |
| Charts | Vico (Compose) | Compose-native, animated, themeable |
| Rule data | YAML in `assets/rules/` parsed with kaml (kotlinx.serialization) | Human-editable, comments allowed |
| PDF | PdfBox-Android | Text with positions; opens password-protected PDFs |
| XLSX | Own reader: unzip + `XmlPullParser` over `sharedStrings.xml` and sheet XML | Avoids heavy POI/StAX on Android |
| XLS (old binary) | Apache POI HSSF only (verify on Android in P6; fallback: ask for CSV/XLSX) | Many Indian banks still export `.xls` |
| CSV | Small own parser (quotes, BOM, `;`/`,` detection) | No dependency needed |
| App lock | AndroidX Biometric (`BiometricPrompt`, device credential fallback) | |
| Min / target SDK | minSdk 29; targetSdk = latest stable at build time | Personal phone, modern APIs |

All libraries must have permissive licences; versions pinned in `docs/versions.md`.

## 3. Main flows

### 3.1 Month scan (user picks a month and taps Scan)
1. `MonthScanWorker(yyyy-MM)` starts as a foreground worker with a progress notification.
2. `SmsSource.query(from, to)` reads `Telephony.Sms.Inbox` with `date >= from AND date < to` (month bounds in Asia/Kolkata, converted to epoch ms), in pages of 500.
3. Each message → `Pipeline.process(raw)` (§3.3). Results are written in batches inside a transaction.
4. The scan records a `ScanRun` (month, counts by outcome, duration). Re-scanning the same month is idempotent: raw SMS are keyed by provider `_id` + hash, so nothing is duplicated.
5. Progress flows to the Home screen (animated ring + counts).

### 3.2 Live updates (today's spends)
- A manifest `BroadcastReceiver` for `SMS_RECEIVED` assembles multi-part messages and enqueues a one-shot work item that runs the pipeline for that message.
- `CatchUpWorker` runs on app open and every 6 h: it processes everything newer than the **watermark**. This covers OEMs that kill receivers.

### 3.3 Pipeline (per SMS, all in `:domain`)
```
raw SMS → SenderNormalizer → SpamFilter (gate) → Classifier
        → TemplateEngine (bank templates) → field validation
        → MerchantResolver → Categorizer → Deduplicator → TransferLinker
        → Ledger write (+ provenance)   |  or  → Needs review / Quarantine / Ignored (with reason)
```
Every outcome records **why** (rule id, template id, filter reason). Nothing is dropped silently: ignored SMS are counted per reason in the scan report.

### 3.4 Reconciliation
Import file → detect type by content → read rows → locate header row → apply saved column map (or ask) → normalise → `Reconciler.match(statementLines, ledger[account, period])` → results UI → user accepts fuzzy matches / adds missing lines. See doc 06.

### 3.5 Dashboard
Aggregations are SQL views/queries over the ledger with the counting rules (doc 04 §6) applied in one place (`LedgerRules`), so Home, Dashboard and the table always agree. Forecast is computed in `:domain` from daily aggregates (doc 07).

## 4. Rule data and overrides

- Shipped rules: `assets/rules/banks.yaml`, `templates/<bank>.yaml`, `merchants.yaml`, `categories.yaml`, `spam.yaml`.
- User additions (aliases, taught templates, category edits) live in the database and override shipped rules. Shipped files carry a `version`; each ledger row records the rule version and template id used.
- Re-parse: after a rule change, "Re-parse month" re-runs stored raw SMS through the pipeline. User edits (category, merchant, kind) are never overwritten by a re-parse.

## 5. Threading and performance targets

| Operation | Target (mid-range phone) |
|---|---|
| Scan one month (~3,000 SMS) | < 10 s |
| Live SMS → visible on Home | < 2 s after receipt |
| Dashboard open (12 months data) | < 300 ms to first chart |
| Statement import (500 lines PDF) | < 5 s |

Parsing runs on `Dispatchers.Default`; DB on Room's executors; UI never blocks.
