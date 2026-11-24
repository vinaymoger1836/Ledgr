# 01 — Overview

## 1. Problem

Bank, card and UPI alerts already arrive as SMS, but they are scattered, mixed with OTPs, offers and scams, and say "VPA swiggy.xyz@icici" rather than "Swiggy". The app turns those messages into a trustworthy personal ledger, shows where money went, forecasts the month and checks the ledger against the bank's own statement.

## 2. Users

One user (the owner), one phone, several bank accounts and credit cards. Indian banks, INR, Asia/Kolkata time zone.

## 3. Goals

1. **Accurate ledger from SMS** — amount, direction (debit/credit), account, counterparty (merchant or person), channel (UPI, card, IMPS/NEFT/RTGS, ATM, auto-debit), reference number, available balance when present.
2. **Never trust spam** — promotional, phishing and unknown-sender messages never create transactions.
3. **Month-scoped scanning** — choose a month; only that month's SMS are read; Home shows that month.
4. **Today at a glance** — today's spends on Home, updated live as SMS arrive.
5. **Where money went** — merchant view (Zomato, Swiggy, Blinkit, Rapido…) and category view on Home and Dashboard.
6. **Dashboard** — weekly and monthly spend charts, categories, merchants, forecast with a range, statement-style table.
7. **Reconciliation** — import a statement (PDF/XLSX/XLS/CSV) for a period; show matched, missing and mismatched lines; add missing ones.
8. **Private by construction** — on-device, encrypted, no network permission, easy deletion.

## 4. Non-goals (v1)

- Play Store publishing, multiple users, cloud sync, web app.
- Reading email, notifications, RCS or app data (SMS only, plus statements).
- Account Aggregator (RBI AA framework) integration.
- Budgets and alerts beyond the forecast (candidate for a later phase).
- LLM parsing (deferred; ADR-004).
- Investment/portfolio tracking (SIP/stock debits are recorded as transactions only).

## 5. Feature map

| Area | Features |
|---|---|
| **Home** | Month picker; Scan month; today's spend (current month) or month total (past month); 7-day mini chart; top merchants strip; recent transactions; Needs review banner |
| **Dashboard** | Account filter; week/month toggle; spend bars; category donut; merchant ranking; trend + forecast band; income vs expense; statement-style table with search, filters, CSV export |
| **Reconcile** | Pick account + period; import file; column mapping (saved per bank); match results in four groups; accept fuzzy matches; add statement-only lines to ledger; reconciliation history |
| **Profile** | Accounts (bank, type, nickname, last 4, sender headers), own UPI IDs, own name variants, categories, merchant aliases and icons |
| **Needs review** | Unparsed bank SMS, low-confidence parses, quarantined suspicious SMS, possible duplicates and transfers to confirm; "teach a template" |
| **Settings** | App lock, theme, "Count own transfers" toggle, auto-delete raw SMS after N days, storage and delete tools, encrypted backup export/import |

## 6. Glossary

| Term | Meaning |
|---|---|
| **Sender header** | The alphanumeric SMS sender, e.g. `VM-HDFCBK-S`: operator/circle prefix, 6-char brand header, TRAI type suffix |
| **Suffix** | TRAI message-type letter: `-P` promotional, `-S` service, `-T` transactional, `-G` government |
| **Template** | A per-bank pattern that extracts fields from a transaction SMS |
| **Ledger** | The table of transactions the app believes happened |
| **Own transfer** | Money moved between two of the user's own accounts (self-transfer, card bill payment) |
| **Watermark** | The newest SMS timestamp already processed; catch-up scans start from it |
| **Reconciliation run** | One import of a statement for one account and period, with its match results |
