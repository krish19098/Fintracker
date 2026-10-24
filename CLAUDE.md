# SpendSense — Personal UPI Expense Tracker (Android)

## Mission
Build a complete, production-quality Android app, **in one session**, that automatically captures every UPI and bank transaction from:
- bank SMS on **both SIMs** of a dual-SIM phone
- GPay and Paytm notifications

It deduplicates them by UPI reference number, categorises them, alerts on budget limits, and shows clear daily, weekly and monthly spending insights.

Rules for this build:
- Fully offline: no network permission, no backend, no analytics SDKs.
- Personal use, sideloaded APK. This is **not** for the Play Store.
- Target device: Xiaomi/Redmi/Poco (HyperOS/MIUI).

## Build order (do in sequence; verify each step before moving on)
1. **Project scaffold.** Gradle KTS, version catalog, single `app` module. Run `./gradlew assembleDebug` to confirm it builds.
2. **Parser (pure Kotlin, no Android deps).** Write the parsers and JVM unit tests using every fixture in `fixtures/`. All tests must pass before continuing.
3. **Data layer.** Room + SQLCipher, entities, DAOs, migrations scaffold, and the dedup/merge logic with unit tests.
4. **Ingestion.** SMS receiver, notification listener, inbox scanner (WorkManager), first-run backfill.
5. **Domain.** Categoriser, exclusions, alerts, insights engine, each with unit tests.
6. **UI.** Compose screens and navigation.
7. **Final check.** If the Android SDK is available locally, run `./gradlew test assembleDebug` and fix all warnings that indicate real bugs. If it is not available (cloud session), skip the local build and rely on CI (see Build & delivery). Write a short README with install steps. Commit and push.

## Build & delivery (mobile-only workflow, no PC)
The user works only from his phone, so the APK is built by GitHub Actions and downloaded on the phone.

- Add `.github/workflows/android.yml`. It triggers on push to `main` and runs on `ubuntu-latest` with:
  - JDK 17
  - `android-actions/setup-android`
  - Gradle cache
  - `./gradlew test assembleRelease`
- **Stable signing, so updates install over the old app without wiping data:**
  1. Generate `keystore/spendsense.jks` once with `keytool`.
  2. Commit it to this **private** repo.
  3. Put the store/key passwords in `keystore/keystore.properties`. Also committed; this is acceptable for a private personal repo, and the README must note it.
  4. `release` signs with this keystore. Keep R8 minify off for v1.
  5. Never regenerate the keystore after the first commit.
- **Release on every build:** create a GitHub Release tagged `v1.0.<run_number>` with `app-release.apk` attached, using `softprops/action-gh-release`. This gives the user a direct APK download link he can open in the phone browser.
- `versionCode = GITHUB_RUN_NUMBER` (default 1 locally), so every build installs as an update.
- If you can't run the build yourself, review the Gradle files carefully for version compatibility (AGP ↔ Gradle ↔ Kotlin ↔ Compose compiler plugin ↔ Room/KSP). Pin known-compatible versions in the version catalog.

Do not stop for confirmation between steps. If a decision is not covered here, pick the simplest option consistent with this file and note it in the README under "Assumptions".

## Tech stack (fixed)
- Kotlin, Jetpack Compose, Material 3 (dynamic color off; custom theme below), single-activity, Navigation Compose
- Room + SQLCipher (`net.zetetic:sqlcipher-android`). Generate the DB key once and store it in Android Keystore-backed `EncryptedSharedPreferences`.
- DataStore (Preferences) for settings
- WorkManager for periodic jobs
- Vico for charts
- BiometricPrompt for app lock
- minSdk 26, targetSdk/compileSdk = latest stable, JDK 17
- **No DI framework.** Use a single `AppContainer` created in the `Application` class.
- **No network.** Do not declare the `INTERNET` permission.

## Package layout (single module)
```
com.krish.spendsense
  ingest/      SmsReceiver, TxnNotificationListener, InboxScanWorker, Backfill
  parse/       ParsedTxn, BankParsers (HDFC, CUB, AXIS), AppNotifParsers (GPAY, PAYTM), MessageFilter
  data/        db entities, DAOs, Repository (single), dedup/merge
  domain/      Categorizer, Exclusions, AlertEngine, InsightsEngine
  ui/          theme, navigation, screens/*, components/*
  AppContainer.kt, SpendSenseApp.kt, MainActivity.kt
fixtures/      sample SMS and notification texts (redacted) + expected results
```

