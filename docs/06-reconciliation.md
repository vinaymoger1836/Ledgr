# 06 — Reconciliation

Goal: for one account and one period, compare the bank's statement with the ledger and show what matches, what the SMS missed and what does not appear in the statement.

## 1. Flow

1. **Reconcile → New** — pick account, then file (Storage Access Framework picker; the file is copied into app-private storage, never modified).
2. **Detect type by content** (magic bytes, not extension): `%PDF` → PDF; `PK` zip with `xl/` → XLSX; OLE2 header `D0 CF 11 E0` → XLS; else text → CSV.
3. **Password** — if the PDF is encrypted, ask for the password (often DOB/customer-ID based; the app never stores it).
4. **Read rows** (§2).
5. **Find the header row** — first row in the first 40 where ≥ 3 cells match known header synonyms (`statement_profiles.yaml`: "Date", "Txn Date", "Value Dt", "Narration", "Description", "Particulars", "Chq./Ref.No.", "Withdrawal Amt.", "Debit", "Deposit Amt.", "Credit", "Closing Balance", "Balance", "Dr/Cr"…).
6. **Column map** — if a saved `column_map` for this bank has the same header signature, apply it silently; else show the mapping screen with auto-guesses and a preview of 5 parsed rows. Saved for next time.
7. **Normalise** — dates (formats tried in order from the profile), amounts to paise (handle `1,23,456.00`, `(500.00)`, `500.00 Dr`), period = min/max date unless the user set it.
8. **Integrity checks** — running balance continuity (`balance[i-1] ± amount[i] = balance[i]`, tolerance ₹0.01); opening + credits − debits = closing. Failures are warnings (usually a mis-parsed row), shown with row numbers.
9. **Match** (§3) and show results (§4).

## 2. Readers

| Type | Approach |
|---|---|
| CSV | Own parser: BOM strip, delimiter sniffing (`,` `;` `\t` `|`), quoted fields, skip blank and preamble rows |
| XLSX | Unzip; read `xl/sharedStrings.xml`, `xl/workbook.xml`, first visible sheet via `XmlPullParser`; numeric dates as Excel serials (1900 system) |
| XLS | POI HSSF (verify size and Android compatibility in P6). If it cannot be made to work cleanly: tell the user "Export as CSV/XLSX from net banking" — documented fallback |
| PDF | PdfBox-Android `PDFTextStripper` subclass collecting glyph positions → lines by y → cells by x-ranges. Column x-ranges come from the header row positions on page 1 (auto), adjustable on the mapping screen by dragging dividers on a preview; saved in `column_map.pdf_columns`. Multi-line narrations: a row without a date is appended to the previous row's description. Scanned (image-only) PDFs are out of scope → message "this PDF has no text; download the e-statement or CSV" |

## 3. Matching algorithm (`:domain` `Reconciler`)

Inputs: statement lines S (one account, period), ledger L (same account, period ± 3 days, statuses ACTIVE).

1. **Exact ref pass** — for each s with `ref_no`, find l with the same `ref_no` (also search the narration of s for any 12-digit UPI ref found in l). Same amount & direction → `MATCHED`; different amount → `AMOUNT_MISMATCH`.
2. **Scored pass** — for remaining pairs with same direction and **same amount**, `|date diff| ≤ 3 days`:
   `score = 0.5 (amount) + 0.3 × (1 − daydiff/4) + 0.2 × descriptionSimilarity(s.description, l.raw_counterparty)` where similarity is token Jaccard after removing noise words (UPI, IMPS, NEFT, digits).
   Assignment: sort all candidate pairs by score desc, greedily take pairs whose s and l are both unused (one-to-one). `score ≥ 0.85` → `MATCHED`; `0.6–0.85` → `MATCHED_FUZZY` (user confirms).
3. **Leftovers** — unmatched s → `STATEMENT_ONLY` (SMS missed it: no alert, deleted SMS, unknown template); unmatched l → `LEDGER_ONLY` (pending txn, wrong account, a duplicate the app did not catch, or outside the statement period).
4. **Balance hint** — where the ledger has `balance_after` from SMS, compare with the statement balance on that row; mismatches are listed as hints.

Deterministic: same inputs → same result (stable sort keys: score, date, row index, txn id).

## 4. Results screen

- Summary card: period, statement debits/credits vs ledger debits/credits, difference, match rate (animated ring).
- Four tabs with counts: **Matched**, **Needs confirm** (fuzzy + amount mismatch), **Missing in app** (statement-only), **Not in statement** (ledger-only).
- Actions:
  - Confirm / reject a fuzzy match.
  - **Add to ledger** for statement-only lines (one, selected, or all): creates `txn` with `source=STATEMENT`, runs merchant resolution and categorisation on the narration.
  - For ledger-only: mark as duplicate, move to another account, or keep.
  - Amount mismatch: choose which amount is right (statement by default).
- Reconciled txns get `recon_state`; the Dashboard table shows a ✓ chip.
- History: list of imports per account with match rate; re-run after rule changes; delete an import (removes its lines and matches, not txns the user added).

## 5. Golden cases

G-RECON-CSV (debit/credit columns, Indian number format, preamble rows), G-RECON-XLSX (Excel serial dates, signed amounts), G-RECON-PDF (synthetic multi-page PDF with multi-line narrations, password-protected), G-RECON-MATCH (exact ref, fuzzy by date shift, two equal amounts same day, amount mismatch, statement-only, ledger-only) — expected results JSON.
