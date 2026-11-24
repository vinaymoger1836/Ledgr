# 08 — UI, UX and motion

See the interactive mockup artifact for the visual reference. This doc is the spec Claude Code builds against.

## 1. Navigation

Bottom navigation bar with 4 destinations + a review badge:

| Tab | Icon | Notes |
|---|---|---|
| Home | house | Default |
| Dashboard | chart | |
| Reconcile | two-arrows check | |
| Profile | person | Accounts, identities, categories, merchants, Settings entry |

**Needs review** opens from the Home banner and from a badge on the Home tab (count of open items). Settings opens from Profile.

## 2. Screens

### 2.1 Home
1. Top bar: greeting, **month picker chip** ("Nov 2026 ▾") opening a month grid sheet (months with scanned data show a dot; future months disabled).
2. **Hero card**: current month → "Today's spends ₹1,240" with a count-up, small delta vs daily average ("▲ 18% vs your usual Monday"); past month → "November total ₹41,830".
   Below: "This month ₹18,420 · Income ₹85,000".
3. **Scan button** in the hero: "Scan Nov SMS". While scanning: circular progress with "Read 1,240 / 3,050" and live counters (transactions, ignored, review). On finish: a summary snackbar ("96 transactions · 7 to review").
4. **Last 7 days** mini bar chart (tap a bar → day sheet).
5. **Where it went** — horizontally scrolling merchant chips (avatar, name, amount), sorted by amount; "See all" → Dashboard W7.
6. **Needs review** banner if items are open ("7 SMS need a look").
7. **Recent** — transactions grouped by day: avatar, merchant/payee, category · account chip, time, amount (−/+ with colour). Swipe left → change category; tap → detail.
8. FAB "+" → manual entry (amount, account, merchant, category, date).

### 2.2 Dashboard
Doc 07 widgets in this order: controls → KPI row → spend bars → forecast → categories → where money went → weekly/monthly trends → recurring → statement-style table.

### 2.3 Reconcile
Start state: account chips + "Import statement" card (PDF · Excel · CSV icons), history list. Then: password sheet (PDF) → mapping screen (only when new) → results (summary ring + four tabs, doc 06 §4).

### 2.4 Profile
Sections: **Accounts** (cards styled like bank cards: bank colour, nickname, type, ••last4; add/edit sheet with bank picker, type, nickname, last 4, detected sender headers with checkboxes) · **My UPI IDs & names** · **Categories** (reorder, rename, merge, icon/colour) · **Merchants** (aliases, icon, default category) · **Senders seen** (all headers in the inbox with counts and "belongs to bank X") · **Settings**.

### 2.5 Needs review
Tabs: **Unparsed** (bank SMS with no template; actions: teach template, ignore sender pattern, add manually) · **Check** (low confidence, unknown merchant, date suspect) · **Suspicious** (quarantined; actions: "It's genuine — record it", "Delete") · **Duplicates & transfers** (confirm/reject merges and links).

### 2.6 Settings
Theme (System/Light/Dark), App lock (biometric, lock after 1/5/15 min in background), Count own transfers toggle, Auto-delete raw SMS (Never/30/90 days), Storage (doc 09), Backup export/import, Rules version and "Re-parse a month", About.

## 3. Visual system

- Material 3 with custom tokens (not dynamic colour by default; optional toggle). Light and dark from the same token set.
- Palette roles: `primary` (deep indigo), `spend` (warm coral), `income` (green), `transfer` (slate blue), `warning` (amber), `danger`; category colours from a fixed 10-colour accessible set checked for contrast in both themes.
- Type: system font (Roboto / Google Sans if present); amounts use tabular figures.
- Shapes: 20 dp cards, 12 dp chips, 28 dp sheets. Elevation mostly tonal.
- Amount formatting: Indian grouping (`₹1,23,456.50`), paise hidden when zero; compact `₹1.2L`, `₹12.4K` in charts.
- Merchant avatar: user image → category icon on category colour → first letter on a colour derived from the name hash.

## 4. Motion spec

| Element | Motion | Duration / easing |
|---|---|---|
| Amounts (hero, KPIs) | Count-up from previous value | 600 ms, `FastOutSlowIn` |
| Lists (recent, table day groups) | Staggered fade + 8 dp rise | 40 ms stagger, max 8 items, 220 ms |
| Cards / chips on press | Scale 0.97 + ripple; haptic tick on long-press | 100 ms |
| Hover (mouse/stylus/ChromeOS) | Tonal overlay + 2 dp lift via `Modifier.hoverable` | 120 ms |
| Tab switch | Fade-through | 250 ms |
| Txn row → detail | Shared element (avatar + amount) | 300 ms, emphasized |
| Charts | Bars grow from baseline, lines draw left→right, donut sweeps | 500–700 ms on first show; 250 ms on filter change |
| Scan | Circular progress + counters ticking; success check morph | continuous; 400 ms morph |
| Month change | Content cross-fade + slide in the direction of time | 280 ms |
| Pull to refresh (Home) | Runs catch-up scan | standard |

Rules: animation only where it explains a change; never block input; respect the system animator scale (`Settings.Global.ANIMATOR_DURATION_SCALE == 0` → no motion, instant state changes).

## 5. Accessibility

- Touch targets ≥ 48 dp; content descriptions for icons and avatars; amounts read as "minus 450 rupees, Swiggy, Food and dining".
- Colour is never the only signal (− / + signs, icons, labels).
- Charts have a "View as table" option and TalkBack summaries ("Spending peaked on Saturday the 15th at ₹3,200").
- Font scale up to 200% without clipping (amounts wrap, tables scroll horizontally).
- Contrast ≥ 4.5:1 for text in both themes.

## 6. Empty, loading and error states

- First run: 3-step onboarding (what the app does and that data never leaves the phone → add accounts → grant SMS and scan this month).
- No data for month: illustration + "Scan Nov SMS" button.
- Permission denied: explainer + "Open App info" (doc 03 §1) + "Import a statement instead".
- Scan errors: per-message errors never stop the scan; counted under "errors" with a "View" link.