## Sources

### 1. Bank SMS (primary, both SIMs)
- Declare a `BroadcastReceiver` for `android.provider.Telephony.SMS_RECEIVED` in the manifest, so it fires even when the app is killed.
- Read the subscription id from the intent extras (`subscription` / `android.telephony.extra.SUBSCRIPTION_INDEX`). Map it to SIM slot and carrier via `SubscriptionManager`, then store `simSlot` (1 or 2) on the transaction.
- **Inbox scanner.** WorkManager runs every 15 minutes, plus once on every app open. It queries `content://sms/inbox` for messages after the stored `lastScannedTimestamp`, using the `sub_id` column for the SIM. This catches anything the receiver missed when HyperOS killed the app.
- **First-run backfill.** Scan the last 180 days of the SMS inbox, so insights work from day one. Show progress in the UI.
- **Sender filter.** DLT headers look like `XX-HDFCBK` or the newer `XX-HDFCBK-S` / `-T` / `-P`. Strip the 2-letter prefix and any trailing `-S`/`-T`/`-P`/`-G` category suffix, then match the middle key, case-insensitive. Initial sender keys, to verify against the fixtures:
  - HDFC: `HDFCBK`, `HDFCBN`
  - Axis: `AXISBK`
  - CUB: `CUBANK`, `CUBLTD`

  Senders must be configurable: Settings → Accounts → "Add sender ID".
- Drop OTPs, promotional messages and balance-enquiry-only messages. Detection: OTP keywords, or no debit/credit verb plus amount.

### 2. App notifications (enrichment)
- `NotificationListenerService` that only processes these packages:
  - GPay: `com.google.android.apps.nbu.paisa.user`
  - Paytm: `net.one97.paytm`

  Ignore all other packages immediately, without reading their content.
- Extract payee name, amount, direction and UPI ref if present. Notifications provide the **payee name and app**. SMS provides the **account, balance and ref**.
- Call `requestRebind` in `onListenerDisconnected`. Show listener health on the Settings screen.
- **Debug capture mode** (a toggle in Settings, off by default): notifications from the two packages that fail to parse are saved to the Review inbox, so parsers can be tuned.

### 3. Unparsed messages
Any SMS from a known bank sender that fails parsing goes into the `UnparsedMessage` table and appears in the Review inbox. From there the user can:
- create the transaction manually, prefilled with any partial parse, or
- dismiss the message.

Raw text is deleted once the item is resolved.

## Parsing
`ParsedTxn` fields:
- `amountPaise: Long`
- `direction: DEBIT | CREDIT`
- `bank`
- `accountLast4?`
- `upiRef?` (12-digit RRN)
- `payeeName?`
- `payeeVpa?`
- `note?`
- `balanceAfterPaise?`
- `timestamp`
- `source: SMS | NOTIF | IMPORT`
- `app: GPAY | PAYTM | OTHER_UPI | NON_UPI`
- `simSlot?`
- `counterpartyAcctLast4?` (when money goes to an account number rather than a VPA or name; used for self-transfer detection against own accounts)
- `upiType?` (`P2M` | `P2P` | `P2A`, when the SMS states it)

Date and time rules:
- If the SMS has both date and time, use them, in IST.
- If it has only a date, combine it with the SMS received time when the received date matches. Otherwise use that date at 12:00 IST.
- Accept these date formats: `dd-MM-yy`, `dd-MM-yyyy`, `dd/MM/yy`, `dd/MM/yyyy`, `dd-MMM-yy`.
- **RRN date check (soft):** a 12-digit UPI RRN usually starts with the last digit of the year followed by the 3-digit day-of-year. For example, `626017540942` → 2026, day 260 → 17 Sep.
  - Use this to fill in the date if it's missing.
  - If it disagrees with the parsed date, log it in debug builds only. Never reject or flag the transaction over it, because not all banks follow the pattern.

