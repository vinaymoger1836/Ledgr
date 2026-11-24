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

## ADR-012 SMS ingestion: raw store, gate order, live identity, foreground type — *Proposed*
**Context:** P2 adds the month scan, live receiver, catch-up, spam gate and classifier. docs/02, 04 and 05 leave several points open or in tension.
**Decision:**
- **Schema v3.** `raw_sms` and `scan_run` as in docs/05, with these differences: `raw_sms.sub_id` (the telephony subscription id; it is not a SIM slot, so `sim_slot` would mislead); `scan_run.kind` (`MONTH|CATCHUP|LIVE`), `status` (`RUNNING|DONE|FAILED|CANCELLED`) and `error_count`. `scan_run.counts_json` is keyed by full reason code (`"IGNORED: otp": 41`); outcome totals come from the prefix. `raw_sms` is unique on `(provider_id, body_hash)`.
- **`body_hash`** is the SHA-256 (lower-case hex) of the UTF-8 body only. It outlives a body purge, so a re-scan still recognises the message.
- **Gate order.** docs/04 puts L2 (suffix) before L3 (known header). A message with no suffix, after the cut-off, from an *unknown* header would then be quarantined, so every stray sender would fill Suspicious. The gate checks the header against `banks.yaml` and the confirmed list first: unknown header → `IGNORED: unknown_sender`; known but unconfirmed header with no suffix after the cut-off → `QUARANTINE: missing_suffix`. `-P` is still rejected first. No transaction outcome changes.
- **Reason codes** added by P2: `phone_sender_txn_like` (a mobile-number sender whose body classifies as a transaction: quarantine, per docs/04 L1), `invalid_sender`, `no_txn_signal` (no class matched), `balance_only` (outcome `BALANCE`; no snapshot is stored until P3) and `pipeline_error` (`REVIEW`, counted as an error; one bad message never stops a scan).
- **L4 priority:** OTP, FAILED, REMINDER, then TXN_CANDIDATE (an amount marker and a debit/credit verb), then PROMO, BALANCE_ONLY, none. A real transaction is never ignored as a promo because of a stray word like "offer".
- **L7 (red flags):** a link plus an action word, or a mobile number plus an action word. Toll-free 1800/1860 numbers are not flagged. `bank_exceptions.links_ok` in `spam.yaml` skips the link check for that bank. The generic detectors (amount marker, link shape, mobile number) are structural regexes in `:domain`; the keyword lists stay in YAML.
- **Live messages.** The receiver joins multi-part messages and queues unique work with `{address_hash, received_at, body_hash}` (SHA-256 of the sender and of the body), never the text or the number itself (WorkManager's database is not encrypted). The worker finds the message in the inbox (received within 3 minutes, both hashes match) to get its `provider_id`, retries three times, then queues a catch-up. Live work never moves the watermark.
- **Watermark** is the receipt time of the newest processed SMS, in DataStore (`catchup_watermark_ms`). Catch-up reads `date >= watermark` and relies on the unique key to skip repeats. With no watermark it scans the current IST month. Only a current-month scan or a catch-up moves it.
- **Month scan re-evaluates** messages it already stored, using the current rules and confirmed headers; catch-up and live skip them. Rows whose body was purged are never re-evaluated.
- **Foreground work.** A month scan is a foreground worker of type `dataSync` (`FOREGROUND_SERVICE_DATA_SYNC`, with WorkManager's `SystemForegroundService` declared with that type), notification channel `scan_progress` (low importance). Workers get their dependencies through a Hilt entry point, not `hilt-work`.
**Consequences:** One migration (v2→v3), new tables only. P3 swaps the template stub for real templates behind the same `TemplateStage`. The debug scan screen is temporary and goes in P4.

## ADR-013 Ledger: templates, linking, counting and review state — *Proposed*
**Context:** P3 turns messages that pass the gate into ledger rows. docs/03–05 leave several points open, and one pair of statements conflicts (see "Counting").
**Decision:**
- **Schema v4.** New tables `txn`, `balance_snapshot` and `link_proposal`, and `raw_sms.user_decision` (`CONFIRMED` | `IGNORED`, null by default). `txn` is docs/05's table plus `template_id`, `rules_version` (CLAUDE.md rule 4) and `check_reason`; `confidence` is an integer 0..100, not a fraction (no floating point near the ledger); `txn.raw_sms_id` is unique so a month re-scan is idempotent; `refund_of` also holds a reversal's link to its debit. A new index on `occurred_at` serves the dedup and linking windows. An `account` with transactions cannot be deleted (foreign key RESTRICT plus a repository check); the "In use" switch archives it instead.
- **Template files** (`assets/rules/templates/<bank>.yaml`, each with `version:`). Fields: `id`, `kind` (`DEBIT` | `CREDIT` | `BALANCE`), optional `txn_kind` (REFUND, REVERSAL, CARD_BILL_PAYMENT, CARD_PAYMENT_RECEIVED, ...), `channel`, `account_type` (a filter, not a score), `pattern`, `date_format`, `may_duplicate_with`, `verify`. Group names are those of docs/03 plus `dest_last4`; Java group names cannot contain `_`, so the engine removes underscores (`channel_hint` becomes `channelhint`). A folded YAML scalar already gives single spaces, so patterns write `\s` where they need it. Every template is compiled and checked when the rules load (the golden preflight fails on a bad one). Input is bounded to 2,000 characters and one message may spend 200 ms matching (CLAUDE.md rule 12). A missing `date_format` means `yyyy-MM-dd`.
- **No real templates ship in P3.** `golden/private/` did not exist, so real bank templates and the 90 % private coverage figure are deferred to a follow-up pass. The synthetic templates the golden cases use live under `golden/fixtures/parse/rules/templates` and are never copied into the app. The coverage reporter is built and runs whenever `golden/private/sms` exists.
- **Parse details.** The SMS date wins over the receipt time. A date-only SMS on a different day than receipt is placed at 12:00 IST; on the receipt day it keeps the receipt time. More than 3 days from receipt sets `date_suspect` and the receipt time is used. Two-digit years are 2000+. Confidence starts at 100, minus 20 without a reference, minus 30 without a `last4` (the account is then chosen only if the bank has exactly one matching account). Below 70 the row is kept and flagged for Check. An amount above ₹10,00,000 or a balance equal to the amount is `REVIEW` (`amount_over_cap`, `balance_equals_amount`); an unreadable amount is `REVIEW: invalid_amount`.
- **Balance messages** now reach the template stage instead of stopping at the gate; one nobody wrote a template for is still `BALANCE: balance_only`. A balance template writes `balance_snapshot` (one per account and instant).
- **Reversal and refund credits.** A message the classifier would call FAILED that also has an amount, a word from `credit_verbs` and one from `reversal_markers` (both in `spam.yaml`) is a transaction candidate, so the reversal and refund templates can see it. A failed debit whose text only says it *will* be reversed stays ignored unless it also says "credited".
- **Ledger rules file** `assets/rules/ledger.yaml`: salary and refund keywords, the person heuristic (phone-number VPA pattern, 2 to 3 capitalised words), the channels on which an own *name* counts as an own transfer (IMPS, NEFT, RTGS), and which category each kind or channel forces. No such constant is in Kotlin.
- **Counterparty identity** in the pure layer is a string: the shipped `merchant_key`, `person:<name>`, `wrapped:<name>` (a merchant unwrapped from an aggregator), `own`, or `id:<row>` for a user-made merchant reached through an alias. Rows for persons, wrapped merchants and "Own account" are created on first sight. The user's per-merchant category rule is `counterparty.default_category_id` (shipped defaults and user choices share it; a user edit replaces the value).
- **Categoriser order:** a kind that forces a category (own transfer, card bill, refund) first, then the merchant's default, the channel (ATM), salary keywords on a credit, people, a plain credit, and `uncategorised`. This puts the kind rule ahead of docs/03 §7's "user rule first", so a refund from Swiggy is a refund, not food.
- **Dedup.** Same account, direction and amount. The same reference is a certain duplicate; within 10 minutes with no conflicting reference it is merged only when either template lists the other in `may_duplicate_with`, otherwise it is a *proposal*. The richer record wins (counterparty, balance, reference); a row the person edited always wins. Duplicates are kept with `status = DUPLICATE`.
- **Transfers.** An own VPA, or an own name on IMPS/NEFT/RTGS to a non-merchant, or `dest_last4` in the profile marks a transfer. A matching opposite leg in another account (60 minutes for IMPS/UPI, 2 calendar days for NEFT/RTGS and card bills; equal references when both have one) pairs them. One candidate links automatically; several become proposals (at most three). If only one leg is seen it is a transfer only through the first or third evidence. A leg arriving later reclassifies an earlier expense to a person unless the person set its kind. There is no bank-holiday calendar: two calendar days stand in for two working days.
- **Refunds and reversals.** A refund links to the most recent expense of the same counterparty, not after the refund and within 60 days, that can still absorb it (cumulative refunds never exceed the original). A linked refund takes the original's category and counts as negative spend on the refund's date; an unlinked or excess refund is income in `refund`. A reversal links to the debit in the same account (same reference, else same amount within 3 days); both become `REVERSED`. Both orders of arrival are handled.
- **Counting (resolves a conflict).** docs/12's "combined total counted once" and the P3 acceptance sentence can be read as "a self-transfer contributes once", while docs/04 §6 says the transfer rows are *excluded* from Combined with the toggle off. ADR-007 and §6 win: Combined/off counts neither leg; Combined/on counts the out leg as spend and the in leg as income (a deliberate double count); a per-account view counts the out leg as spend in the source bank. `CARD_PAYMENT_RECEIVED` and `REVERSAL` count nowhere. `LedgerRules` is the only code that decides this; the database only groups and sums.
- **Review state.** `txn.check_reason` (`low_confidence`, `unknown_merchant`, `date_suspect`) drives the Check tab. `link_proposal` holds duplicate and transfer proposals (unique per pair, so a re-scan does not ask again). `raw_sms.user_decision` makes "Ignore", "Delete", "Add manually" and "It's genuine, record it" survive a re-scan. "Record it" lifts only the message-level quarantine (missing suffix, red flags) for that one message; no sender header is learned, and a phone-number sender can never be recorded (rule 3).
- **Re-parse** updates parse-derived fields on an existing row and re-links only a row with no links; fields in `user_edited_fields` are never written. A message that no longer yields a transaction detaches and deletes its row unless the person edited it.
**Consequences:** One migration (v3→v4: three new tables and one nullable column). Rules versions move to `2026.11.2`. The scan report's counts now include `TXN: parsed` and `BALANCE: balance_snapshot`. Known limits: the person heuristic also matches a two-word company name in capitals ("ACME LTD"); an own *name* on UPI is treated as a person (UPI needs an own VPA); a candidate window of ±3 days bounds dedup and linking.
