# 03 — SMS ingestion and parsing

## 1. Permissions

| Permission | Use | When asked |
|---|---|---|
| `READ_SMS` | Month scans, catch-up | First "Scan" tap, with an explainer sheet |
| `RECEIVE_SMS` | Live updates | Right after `READ_SMS` is granted (optional) |
| `POST_NOTIFICATIONS` | Scan progress notification | Before the first scan |
| `USE_BIOMETRIC` | App lock | Settings |

Not requested: `INTERNET`, `SEND_SMS`, contacts, location. If `READ_SMS` is denied, the app still works with statement imports and manual entries.

Sideload note: from Android 15 (and on some Android 13/14 builds), SMS permissions are "restricted settings" for sideloaded apps, so the system dialog may be greyed out or refused. The user allows it once: App info → ⋮ → *Allow restricted settings* → Permissions → SMS → Allow. The permission explainer sheet detects a refused grant, explains these steps and deep-links to App info.

## 2. Reading SMS

- Source: `Telephony.Sms.Inbox.CONTENT_URI`. Columns: `_id`, `address`, `body`, `date` (receipt, ms), `date_sent`, `sub_id` (SIM slot, when present).
- Month bounds: `[first day 00:00, next month first day 00:00)` in `Asia/Kolkata`.
- Pages of 500 ordered by `date`. Raw rows stored in `raw_sms` (body encrypted at rest with the DB; optional auto-delete, doc 09).
- RCS and app-chat messages are not in this provider; they are out of scope (risk R4, doc 11).

## 3. Sender normalisation

Input address examples: `VM-HDFCBK-S`, `AD-ICICIT-T`, `JD-SBIUPI`, `HDFCBK`, `+9198xxxxxx01`.

Algorithm:
1. Upper-case, trim.
2. If it matches `^\+?\d{10,13}$` → `SenderType.PHONE_NUMBER` (never a bank; doc 04).
3. Else split on `-`: optional 2-letter prefix, 6-char header, optional suffix letter `P|S|T|G`.
4. Output `Sender(header="HDFCBK", suffix=S|T|P|G|NONE, prefix="VM")`.

The `banks.yaml` registry maps headers to banks:
```yaml
version: 2026.11.1
banks:
  - id: hdfc
    name: HDFC Bank
    headers: [HDFCBK, HDFCBN]   # verify against your own inbox in P1
    card_headers: [HDFCCC]
  - id: icici
    name: ICICI Bank
    headers: [ICICIB, ICICIT]
```
Header lists are **verified against the user's own inbox** in P1 (a "Senders seen" screen lists every header with counts; the user ticks which belong to their banks). Profile accounts link to these headers.

## 4. Classification (before templates)

Each SMS from an allowed sender is first classified by keyword rules (`spam.yaml` → `classes:`):

| Class | Typical signals | Outcome |
|---|---|---|
| `OTP` | "OTP", "one time password", "do not share" | Ignored |
| `REMINDER` | "is due", "will be debited", "due date", "min amt due", "mandate … scheduled" | Ignored (v1) |
| `FAILED` | "declined", "failed", "unsuccessful", "reversed due to" | Ignored, unless a reversal of a recorded txn (doc 04 §5) |
| `PROMO` | "offer", "pre-approved", "cashback upto", "apply now", URL + no amount verb | Ignored |
| `BALANCE_ONLY` | "available balance" without debit/credit verb | Stored as balance snapshot only |
| `TXN_CANDIDATE` | amount + debit/credit verb ("debited", "spent", "withdrawn", "credited", "received", "paid") | Goes to templates |

## 5. Templates