Parsing rules:
- Use one parser per bank or app. Each tries an ordered list of regexes and returns the first full match.
- Amounts may appear as `Rs.`, `Rs`, `INR` or `₹`, with or without commas and decimals. Parse them into paise. **Never use floating point for money.**
- UPI ref: a 12-digit number near `UPI`, `Ref`, `RRN` or `UPI Ref No`.
- VPA: `[\w.\-]+@[\w]+`.
- If the SMS gives no VPA but the notification gives a name, merge them (see Dedup).
- **Every fixture in `fixtures/` must have a unit test asserting the full expected `ParsedTxn`.**

## Dedup and merge (critical)
1. **Primary key:** `upiRef`. If a transaction with the same `upiRef` exists, **merge** into it instead of inserting:
   - keep account, balance and simSlot from SMS
   - keep payeeName and app from the notification
   - OR the source flags together
2. **Fallback** (no ref on one side): same `amountPaise` + same `direction` + timestamp within ±3 minutes + compatible account. Treat as the same transaction and merge.
3. Store `rawHash` (SHA-256 of sender + body + timestamp) so the same SMS is never processed twice, whether it arrives via the receiver, the scanner or the backfill.
4. All of this runs in one Room transaction, so a race between the receiver and the listener can't create duplicates.

## Accounts and SIMs (SMS-driven, fully user-editable)
Tracking is **account-wise**. Every transaction belongs to an account identified from its SMS (bank + last4). Users must be able to change every number and account preference easily, with no technical knowledge, in at most 2 taps from Settings.

### SIM / number management (Settings → Numbers & SIMs)
- Each active SIM is detected via `SubscriptionManager` and shown as a card with:
  - slot
  - carrier
  - a user label, e.g. "Personal", "Work"
  - the phone number. Prefill it if Android exposes it; otherwise the user types it. It's display only.
- Per-SIM controls:
  - **Rename label**
  - **Edit number**
  - **Track SMS from this SIM** (on/off toggle). When off, SMS from this SIM are ignored.
  - **Linked accounts**: a list of accounts on this SIM, with a shortcut to move them
- **SIM change detection.** On app open and on boot, compare the current subId per slot with the stored ones. If a SIM was swapped or replaced, show a one-screen prompt: "New SIM detected in slot 2 — is this a new number?"
  - **Keep accounts linked:** remap the existing accounts to the new SIM.
  - **New number, set up fresh:** keep the old accounts but mark the old SIM inactive.

  Never lose history on a SIM change.

### Account management (Settings → Accounts)
- Accounts are auto-created from bank + last4 the first time they're seen. A notification asks the user to name the new account: "New account found: HDFC ••1234 — give it a name?"
- Per-account preferences on one simple edit screen, with plain-language labels:
  - Nickname, color and icon
  - **SIM / number it gets alerts on**: auto-set from the SMS, changeable via a dropdown
  - **Auto-update SIM when alerts start arriving on another number** (default ON). When the SIM changes, show a toast: "HDFC ••1234 now on SIM 2"
  - **Track this account** (on/off): pauses new captures while keeping history
  - **Include in spending totals** (on/off), e.g. to exclude a business account from personal totals
  - **This is my own account** (used for self-transfer detection)
  - **This is a credit card**
  - **Optional daily limit** for this account, in addition to the global limit
  - **Bank SMS sender IDs**: view, add or remove
  - **Set as primary account**: shown first on Home
- Account actions:
  - **Merge accounts**, for when the same account was detected twice, e.g. a different last4 format. All transactions move into the kept account.
  - **Delete account**: either "Keep its transactions as Unassigned" or "Delete its transactions too". Confirm with a clear warning.
- Transactions with no matched account still save as Unassigned and appear in the Review inbox. There the user can assign the account once, with an "Always use this account for this sender/last4" option.

### Account-wise tracking
- **Account detail screen** (tap any account card):
  - latest known balance, taken from the most recent SMS that included one, with an "as of" time
  - this month's spend and income
  - category split
  - the account's transaction list, with the same filters as the Transactions tab
- **Home:** a horizontal row of account chips (All · HDFC ••1234 · CUB ••5678 · Axis ••9012). Selecting one filters every Home figure to that account. The primary account shows first.
- **Insights:** a per-account comparison card and an account filter on every chart.
- Credit transactions without a known account still save, and are flagged for review.

