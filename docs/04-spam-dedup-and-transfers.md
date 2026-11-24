# 04 — Spam filter, de-duplication, transfers and counting rules

## 1. Spam and fraud filter (the gate)

A message can create a transaction **only if it passes every layer**. Each rejection records a reason code; quarantined messages are visible in Needs review → Suspicious.

| Layer | Rule | Reject → |
|---|---|---|
| L1 Sender type | Address is a phone number (`+91…`, 10–13 digits) | `IGNORED: phone_sender` (quarantine if it looks like a bank txn: "Suspicious — bank-style SMS from a mobile number") |
| L2 TRAI suffix | Suffix `P` → reject. `T`, `S`, `G` → pass. No suffix → pass only if the SMS date is before the suffix rollout cut-off (config, default 2025-05-01) **or** the header is in the user's confirmed list | `IGNORED: promotional_suffix` / `QUARANTINE: missing_suffix` |
| L3 Known bank header | Header in `banks.yaml` or confirmed by the user in Profile | `IGNORED: unknown_sender` |
| L4 Classifier | OTP, reminder, promo, failed (doc 03 §4) | `IGNORED: <class>` |
| L5 Template match | A template for that bank matches | `REVIEW: no_template` |
| L6 Account match | `last4` matches a profile account of that bank | `REVIEW: unknown_account` |
| L7 Red flags | Contains a URL **and** asks for action ("click", "update KYC", "verify", "blocked", "call"), or a phone number to call, inside an otherwise valid-looking txn | `QUARANTINE: red_flags` (the txn is not recorded until the user confirms) |

Notes:
- Real banks do include links in some alerts (e.g. "not you? report at…"). L7 therefore quarantines rather than drops, and the user can mark a template as "links OK".
- `spam.yaml` holds the keyword lists, the cut-off date and per-bank exceptions; all versioned.
- Counters per reason appear in each scan report ("812 SMS read · 96 transactions · 640 promotional · 41 OTP · 3 suspicious · 7 need review").

## 2. De-duplication

The same money movement can be reported more than once: repeated delivery, a bank alert plus a card alert, a debit alert plus a "UPI txn successful" alert from the same bank, or a statement line plus an SMS.

Candidate duplicates (within the same account and direction):
1. **Same reference number** → duplicate (certain). Keep the richer record (has merchant/balance), link the other as `duplicate_of`.
2. **Same amount, |Δtime| ≤ 10 min, no conflicting refs** → probable duplicate. Merged automatically if both came from templates marked `may_duplicate_with: [...]`; otherwise listed in Needs review → Possible duplicates.
3. Two debits with **different refs** are never merged (two coffees of ₹120 are two coffees).

## 3. Transaction kinds

| Kind | Example | Default category |
|---|---|---|
| `EXPENSE` | UPI to Swiggy, card spend, auto-debit, ATM withdrawal | from merchant |
| `INCOME` | Salary, UPI received from a person, interest | Salary / Transfers in / Interest |
| `REFUND` | "Refund of ₹349 from Zomato credited" | Refund |
| `SELF_TRANSFER_OUT` / `SELF_TRANSFER_IN` | A/c 1234 → own A/c 5678 | Self transfer |
| `CARD_BILL_PAYMENT` (bank side) / `CARD_PAYMENT_RECEIVED` (card side) | HDFC savings → own ICICI credit card | Card bill payment |
| `REVERSAL` | Failed UPI amount reversed | links to original |

## 4. Own-transfer detection

A debit is an own transfer when any of these holds:
1. The payee VPA is one of the profile's own UPI IDs, or the payee name matches an own-name variant **and** the destination is a bank account (not a merchant).
2. A matching credit exists in another profile account: same amount, |Δt| ≤ 60 min (IMPS/UPI) or ≤ 2 working days (NEFT), and if refs exist they match.
3. The SMS names a destination account/card whose last 4 digits are in the profile (e.g. "payment to card xx4321").

When both sides are seen they are linked as a `transfer_pair`. If only the outgoing side is seen (destination account not tracked), it is an own transfer only if rule 1 or 3 matched; otherwise it is an `EXPENSE` to a person.

Card bill payments are own transfers whose destination is a profile credit card.

## 5. Refunds and reversals

- `REFUND` credits are linked to the most recent `EXPENSE` with the same counterparty and amount ≥ refund amount within 60 days (user can relink).
- `REVERSAL` (failed txn reversed) links to the debit with the same ref or same amount within 3 days; both are then marked `reversed` and excluded from spend.

## 6. Counting rules (single source of truth: `LedgerRules`)

| Kind | Per-account view (that bank/card) | Combined view, toggle **off** (default) | Combined view, toggle **on** |
|---|---|---|---|
| `EXPENSE` | Spend | Spend | Spend |
| `INCOME` | Income | Income | Income |
| `SELF_TRANSFER_OUT` | **Spend** (owner's rule) | Excluded | Spend |
| `SELF_TRANSFER_IN` | Income ("Transfer in") | Excluded | Income |
| `CARD_BILL_PAYMENT` (bank side) | **Spend** | Excluded | Spend |
| `CARD_PAYMENT_RECEIVED` (card side) | Shown as payment, not income | Excluded | Excluded |
| Card spend (`EXPENSE` on a card account) | Spend on the card | Spend | Spend |
| `REFUND` | Reduces spend in its category (net) | Same | Same |
| `REVERSAL` pair | Excluded | Excluded | Excluded |

The toggle is labelled **"Count transfers between my own accounts"** (Settings and a chip on Dashboard). Turning it on double-counts by design; a one-line hint says so.

"Spend" everywhere = Σ expense-type amounts − linked refunds, per the column above. Every chart, the Home total and the table footer use this function; nothing re-implements it.

## 7. Golden cases for this doc (in doc 12)

G-SPAM (promo, OTP, phishing from a mobile number, `-P` header, missing suffix pre/post cut-off), G-DUP (same ref twice, two equal coffees), G-XFER (both sides seen, one side seen with own VPA, NEFT next-day credit), G-CARD (spend + bill payment, combined total counted once), G-REFUND, G-REVERSAL.
