# SpendSense — personal UPI expense tracker (Android)

Captures every UPI / bank transaction from bank SMS on both SIMs and from GPay / Paytm
notifications, de-duplicates them by UPI reference, categorises them, alerts on limits and
shows daily / weekly / monthly insights. Fully offline: no `INTERNET` permission, no backend,
no analytics. Built for Xiaomi / Redmi / Poco (HyperOS / MIUI), sideloaded.

## Install on your phone (no PC needed)

1. Every push to `main` runs **GitHub Actions → Android build**, which runs the unit tests and
   builds a signed release APK.
2. Open the repo's **Releases** page in your phone browser. The newest release is
   `v1.0.<build number>`; download **`app-release.apk`**.
3. Open the downloaded file and allow "Install unknown apps" for your browser when asked.
4. Updates: install the newer APK over the old one. Every build is signed with the same key and
   has a higher `versionCode`, so your data is kept.
5. First launch walks you through permissions, the Xiaomi reliability steps (Autostart, battery
   "No restrictions", lock in Recents), your daily limit and a 180-day SMS backfill.

## Signing key (read this)

`keystore/spendsense.jks` and its passwords in `keystore/keystore.properties` are **committed
on purpose**. This is acceptable only because this is a **private, personal repo** for a
sideloaded app. Anyone with repo access can sign APKs as this app. **Never regenerate the
keystore**: a different key can't update the installed app without uninstalling (and losing
data).

## Project layout

```
app/src/main/java/com/krish/spendsense
  ingest/  SmsReceiver, TxnNotificationListener, InboxScanWorker, Backfill, Ingestor, Notifier, ...
  parse/   ParsedTxn, BankParsers (HDFC, CUB, AXIS), AppNotifParsers (GPAY, PAYTM), MessageFilter
  data/    Room entities + DAOs (SQLCipher), Repository, TxnMerge (dedup/merge), Settings, Backup
  domain/  Categorizer, Exclusions (+Totals), AlertEngine, InsightsEngine, Money
  ui/      theme, navigation (AppRoot), screens, components
fixtures/  real redacted SMS samples + expected results (sms/), notification samples (notif/)
```

Tests: `./gradlew test` — every fixture, parser/filter rules, dedup & merge, accounts/SIMs,
categoriser, exclusions, alerts, every insight rule, forecast math, Indian formatting, and
Room-backed repository scenarios (Robolectric).

## Formats not yet verified with real samples

These parsers are marked `// UNVERIFIED FORMAT` and written against commonly seen wording.
Anything from a known bank sender that fails goes to the **Review inbox**; turn on **Settings →
Debug capture mode** to also collect GPay / Paytm notifications that fail to parse.

- HDFC UPI credit ("Received Rs… / In HDFC Bank A/C…", "credited to HDFC Bank A/c… from VPA…")
  and the older HDFC "debited from a/c… to VPA…" wording
- Axis UPI credit (`INR … credited / A/c no. … / UPI/P2A/…`)
- CUB UPI (P2P) credit (`… is credited for Rs… (UPI Ref no …)`)
- GPay and Paytm notification texts (no samples at all yet)
- Sender IDs for HDFC (`HDFCBK`, `HDFCBN`) and Axis (`AXISBK`) are defaults; edit them in
  Settings → Accounts → "Add sender ID". CUB `JK-CUBANK-S` is confirmed.

## Assumptions

Decisions not covered by the spec, chosen as the simplest consistent option:

- **Versions** (pinned in `gradle/libs.versions.toml`): AGP 8.10.1, Gradle 8.13, Kotlin 2.1.21,
  KSP 2.1.21-2.0.1, Compose BOM 2025.06.00, Room 2.7.1, SQLCipher 4.9.0, Vico 2.1.2,
  compile/target SDK 36, min SDK 26, JDK 17.
- `accountLast4` / `counterpartyAcctLast4` are generalised to `accountTail` / `counterpartyTail`
  (2–6 chars). `ParsedTxn` also carries an `incomeHint` (e.g. NACH/dividend wording) because the
  raw SMS body is never stored.
- Timestamps are epoch millis; all date logic is in IST.
- An alphabetic counterparty tail (e.g. `IPAY`) becomes the payee name when the transaction is
  stored; the parser itself leaves `payeeName` null, matching the fixture.
- No-ref fallback merge only joins rows from **different sources** (SMS + notification). Two SMS
  with the same amount within 3 minutes are kept as two payments.
- `rawHash` uses the SMS service-centre time (`date_sent`), which is identical for the receiver
  and the inbox scanner. A `processed_hash` table records every handled SMS / notification
  (including merged and unparsed ones); `Txn.rawHash` is the first source's hash.
- Android shows at most 3 notification buttons: the per-payment alert has the top 2 suggested
  categories and "Add note"; tapping the notification opens the "Other…" category sheet.
- Summaries use self-rescheduling WorkManager jobs (not exact alarms), so they may fire a few
  minutes late in Doze.
- Before the first backfill the 15-minute scanner only looks back 24 hours.
- Accounts are auto-created only from a 4+ character tail. A 2–3 digit tail is matched by suffix
  against the bank's known accounts; otherwise it stays Unassigned for the Review inbox.
- Unlinked refunds count as income (category Refund); linked refunds (within 30 days) net off
  the original spend.
- Monthly budget defaults to daily limit × days in month unless set in Budgets & limits.
- App lock re-locks after 30 seconds in the background; it is skipped if the phone has no
  screen lock.
- Backup files are AES-256-GCM with a PBKDF2 key from your passphrase. CSV export is plain text.