## Categorisation (in order; first hit wins)
1. **PayeeRule**, user-learned. The key is the normalised VPA, or the normalised payee name if there's no VPA. When the user changes a category, ask once: "Always categorise <payee> as <category>?" (Yes / Just this once).
2. **KeywordRule.** Case-insensitive match on payee name, VPA and note. Seed rules:
   - Food: swiggy, zomato, restaurant, hotel, bakery, tea, cafe
   - Groceries: bigbasket, blinkit, zepto, dmart, supermarket, stores
   - Transport: uber, ola, rapido, metro, irctc, redbus, fuel, petrol
   - Bills: airtel, jio, bescom, tneb, electricity, broadband, recharge
   - Shopping: amazon, flipkart, myntra, ajio, meesho
   - Entertainment: netflix, spotify, hotstar, prime, bookmyshow
   - Health: pharmacy, apollo, medplus, hospital, clinic
   - Rent: rent
3. Otherwise **Uncategorised**, with a "Needs review" badge.

Default categories, each with an icon and color: Food, Groceries, Transport, Bills & Recharge, Shopping, Entertainment, Health, Rent, Transfers to People, Education, Others, Uncategorised. Users can add, edit and delete categories; deleting one moves its transactions to Others.

A payee VPA that looks like a person (no merchant keyword and a personal-handle pattern such as `@okaxis`, `@oksbi`, `@ybl`, `@paytm`) defaults to **Transfers to People**.

## Exclusions (never count as spending)
- **Self-transfer:** a debit from one own account with a matching credit (same amount, ±10 minutes) to another own account, or a payee VPA the user has marked as their own. Mark both legs as excluded.
- **Credit-card bill payment:** a payee or note containing "credit card", "cc payment", "card bill", or a user-marked CC payee.
- **Refund:** a credit containing "refund" or "reversal". If found within 30 days, link it to the original debit; the net amount counts.
- Credits are tracked as **income** and shown separately. They never offset spending totals, except refunds.

Excluded transactions show in lists with a small tag, and the user can toggle exclusion manually.

## Alerts (local notifications)
Notification channels: Transactions, Limits, Summaries.

- **Per transaction:** "₹250 → Ravi Tea Stall · Today ₹1,840 / ₹2,000". Action buttons:
  - top 3 suggested categories
  - "Other…", which opens a quick sheet
  - "Add note", via inline reply
- **Daily limit:** warn at 80% (configurable) and alert at 100%. Fire each once per day. Default limit ₹1,000, set during onboarding.
- **Category monthly budgets** (optional per category): alerts at 80% and 100%.
- **Large transaction:** a single debit at or above the threshold (default ₹5,000).
- **End-of-day summary** at 21:30, configurable: "₹2,340 today · 60% Food · biggest: Swiggy ₹620".
- **Weekly digest** Sunday 20:00 and **monthly digest** on the 1st at 09:00. These open the Insights screen.
- Use exact-time WorkManager or AlarmManager; reschedule on boot via `BOOT_COMPLETED`.

## Insights engine (rules only, offline)
Computed on device. Each insight is a card with a title, a one-line explanation and an optional action.

- **Category change:** a category's spend this month vs last month, flagged when it changes by 30% or more and ₹500 or more. Example: "Food ↑ 2× — ₹4,200 vs ₹2,100".
- **Frequency:** "Swiggy 14 times this month (₹3,860)" for any payee with 8 or more transactions per month.
- **Small leaks:** the sum of debits under ₹100 this month, shown when it's ₹1,000 or more.
- **Weekend vs weekday:** average daily spend on each, shown when they differ by 25% or more.
- **Time of day:** the most expensive time band (morning, afternoon, evening, late night).
- **Recurring payments:** the same payee with a similar amount (±10%) at intervals of 25–35 days or 6–8 days, seen at least twice. Show a "Subscriptions & recurring" list with the monthly total and next expected date.
- **Forecast:** month-to-date spend ÷ elapsed days × days in month, compared with the monthly budget. Example: "On pace for ₹38,000 vs ₹30,000 budget — exceeds around the 24th."
- **Suggestions:** for the top category that's over its trend, suggest a cap at its 3-month median and the saving. Example: "Cap Food at ₹3,000 → save ~₹1,200/mo". One tap sets that budget.
- **Streak:** "5 days under your daily limit 🎉" (positive reinforcement).

## UI / UX (user-friendly, minimal taps)
**Theme:** clean, calm, high-contrast. Primary is teal `#0F766E`. Supports dark mode. Large rupee figures, Indian number formatting (₹1,23,456), dates as "Fri, 23 Oct".