Per bank, an ordered list of regex templates with named groups. Templates are data (`templates/<bank>.yaml`). Example (synthetic, not a real bank's wording):

```yaml
bank: hdfc
templates:
  - id: hdfc.upi.debit.v1
    kind: DEBIT
    channel: UPI
    pattern: >-
      (?i)(?:Rs\.?|INR|₹)\s?(?<amount>[\d,]+(?:\.\d{1,2})?)\s+debited
      from\s+a/c\s+\*{0,2}(?<last4>\d{4})\s+on\s+(?<date>\d{2}-\d{2}-\d{2,4})
      to\s+VPA\s+(?<vpa>[\w.\-]+@[\w.\-]+)
      .*?UPI\s*Ref\s*(?:No\.?)?\s*(?<ref>\d{12})
    date_format: dd-MM-yy
  - id: hdfc.card.spend.v1
    kind: DEBIT
    channel: CARD
    account_type: CREDIT_CARD
    pattern: >-
      (?i)spent\s+(?:Rs\.?|INR|₹)\s?(?<amount>[\d,]+(?:\.\d{1,2})?)\s+on\s+.*?card\s+x+(?<last4>\d{4})
      \s+at\s+(?<merchant>.+?)\s+on\s+(?<date>\d{4}-\d{2}-\d{2})
```

Named groups the engine understands: `amount`, `last4`, `date`, `time`, `vpa`, `merchant`, `payee`, `ref`, `balance`, `channel_hint`.

Rules:
- First matching template wins; template order is explicit.
- `amount` → `Long` paise (strip commas; `.5` → 50 paise). Must be > 0.
- `date` parsed with `date_format`; if absent or unparsable, use SMS receipt time. A date more than 3 days from receipt flags `date_suspect`.
- `last4` must match an account in the profile for that bank; otherwise → Needs review ("unknown account").
- Confidence: 1.0 for a full template match with last4 match; 0.8 if `ref` missing; < 0.7 → Needs review.
- Real templates are written in P3 from the user's own SMS (redacted samples in a git-ignored folder, doc 12). Synthetic fixtures are used in the committed golden set.

### Teach a template (P7)
From a Needs review item the user taps the amount, the account digits and the payee in the SMS text. The app builds a template: literal text kept, the tapped spans become named groups, digits elsewhere become `\d+`, whitespace becomes `\s+`. It is tested against that month's other SMS from the same sender and shows how many it would match before saving as a **user template** (stored in DB, priority above shipped ones).

## 6. Merchant resolution

Input: `vpa`, `merchant`, or `payee` text. Output: `Counterparty(id, displayName, type=MERCHANT|PERSON|OWN_ACCOUNT|BANK|UNKNOWN)`.

Order:
1. **User alias** (exact raw key → counterparty), created whenever the user renames/merges ("always map `paytm-123@ptys` to Swiggy").
2. **Own identity**: VPA in the profile's own UPI IDs or payee matching own name variants → `OWN_ACCOUNT` (doc 04 §4).
3. **Shipped merchant dictionary** `merchants.yaml`: VPA handle prefixes, regexes and keywords per brand, with default category:
   ```yaml
   - id: swiggy
     name: Swiggy
     category: food_dining
     match:
       vpa_prefix: [swiggy]
       keywords: [SWIGGY, BUNDL TECHNOLOGIES]
   - id: blinkit
     name: Blinkit
     category: groceries
     match: { vpa_prefix: [blinkit], keywords: [BLINKIT, GROFERS] }
   - id: rapido
     name: Rapido
     category: transport
     match: { keywords: [RAPIDO, ROPPEN] }
   ```
   (Exact VPAs/legal names are filled from the user's real data in P3; the above are illustrative.)
4. **Aggregator unwrap**: if the payee is a payment aggregator (Razorpay, PayU, Cashfree, BillDesk, Paytm PG…), look for a second name in the SMS; if none, keep `"Razorpay · unknown merchant"` and send to Needs review once per new VPA.
5. **Person heuristic**: VPA that looks like a phone number or a personal handle, or payee with 2–3 capitalised words → `PERSON` (category "Transfers to people").
6. Else `UNKNOWN` → shown with the raw text, flagged for review.

Normalised display names are title case; the raw text is always kept.

## 7. Categorisation

1. User rule for that counterparty (set by editing any transaction: "apply to all from this merchant").
2. Merchant default category.
3. Channel/kind rules: ATM → Cash; own transfer → Self transfer; card bill → Card bill payment; salary keywords on credit → Salary; "refund" → Refund.
4. Else `Uncategorised`.

## 8. Field validation before writing

- Amount ≤ ₹10,00,000 unless confirmed (catches parse errors like reading a balance as the amount).
- `balance` (if present) differs from `amount`.
- Same template id must not produce both debit and credit.
- Failures → Needs review with the reason.
