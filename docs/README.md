# Expense Tracker (SMS-based, Android) — Design Package

A personal Android app that reads bank and card SMS on the phone, turns them into a clean ledger (who you paid, how much, from which account), shows today's and monthly spends, forecasts the month, and reconciles the ledger against bank statements (PDF, Excel, CSV). Everything stays on the device.

**Status (2026-11-24):** P0–P5 built (`goldenCheck` green with 8 cases; none yet run on a phone). Next: the P3 follow-up (real templates), then **P6** (`12-claude-code-handoff.md` §0).

## Decisions so far

| # | Decision | Where |
|---|---|---|
| D1 | Personal use only. Sideloaded APK, never published to the Play Store, so Play's SMS-permission restrictions do not apply. Design stays policy-friendly anyway. | 01, ADR-001 |
| D2 | On-device only. No backend, no account, **no `INTERNET` permission** in v1. The user can delete data by month, by type or all of it. | 02, 09, ADR-002 |
| D3 | Native Kotlin + Jetpack Compose (Material 3), Room + SQLCipher, WorkManager, Hilt, Vico charts. | 02, ADR-003 |
| D4 | Rules first: bank sender registry + per-bank templates as data files. Unparsed SMS go to **Needs review**. No LLM in v1; an opt-in fallback is a later, separate decision. | 03, ADR-004 |
| D5 | Layered spam filter: TRAI header suffix (`-T`/`-S` allowed, `-P` dropped), known bank sender, template match, account last-4 match, red-flag words. | 04, ADR-005 |
| D6 | Profile stores bank, account type, nickname and **last 4 digits only**, plus your own UPI IDs. Never the full account number. | 05, 09, ADR-006 |
| D7 | Track income and expenses. Credit cards included. | 04, 05 |
| D8 | A self-transfer counts as an expense in the bank it left. In combined (all-accounts) totals, transfers between your own accounts (including card bill payments) are excluded unless the "Count own transfers" toggle is on (default off). | 04, ADR-007 |
| D9 | Month scan: picking a month scans only that month's SMS. Results are stored, so they survive SMS deletion. A live receiver plus catch-up scan keeps today's spends current. | 03, 08 |
| D10 | Reconciliation imports CSV, XLSX, XLS and PDF (including password-protected PDFs), maps columns once per bank and matches by amount, date window and reference number. | 06, ADR-008 |
| D11 | Forecast is statistical and explainable (run-rate + recurring payments + discretionary daily rate with a range). No ML. | 07 |
| D12 | Default categories, user-editable. Merchants shown with letter avatars or category icons; you can set your own image per merchant. | 05, 08 |
| D13 | Animation where it helps (count-ups, staggered lists, scan progress, chart entry, shared-element transitions); respects the system "remove animations" setting. Light and dark themes. | 08 |

## Documents

| File | Contents |
|---|---|
| `CLAUDE.md` | Rules for Claude Code (copy to the repo root) |
| `01-overview.md` | Goals, scope, non-goals, glossary |
| `02-architecture.md` | Modules, layers, tech stack, main flows |
| `03-sms-ingestion-and-parsing.md` | Permissions, month scan, live receiver, sender registry, templates, extraction, merchant and category resolution |
| `04-spam-dedup-and-transfers.md` | Spam/fraud filter, non-transaction SMS, de-duplication, self-transfers, card bills, refunds, counting rules |
| `05-data-model.md` | Entities, Room schema, rule data files |
| `06-reconciliation.md` | Statement import, column mapping, matching, results |
| `07-dashboard-and-forecast.md` | Metrics, charts, statement-style table, forecast method |
| `08-ui-ux-and-motion.md` | Screens, components, motion spec, accessibility |
| `09-privacy-security-data-lifecycle.md` | Encryption, app lock, no network, data cleaning, backup |
| `10-adrs.md` | Architecture decision records |
| `11-roadmap-and-risks.md` | Phases, risks, mitigations |
| `12-claude-code-handoff.md` | Phase prompts, golden checks, review checklist |

UI mockup: the interactive "Expense Tracker — UI mockup" canvas (Home, Dashboard, Reconcile, Profile, Needs review) — https://claude.ai/artifact/KoBxmLPGAhdaS5GD9BRG17. Sample data in it is synthetic (November 2026).