**Bottom navigation, 4 tabs:**
1. **Home**
   - Today's spend as a progress ring against the daily limit, colored green, then amber, then red
   - Month-to-date total and forecast line
   - Last 5 transactions
   - A "Needs review (n)" chip, shown only if n > 0
2. **Transactions**
   - Search bar
   - Filter chips: date range, account, SIM, app (GPay / Paytm / Other), category, debit/credit, excluded
   - List grouped by day, each day header showing the day total
   - Tap a transaction for detail: edit category, note, payee, exclusion; source badges (SMS / GPay / Paytm); UPI ref
   - Swipe right to recategorise quickly
   - FAB to add a cash or manual transaction
3. **Insights**
   - Segmented control: Day / Week / Month / Custom
   - Category donut, spend-over-time bar chart, top payees, per-account split
   - Insight cards from the engine
   - Subscriptions list
4. **Settings**
   - **Numbers & SIMs**: labels, numbers, per-SIM tracking toggle, linked accounts (see the Accounts and SIMs section)
   - **Accounts**: full per-account preferences, merge and delete (see the Accounts and SIMs section)
   - Budgets & limits
   - Categories & rules (view and delete learned rules)
   - Notification times
   - App lock
   - **Permission health checklist**
   - Backup/restore (encrypted file export and import), CSV export
   - Debug capture mode
   - About

**Review inbox:** reached from the Home chip. It lists unparsed SMS, uncategorised transactions and unknown accounts, each with one-tap resolution.

**Onboarding wizard:** a short screen per step, each with a "why we need this" line in plain language. Steps:
1. Welcome, with a privacy promise: "Your data never leaves this phone."
2. SMS permission (`RECEIVE_SMS`, `READ_SMS`) and `READ_PHONE_STATE` (for SIM detection)
3. Notification access, deep-linking to the system settings
4. Post notifications permission (Android 13+)
5. **Xiaomi reliability steps**, each with a deep-link and a checkbox:
   - Autostart → ON
   - Battery saver → "No restrictions"
   - Lock the app in Recents
   - Show a check mark once each is done
6. Set daily limit
7. Run backfill (180 days), with progress
8. Label each SIM, e.g. "Personal", "Work", and confirm or enter its number
9. Confirm the detected accounts. Show each one grouped under its SIM, with nickname, SIM and include-in-totals editable inline. Each choice has a "You can change this anytime in Settings" note.

**Permission health:** Settings shows green or red status for each item. Home shows a red banner if SMS or notification access is lost.

**Empty states and errors:** every screen has a friendly empty state. No crashes on missing permissions; degrade gracefully and prompt the user.

**Accessibility:** content descriptions on icons, touch targets of 48dp or more, and support for font scaling.

## Data model (Room)
- `SimProfile`: id, subscriptionId, slot, carrier?, label, phoneNumber?, trackSms (default true), active, lastSeenAt
- `Account`:
  - id, bank, last4, nickname, color, icon, simProfileId?
  - autoUpdateSim (default true), tracked (default true), includeInTotals (default true)
  - isOwn (default true), isCreditCard, isPrimary, dailyLimitPaise?, createdAt
- `AccountSender`: id, accountId?, bank, senderKey (an account-specific override, or a bank-level default when accountId is null)
- `AccountMatchRule`: senderKey + last4 → accountId (created from "Always use this account" in the Review inbox)
- `Txn`:
  - id, upiRef? (indexed, unique when non-null), rawHash (unique)
  - amountPaise, direction, accountId?, payeeName?, payeeVpa?, app, note?
  - categoryId, timestamp (indexed), sourceFlags (bitmask), simSlot?
  - excludedReason? (SELF_TRANSFER | CC_BILL | REFUND | MANUAL), linkedTxnId?
  - balanceAfterPaise?, needsReview
- `Category`: id, name, icon, color, monthlyBudgetPaise?, isDefault
- `PayeeRule`: payeeKey (PK), categoryId
- `KeywordRule`: id, keyword, categoryId
- `UnparsedMessage`: id, sender, body, timestamp, simSlot?, source

Settings go in DataStore: daily limit, warning %, large-txn threshold, summary times, last scan timestamp, onboarding done, app lock.

