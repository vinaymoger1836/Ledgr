# 05 — Data model

All money is `Long` **paise**. All instants are epoch ms (UTC) with display in `Asia/Kolkata`; `local_date` (yyyy-MM-dd) is stored alongside for fast grouping. IDs are `Long` autoincrement unless noted.

## 1. Entities (Room)

### `account`
| Column | Type | Notes |
|---|---|---|
| id | Long PK | |
| bank_id | String | from `banks.yaml` (`hdfc`, `icici`…) |
| type | enum | `SAVINGS`, `CURRENT`, `CREDIT_CARD`, `WALLET` |
| nickname | String | "HDFC Salary" |
| last4 | String(4) | **only** the last 4 digits |
| color | Int | chip/accent colour |
| sender_headers | String (JSON list) | confirmed headers for this bank |
| opening_balance | Long? | optional, for balance charts |
| active | Bool | |

### `own_identity`
`id`, `kind` (`UPI_ID` | `NAME_VARIANT`), `value` — own VPAs and name spellings used for own-transfer detection.

### `raw_sms`
| Column | Notes |
|---|---|
| id | PK |
| provider_id | `_id` from the SMS provider (unique with `body_hash`) |
| address, sender_header, sender_suffix | normalised sender |
| body | encrypted with the DB; nulled by "auto-delete raw SMS" |
| body_hash | SHA-256, kept after body deletion (idempotent re-scans) |
| received_at, sim_slot | |
| outcome | `TXN`, `IGNORED`, `REVIEW`, `QUARANTINE`, `BALANCE` |
| reason | reason code (doc 04 §1) |
| template_id, rules_version | which rule produced the outcome |

### `txn` (the ledger)
| Column | Notes |
|---|---|
| id | PK |
| account_id | FK |
| kind | doc 04 §3 |
| direction | `DEBIT` / `CREDIT` |
| amount | paise |
| occurred_at, local_date | from SMS/statement; receipt time if absent |
| channel | `UPI`, `CARD`, `IMPS`, `NEFT`, `RTGS`, `ATM`, `AUTO_DEBIT`, `CASH`, `OTHER` |
| counterparty_id | FK, nullable |
| raw_counterparty | VPA/merchant text as in SMS |
| category_id | FK |
| ref_no | UPI/IMPS ref, nullable, indexed |
| balance_after | paise, nullable |
| source | `SMS`, `STATEMENT`, `MANUAL` |
| raw_sms_id | FK, nullable |
| statement_line_id | FK, nullable |
| confidence | 0–1 |
| status | `ACTIVE`, `DUPLICATE`, `REVERSED`, `DELETED` |
| duplicate_of, transfer_pair_id, refund_of | self FKs |
| user_edited_fields | JSON set (fields a re-parse must not change) |
| note | user note |
| recon_state | `UNRECONCILED`, `MATCHED`, `MATCHED_FUZZY`, `LEDGER_ONLY` |

Indexes: `(account_id, local_date)`, `(local_date)`, `(ref_no)`, `(counterparty_id, local_date)`, `(category_id, local_date)`.

### `counterparty`
`id`, `display_name`, `type` (`MERCHANT|PERSON|OWN_ACCOUNT|BANK|UNKNOWN`), `merchant_key` (shipped id, nullable), `default_category_id`, `icon` (`LETTER` | `CATEGORY` | user image URI in app storage), `color`.

### `counterparty_alias`
`raw_key` (normalised VPA or text, unique) → `counterparty_id`, `created_by` (`USER` | `RULE`).

### `category`
`id`, `key`, `name`, `icon`, `color`, `kind` (`EXPENSE|INCOME|TRANSFER`), `parent_id` (one level), `is_default`, `hidden`. Merge = re-point txns then hide.

Default set: Food & dining, Groceries, Shopping, Transport, Fuel, Bills & utilities, Mobile & internet, Rent, EMI & loans, Investments, Insurance, Health, Entertainment & subscriptions, Travel, Education, Personal care, Gifts & donations, Cash withdrawal, Transfers to people, Fees & charges, Uncategorised · Income: Salary, Transfers in, Refund, Interest, Cashback, Other income · Transfer: Self transfer, Card bill payment.

### `user_template`
`id`, `bank_id`, `pattern`, `kind`, `channel`, `date_format`, `priority`, `created_from_raw_sms_id`, `match_count`, `enabled`.

### Reconciliation
- `statement_import`: `id`, `account_id`, `file_name`, `file_type`, `period_from`, `period_to`, `imported_at`, `column_map_id`, `opening_balance`, `closing_balance`, `line_count`.
- `statement_line`: `id`, `import_id`, `row_index`, `txn_date`, `value_date`, `description`, `ref_no`, `debit`, `credit`, `balance`, `raw_row` (JSON).
- `column_map`: `id`, `bank_id`, `file_type`, `header_signature` (hash of normalised header row), `mapping` (JSON: column → field, date format, amount style `DEBIT_CREDIT_COLUMNS | SIGNED | AMOUNT_PLUS_DRCR`), `pdf_columns` (x-ranges for PDF).
- `recon_match`: `id`, `import_id`, `statement_line_id`, `txn_id`, `status` (`MATCHED`, `MATCHED_FUZZY`, `AMOUNT_MISMATCH`, `STATEMENT_ONLY`, `LEDGER_ONLY`), `score`, `reasons` (JSON), `resolved_by_user`.

### Housekeeping
- `scan_run`: `id`, `month`, `started_at`, `finished_at`, `read_count`, counts per outcome (JSON), `rules_version`.
- `balance_snapshot`: `account_id`, `at`, `balance`, `source`.
- `setting` lives in DataStore, not Room (theme, lock, toggle, auto-delete days, suffix cut-off override).

## 2. Rule data files (`assets/rules/`)

| File | Contents |
|---|---|
| `banks.yaml` | bank id, name, headers, card headers |
| `templates/<bank>.yaml` | ordered templates (doc 03 §5) |
| `merchants.yaml` | brands, VPA prefixes, keywords, default category, aggregator list |
| `categories.yaml` | default categories (seeded into DB on first run) |
| `spam.yaml` | classifier keywords, red-flag words, suffix cut-off |
| `statement_profiles.yaml` | known header synonyms and date formats for statement import |

Each file has `version:`; the combined version string (e.g. `2026.11.1`) is stored on every `raw_sms`/`txn` it produced.

## 3. Derived views (SQL)

- `v_spend_daily(account_id, local_date, category_id, counterparty_id, spend, income)` with counting rules applied per view mode (per-account vs combined, toggle) — implemented as parameterised DAO queries generated from `LedgerRules` so the rules exist once.
- Weekly = ISO week (Mon–Sun); monthly = calendar month (IST).
