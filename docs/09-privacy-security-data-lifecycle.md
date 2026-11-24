# 09 — Privacy, security and data lifecycle

## 1. Principles

1. Data never leaves the phone unless the user exports it.
2. Store the minimum: last 4 digits only, no passwords, no full account numbers, raw SMS bodies optional.
3. Encrypted at rest; locked when unattended.
4. Deleting is easy, specific and final.

## 2. Controls

| Control | Implementation |
|---|---|
| No network | Manifest has **no `INTERNET` permission**. A golden-check step fails the build if any merged manifest declares it (doc 12). Libraries that need network are not allowed. |
| DB encryption | SQLCipher; 256-bit random key generated on first run, wrapped with an AES-GCM key in Android Keystore (`setUserAuthenticationRequired(false)` so background workers can open the DB; the app lock protects the UI). |
| Files | Imported statements copied to `filesDir/imports/` and deleted after parsing unless the user keeps them (setting). Exports go only where the user picks via SAF. |
| App lock | `BiometricPrompt` with device-credential fallback; lock on cold start and after the chosen background timeout; `FLAG_SECURE` on all screens (no screenshots, hidden in Recents) with a setting to allow screenshots. |
| Backups | `android:allowBackup="false"`, `dataExtractionRules` excluding everything — Android cloud backup never copies the ledger. The app's own encrypted backup is the only way out (§4). |
| Logs | No SMS bodies, amounts or account digits in Logcat in release builds; debug logs redact digits. |
| PDF passwords | Used in memory only; never stored or logged. |
| Rule data | Loaded from assets and DB only; regexes compiled with a timeout guard (bounded input length 2,000 chars) to avoid catastrophic backtracking. |

## 3. Data cleaning ("free/clean the data")

Settings → Storage shows sizes and counts per item, each with its own action and a confirmation sheet (destructive actions also require the app lock):

| Action | Deletes | Keeps |
|---|---|---|
| Delete raw SMS text (all or older than N days) | `raw_sms.body` | hashes, outcomes, ledger |
| Auto-delete raw SMS after N days | same, scheduled daily by `PurgeWorker` | |
| Delete a month | txns, raw SMS rows, scan runs, matches for that month | accounts, categories, aliases |
| Delete statement imports | imports, lines, matches (txns added from statements stay unless "also delete added txns" is ticked) | ledger |
| Clear Needs review | ignored/quarantined raw rows | ledger |
| Reset learning | user aliases, user templates, category rules | ledger |
| **Erase everything** | DB file, key in Keystore, DataStore, `filesDir` | nothing (app returns to onboarding) |

After deletes the DB runs `VACUUM` (SQLCipher supports it) so space is actually freed; the Storage screen shows space before/after.

## 4. Backup and restore (optional, P7)

- Export: JSON of all tables (not raw SMS bodies unless ticked) → compressed → encrypted with a user passphrase (PBKDF2/Argon2-derived key, AES-GCM) → `.etbak` file saved via SAF.
- Import: on a fresh install, pick the file, enter the passphrase, restore. Versioned schema; refuses newer versions than the app.
- Reason: the DB key lives in Keystore; reinstalling or a new phone otherwise loses everything.

## 5. Threat notes

| Threat | Mitigation |
|---|---|
| Phishing SMS creating fake transactions or tricking the user | Gate in doc 04 §1; quarantine; the app never opens links from SMS |
| Lost/unlocked phone | App lock, `FLAG_SECURE`, encrypted DB |
| Malicious statement file | Content-type detection, size limit (20 MB), PDF/XLSX parsing in a worker with timeout; zip-bomb guard (max uncompressed 100 MB, max entries) |
| Supply chain | Few dependencies, pinned versions, permissive licences, no network permission |