## Security and privacy
- Encrypted DB.
- Biometric or device-credential lock on open, optional and on by default.
- `FLAG_SECURE` on all screens.
- Never log SMS or notification bodies in release builds.
- Process only allow-listed senders and packages; discard everything else without storing it.
- Backups are encrypted with a user passphrase.

## Testing requirements
- Unit tests:
  - every parser fixture
  - dedup and merge scenarios: SMS then notification, notification then SMS, duplicate SMS, no-ref fallback
  - self-transfer pairing, refund linking, categoriser precedence
  - every insight rule
  - forecast math
  - Indian currency formatting
  - SIM swap remap, automatic SIM update on an account, account merge (transactions moved, totals unchanged), account delete (both options), account match rules
- `./gradlew test assembleDebug` must pass at the end.

## Fixtures (provided by user — REQUIRED before step 2)
`fixtures/sms/<bank>_<n>.txt` and `fixtures/notif/<app>_<n>.txt`, each with a matching `.expected.json`.
- At least 3 debit and 1 credit sample per bank: HDFC, CUB, Axis.
- At least 2 GPay and 2 Paytm notifications.
- Redaction: names are replaced and account digits changed, but punctuation, spacing and structure are kept exactly.

If fixtures are missing for a bank, write the parser against commonly known formats, mark it `// UNVERIFIED FORMAT` and list it in the README.

### Real samples (verified) — create these fixture files from the text below, byte-for-byte including line breaks

**`fixtures/sms/axis_1.txt`** (bank: AXIS)
```
INR 19.00 debited
A/c no. XX9694
09-05-25, 11:37:56
UPI/P2M/512920989783/Google India Digita
Not you? SMS BLOCKUPI Cust ID to 919951860002
Axis Bank
```
Expected:
```json
{"amountPaise":1900,"direction":"DEBIT","bank":"AXIS","accountLast4":"9694","upiRef":"512920989783","upiType":"P2M","payeeName":"Google India Digita","payeeVpa":null,"timestamp":"2025-05-09T11:37:56+05:30"}
```
Notes:
- Axis puts the payee name, truncated to about 20 characters, as the last `/` segment of the line `UPI/<type>/<ref>/<name>`. Prefer the full name from a notification on merge.
- Axis multi-line debit format: amount, then account, then date/time, then the UPI line. Also support `P2A` and `P2P`.

**CUB sender ID confirmed: `JK-CUBANK-S`** (key `CUBANK`). All CUB samples below come from this sender.

**`fixtures/sms/cub_1.txt`** (bank: CUB)
```
Your a/c no. XXXXXXXX6578 is debited for Rs.376.00 on 17-09-2026 and credited to a/c no. XXXXXXXX2708 (UPI Ref no 626017540942)
```
Expected:
```json
{"amountPaise":37600,"direction":"DEBIT","bank":"CUB","accountLast4":"6578","counterpartyAcctLast4":"2708","upiRef":"626017540942","payeeName":null,"payeeVpa":null,"date":"2026-09-17","timeFrom":"SMS_RECEIVED"}
```
Notes:
- This SMS has no bank name in its text, so the bank comes from the **sender ID only**. This single-line template is also used by other banks, so map it via sender ID, not text.
- The counterparty is an account number. If `2708` matches an own account, run self-transfer pairing.

**`fixtures/sms/cub_2.txt`** (bank: CUB; received 2026-08-07 13:46 IST)
```
Your a/c no. XXXXXXXX6578 is debited for Rs.29.00 on 07-08-2026 and credited to a/c no. XXXXXXXX1614 (UPI Ref no 621970896384)
```
Expected:
```json
{"amountPaise":2900,"direction":"DEBIT","bank":"CUB","accountLast4":"6578","counterpartyTail":"1614","upiRef":"621970896384","timestamp":"2026-08-07T13:46:00+05:30"}
```

**`fixtures/sms/cub_3.txt`** (bank: CUB; received 2026-08-07 15:12 IST)
```
Your a/c no. XXXXXXXX6578 is debited for Rs.407.00 on 07-08-2026 and credited to a/c no. XXXXXXXXIPAY (UPI Ref no 212621537807)
```
Expected:
```json
{"amountPaise":40700,"direction":"DEBIT","bank":"CUB","accountLast4":"6578","counterpartyTail":"IPAY","upiRef":"212621537807","timestamp":"2026-08-07T15:12:00+05:30"}
```
Notes:
- The counterparty tail can be **alphanumeric**: here `IPAY` is a merchant or payment-aggregator handle, not an account. Parse it as `counterpartyTail` with the regex `X+([A-Z0-9]{2,6})`.
  - Only a numeric tail is eligible for own-account or self-transfer matching.
  - An alphabetic tail is treated as a merchant: payee name = tail, default category Uncategorised.
