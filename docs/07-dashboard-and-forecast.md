# 07 — Dashboard and forecast

## 1. Controls (sticky header)

- **Account**: All accounts · each account chip (colour dot + nickname + ••1234).
- **Period**: Week / Month toggle + period stepper (‹ Nov 2026 ›).
- **Own transfers** chip (visible on "All accounts"): reflects the "Count transfers between my own accounts" setting (doc 04 §6).

All widgets below use the same filter and `LedgerRules`.

## 2. Widgets

| # | Widget | Content | Interaction |
|---|---|---|---|
| W1 | KPI row | Spent, Income, Net, Forecast month-end (with range) | Count-up animation on change; tap → table filtered |
| W2 | Spend bars | Month view: daily bars + cumulative line; Week view: Mon–Sun bars; previous period as a faint ghost series | Tap/drag a bar → tooltip (date, amount, top merchant); long-press → day sheet |
| W3 | Forecast | Cumulative spend line to today, dashed projection to month-end, shaded P25–P75 band, last month's cumulative as reference | Tap band → "How this is calculated" sheet |
| W4 | Weekly trend | Last 12 weeks, bars with 4-week moving average | |
| W5 | Monthly trend | Last 12 months spend vs income bars | Tap month → jump period |
| W6 | Categories | Donut (top 7 + Other) with legend amounts and % ; centre shows total | Tap slice → table filtered by category |
| W7 | Where money went | Merchant ranking: avatar, name, count, amount, share bar (Zomato, Swiggy, Blinkit, Rapido…) | Tap → merchant detail (history, average ticket, monthly bars) |
| W8 | Recurring | Detected subscriptions/EMIs/rent with next expected date and amount | Confirm / not recurring |
| W9 | Statement-style table | Bank-statement layout (§3) | Search, filter, sort, export CSV |

## 3. Statement-style table

Columns: **Date · Description (merchant / payee, raw text on expand) · Ref no · Debit · Credit · Balance · Account · Category · Source · Recon**.

- Debit in red-toned text, credit in green-toned text (also +/− signs so colour is not the only cue).
- Balance = `balance_after` from SMS where available; otherwise computed from opening balance when set; otherwise blank (never invented).
- Source chips: `SMS`, `Statement`, `Manual`. Recon: ✓ matched, ~ fuzzy, blank.
- Filters: account, category, merchant, kind, source, recon state, amount range, text search (merchant, raw text, ref).
- Grouped by day with day subtotal headers; footer shows period debits, credits, net.
- Paging via Room `PagingSource`; row tap → transaction detail (edit category, merchant, kind, note, split later).
- Export: CSV of the filtered rows to a user-chosen location (SAF).

## 4. Forecast method (`:domain` `Forecaster`)

Explainable, no ML. Computed per filter (account or combined).

Inputs: daily spend history (counting rules applied), detected recurring items, today's date.

1. **Recurring detection** — group expenses by counterparty; a series is recurring when it has ≥ 3 occurrences, intervals within 30 ± 4 days (or 7 ± 1 for weekly), and amounts within ±10% of the median. Next expected date = last + median interval.
2. **Discretionary daily rate** — trailing 90 days of daily spend **excluding** recurring and one-off outliers (a day > P95 × 3 is capped at P95). Rates are computed per weekday (Mon…Sun) because weekend spending differs; with < 8 weeks of data, use the overall daily median instead.
3. **Projection for the rest of the month**:
   `expected = spent_so_far + Σ recurring expected in (today, month_end] + Σ_{d=today+1..end} rate[weekday(d)]`
4. **Range** — same formula with the P25 and P75 of each weekday's daily spend → low/high band.
5. **Confidence label** — "Low" (< 30 days of history), "Medium" (30–89), "Good" (≥ 90). Shown next to the number.
6. **Explanation sheet** — "₹18,400 spent so far + ₹6,200 expected bills (Rent, Netflix, Airtel) + about ₹420/day for 8 days = ₹27,960 (likely ₹25,900–₹30,300)".
7. **Weekly forecast** — same method over the current ISO week.

Backtest in golden check G-FORECAST: a synthetic 6-month history with known recurring items; the projection on day 15 must be within ±8% of the actual month total and recurring items must be detected exactly.

## 5. Home vs Dashboard

Home shows the **selected month** only: today's spend (if current month) or month total, 7-day mini bars, top merchants strip, recent list. Dashboard is the analysis surface with all widgets and filters. Both read the same aggregates.
