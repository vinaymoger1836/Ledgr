# 11 — Roadmap and risks

## 1. Phases

| Phase | Outcome | Depends on |
|---|---|---|
| **P0 Foundations** | Gradle modules, theme tokens (light/dark), nav shell with 4 tabs, encrypted Room, app lock, no-INTERNET check, golden harness skeleton | — |
| **P1 Profile & rule data** | Accounts, own identities, categories seeded, `banks.yaml`/`spam.yaml` loader, sender normaliser, "Senders seen" screen | P0 |
| **P2 SMS ingestion & gate** | Permission flow (incl. restricted-settings help), month scan worker with progress, live receiver, catch-up/watermark, spam gate + classifier with reason codes, raw SMS store, scan report | P1 |
| **P3 Parsing & ledger** | Template engine, templates for the owner's banks, merchant resolver + dictionary, categoriser, dedup, transfer linker, refunds/reversals, `LedgerRules`, Needs review (all tabs except teach) | P2 |
| **P4 Home** | Month picker, hero (today / month), scan UI, 7-day bars, merchants strip, recent list, txn detail/edit, manual entry, motion spec | P3 |
| **P5 Dashboard & forecast** | Widgets W1–W9, forecaster, recurring detection, statement-style table with filters and CSV export | P3 (P4 for shared components) |
| **P6 Reconciliation** | Readers (CSV/XLSX/XLS/PDF + password), header detection, mapping screen, matcher, results UI, history | P3 |
| **P7 Learning, data lifecycle, polish** | Teach a template, Storage screen with all delete actions, auto-delete, encrypted backup/restore, re-parse month, performance pass, accessibility pass | P4–P6 |

Later candidates (each needs a *Proposed* ADR): budgets & alerts, split transactions, home-screen widget for today's spend, on-device model fallback, cash wallet tracking.

## 2. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Bank SMS formats change or vary by channel | High | Parsing gaps | Templates as data; Needs review never drops; teach-a-template; reconciliation surfaces misses |
| R2 | Merchant hidden behind payment aggregators | High | "Razorpay" instead of brand | Aggregator unwrap, user aliases learned once per VPA |
| R3 | Bank PDF layouts differ, some multi-line | High | Import friction | Position-based columns with saved maps; CSV/XLSX preferred path |
| R4 | Some alerts arrive via RCS/app notifications, not SMS | Medium | Missing txns | Out of scope; reconciliation catches them; document it in the app's help |
| R5 | OEM battery killers stop the receiver | Medium | Today's spend lags | Catch-up scan on open and every 6 h; pull to refresh |
| R6 | Sideload restrictions tighten further | Medium | Can't grant SMS | Statement import + manual entry still work; Play path documented (ADR-001) |
| R7 | Old SMS deleted by the user/SMS app | Medium | Past months incomplete | Results persist after scan; statements fill history |
| R8 | Own-transfer detection errors | Medium | Wrong totals | Pairing rules + Needs review confirmation + own-identity list |
| R9 | DB key lost on reinstall | Low | Data loss | Encrypted backup export (P7), reminder after big imports |
| R10 | XLS reader on Android problematic | Medium | Some statements | Fallback message to export CSV/XLSX |
| R11 | TRAI suffix missing on some genuine headers | Low | Genuine SMS quarantined | Confirmed-header list, pre-cut-off rule |