- One digit of this ref was obscured in the user's screenshot, so the ref here is a stand-in. It starts with `2`, so it does **not** follow the RRN year/day pattern. This confirms the RRN date check must stay soft and never reject.

**`fixtures/sms/cub_4.txt`** (bank: CUB; credit; **non-UPI** NACH)
```
Savings No XXXXXXXX78 credited with INR 161.00 towards BY ONL NACH_CR/NMDC LTD/ACH_CR/0000060167311:: CA 5109090102 on 23-OCT-2026.Avl Bal 4006.73
```
Expected:
```json
{"amountPaise":16100,"direction":"CREDIT","bank":"CUB","accountTail":"78","app":"NON_UPI","upiRef":null,"payeeName":"NMDC LTD","balanceAfterPaise":400673,"date":"2026-10-23","timeFrom":"SMS_RECEIVED","incomeCategory":"Dividends & Interest"}
```
Notes:
- **Only 2 trailing account digits are given.** Generalise `accountLast4` into `accountTail` (2–4 characters).
  - Match by suffix against known accounts of the same bank. With exactly one match (`6578` ends in `78`), assign it.
  - With zero or several matches, save the transaction as Unassigned and send it to the Review inbox, which offers "Always use this account" and creates an `AccountMatchRule`.
- The account type prefix can be `Savings No`, `a/c no.` or `A/c`.
- The date format is `dd-MMM-yyyy` with an uppercase month.
- The balance has no currency symbol: `Avl Bal 4006.73`.
- The text was transcribed from a screenshot. The parser must tolerate any whitespace or newline variation, and optional spaces after `/`.
- `NACH_CR` / `ACH_CR` mean a bank-transfer credit; the payee is the segment after `NACH_CR/`.

**Income categories** (credits only, kept separate from spending categories):
- Salary
- Dividends & Interest (keywords: `NACH_CR`, `ACH_CR`, `DIVIDEND`, `INT.PD`, `INTEREST`)
- Refund
- Transfer In (from people or own accounts)
- Other Income

The Insights screen shows a monthly Income vs Spend card.

**`fixtures/sms/hdfc_1.txt`** (bank: HDFC)
```
Sent Rs.200.00
From HDFC Bank A/C *9924
To EVEREST SYSTEMS YOGANAND 
On 07/01/26
Ref 600792645725
Not You?
Call 18002586161/SMS BLOCK UPI to 7308080808
```
Expected:
```json
{"amountPaise":20000,"direction":"DEBIT","bank":"HDFC","accountLast4":"9924","upiRef":"600792645725","payeeName":"EVEREST SYSTEMS YOGANAND","payeeVpa":null,"date":"2026-01-07","timeFrom":"SMS_RECEIVED"}
```
Notes:
- HDFC multi-line "Sent" format: `Sent Rs.<amt>` / `From HDFC Bank A/C *<last4>` / `To <name>` / `On dd/MM/yy` / `Ref <rrn>`.
- Trim trailing spaces from the name. Ignore the "Not You?" footer.
- Also support the HDFC credit variant ("Received Rs.… / In HDFC Bank A/C *… / From …"), marked `// UNVERIFIED FORMAT` until a sample is added.

`timeFrom: SMS_RECEIVED` in a test means the test injects a received timestamp on the same date, and asserts that the output uses it.

**Still missing** (write these parsers against common formats, marked `// UNVERIFIED FORMAT`):
- UPI credit SMS for HDFC and Axis, and a UPI (P2P) credit for CUB
- Sender IDs for HDFC and Axis. Keep `HDFCBK`, `HDFCBN` and `AXISBK` as defaults, editable in Settings.
- GPay and Paytm notification texts

Debug capture mode and the Review inbox exist to tune these after install.

## Out of scope (v1)
iOS, cloud sync, any network calls, LLM features, Account Aggregator, Play Store compliance.
