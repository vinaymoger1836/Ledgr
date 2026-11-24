# 10 — Architecture decision records

Status values: *Accepted*, *Proposed*, *Superseded*. New design changes are added as *Proposed* by Claude Code and accepted by the owner.

## ADR-001 Personal, sideloaded distribution — *Accepted*
**Context:** Google Play restricts `READ_SMS`/`RECEIVE_SMS` to default SMS handlers or approved exceptions ("SMS-based money management" is one, subject to review).
**Decision:** Personal use only; signed release APK installed directly. No Play publishing.
**Consequences:** No policy review; the user must "Allow restricted settings" once on Android 15+ (doc 03 §1). The app still degrades gracefully without SMS permission, keeping a Play path open.

## ADR-002 On-device only, no network permission — *Accepted*
**Context:** Bank SMS and statements are highly sensitive; there is one user and one device.
**Decision:** No backend, no accounts, no `INTERNET` permission. Optional encrypted file backup.
**Consequences:** Strong privacy guarantee enforced by the OS. No sync, no cloud LLM, no remote rule updates (rules ship with the APK or are taught in-app). Reinstall loses data unless backed up.

## ADR-003 Native Kotlin + Compose — *Accepted*
**Alternatives:** Flutter, React Native.
**Decision:** Kotlin, Compose, Room, WorkManager, Hilt.
**Why:** SMS provider, broadcast receiver, Keystore, PDF parsing and background work are native anyway; Compose has the animation and chart ecosystem needed; one language.

## ADR-004 Rules first; no LLM in v1 — *Accepted*
**Decision:** Deterministic sender registry, classifier and per-bank templates as YAML data; unparsed SMS go to Needs review; "teach a template" covers new formats.
**Why:** Accurate, explainable, testable, offline, consistent with ADR-002.
**Later option (Proposed when needed):** an on-device model (if the phone supports one) or an opt-in cloud LLM that receives redacted text only; it would require revisiting ADR-002.

## ADR-005 Layered spam gate using TRAI suffixes — *Accepted*
**Decision:** Transactions only from known bank headers with `-T/-S/-G` suffix (or pre-cut-off/confirmed), passing classifier, template and account-match layers; red-flag messages quarantined.
**Consequences:** Very low false positives; some genuine messages may land in review (acceptable — user confirms once, then templates/headers learn).

## ADR-006 Store last 4 digits only — *Accepted*
**Decision:** Profile holds bank, type, nickname, last 4, headers, own UPI IDs. No full account or card numbers.
**Why:** SMS only ever shows masked numbers; full numbers add risk and no value.

## ADR-007 Counting rules for own transfers — *Accepted*
**Decision:** Self-transfers and card bill payments count as spend in the source account's view (owner's rule). In combined views they are excluded unless "Count transfers between my own accounts" is on (default off). Implemented once in `LedgerRules`.
**Consequences:** Per-bank totals match the bank's own outflow; combined totals avoid double counting by default.

## ADR-008 Lightweight statement readers — *Accepted*
**Decision:** Own CSV parser, own XLSX reader (zip + XmlPullParser), POI HSSF for legacy XLS (verify in P6; fallback to "export CSV"), PdfBox-Android for PDFs with position-based column detection and saved column maps.
**Why:** Apache POI OOXML relies on APIs that are awkward on Android and is large; XLSX structure is simple enough to read directly.

## ADR-009 Money as paise, time as IST local date — *Accepted*
`Long` paise everywhere; `local_date` computed in Asia/Kolkata at write time for grouping. Avoids float errors and midnight-UTC bugs.

## ADR-010 Pure-Kotlin domain module — *Accepted*
Parser, filters, linker, reconciler, forecaster and aggregations have no Android imports, so golden checks run on the JVM in seconds and the logic is reusable.

## ADR-011 Category ordering, merge bookkeeping and own-identity uniqueness — *Proposed*
**Context:** P1 (Profile) needs to reorder categories, merge them, and keep own UPI IDs/names without duplicates. docs/05 has no column for any of these, and there is no `txn` table yet to re-point on a merge.
**Decision:**
- `category.sort_order INTEGER NOT NULL` — the user's order, unique per row so a move is a swap. Migration 1→2 sets it to `id`; the rule sync appends new shipped categories at the end.
- `category.merged_into_id INTEGER NULL` — set when a category is merged away (it is also hidden). Merge re-points children (`parent_id`), merchant `default_category_id` and earlier merges (chains stay flat). From P3 the ledger resolves a category through this column, so transactions never need a bulk rewrite and a merge stays explainable.
- `own_identity` has a unique index on `(kind, value COLLATE NOCASE)`.
- `counterparty_alias.raw_key` is the primary key (docs/05 says "unique"); keys are normalised by `AliasKey.normalize` in `:domain`, the same function the merchant resolver will use.
- The "Uncategorised" category cannot be hidden or merged (the categoriser falls back to it).
**Consequences:** One migration (v1→v2). Shipped rows are never updated after first insert, so user edits always win (CLAUDE.md rule 7). Matching data (VPA prefixes, keywords) is read from `merchants.yaml` at runtime and is not copied into the database.
