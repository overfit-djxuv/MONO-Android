# MONO for Android: system design

Status: plan, nothing built yet. Source of truth for behaviour is the iOS app (`~/MONO`, v2.0.2). This app is a port, not a redesign: same screens, same rules, same numbers.

## 1. Goals and non-goals

- Same app on Android: accounts, transactions, transfers, budget, goals, shopping, subscriptions, safe to spend, purchase simulator, what-if lab, monthly review, insights, receipts (photo + OCR + manual), backup and restore, themes, hide amounts, app lock.
- Offline only. The app declares no INTERNET permission, so it cannot send data anywhere.
- Distributed as a signed APK on GitHub Releases.
- Non-goals for now: Play Store listing, widgets, cloud sync, tablets or Wear OS. Nothing beyond what iOS MONO does.

## 2. Platform choices

| Concern | Choice | Why |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 as a base only | Native; MONO draws its own muted look through `Theme` tokens |
| Min / target SDK | min 26 (Android 8), target = newest installed platform | `java.time` works without desugaring; covers nearly all phones |
| Storage | Room (SQLite) + DataStore (preferences) | Room is the SwiftData equivalent; DataStore replaces `UserDefaults` |
| DI | Manual (a single `AppContainer`) | ~15k lines; Hilt is not worth the build cost |
| Navigation | Navigation Compose, single Activity, five bottom tabs | Matches Dashboard / Transactions / Budget / Goals / Shopping |
| Async | Coroutines + Flow; ViewModels expose `StateFlow` | Room queries are Flows, screens update like `@Query` does |
| Charts | Compose Canvas (own code) | Charts are simple and must follow the muted theme exactly |
| Lock | `androidx.biometric` (fingerprint/face or device PIN) | Equivalent of Face ID + passcode |
| Reminders | WorkManager + notifications (`POST_NOTIFICATIONS`) | Equivalent of local notifications |
| Receipts | Photo picker / CameraX + ML Kit text recognition (on device) | Same limit as iOS: Latin scripts only, Burmese is photo + manual entry |
| Quick actions | Launcher app shortcuts (`ShortcutManager`) | Equivalent of the icon long-press menu |
| Backup files | Storage Access Framework (user picks the location) | No storage permission needed |

## 3. Modules and layers

```
:domain   pure Kotlin/JVM, no Android imports      <- port of MONO/Domain (+ its 3,000 lines of tests)
:app      Android application
            data/      Room entities, DAOs, repositories, DataStore, backup, CSV import
            platform/  biometric lock, notifications, shortcuts, camera/OCR, file picker
            ui/        Theme, shared components, screens + ViewModels
```

Dependency rule: `ui -> data -> domain`, and `platform` is used by `data`/`ui` through small interfaces. `:domain` never sees Room, Android or Compose. That keeps the money logic testable on the JVM in milliseconds, exactly like the iOS `MONOTests`.

```
 Compose screen ──events──▶ ViewModel ──▶ Repository ──▶ Room DAO ──▶ SQLite
       ▲                        │              │
       └───── StateFlow ◀───────┘              └──▶ :domain engines (pure functions)
```

Screens never compute money. A ViewModel loads rows, hands them to a `:domain` engine and exposes the result.

## 4. Domain engines (`:domain`), ported one for one

| iOS file | Kotlin | Notes |
|---|---|---|
| `Money`, `Currency`, `MonthKey` | `Money`, `Currency`, `MonthKey` | Amounts are `Long` minor units (cents). `MonthKey` is `"yyyy-MM"` |
| `FinanceEngine` | `FinanceEngine` | totals, budget summary, savings rate, category slices, monthly summaries |
| `AccountEngine`, `Books` | `AccountEngine`, `Books` | balances, transfers, cross-currency, reporting currency + manual rates |
| `SavingsPlanner` | same | schedule, required monthly, estimated completion |
| `SafeToSpend` | same | pure; config passed in |
| `SubscriptionSchedule` | same | due dates, confirm-to-record, occurrence keys |
| `Insights`, `DashboardInsight` | same | |
| `PurchaseSimulator`, `WhatIf` | same | simulations never touch balances |
| `MonthlyReview`, `CategoryWeeks` | same | |
| `ReceiptParser`, `ShoppingQuickAdd` | same | |

Behavioural rules carried over unchanged:
- Money text is produced in one place, `Money.format`, so Hide amounts masks everything. Any new string with an amount must use it.
- Planned or simulated amounts never change balances; only confirmed records do. Transfers are never income or spending.
- Recurring payments are confirm-to-record, never auto-deducted.

Porting hazards (each gets a test):
- Rounding: Swift `.rounded()` is half away from zero; Kotlin `roundToInt()` is half up. Use one explicit helper.
- Calendar maths (month ends, "whole calendar months left", weeks of the month) must use `java.time` with the same week start as iOS tests assume.
- Number and date text depend on locale; the engines return numbers, the UI formats them.

## 5. Data model (Room)

One table per SwiftData model, same field names and defaults, so the two apps stay comparable:
`MoneyCategory`, `Account`, `ExchangeRate`, `TransactionRecord`, `MonthBudget`, `BudgetLimit`, `PlannedExpense`, `ShoppingItem`, `SavingsGoal`, `GoalEntry`, `TripCost`, `RecurringRule`, `ReservedFund`, `Receipt`.

- IDs are UUID strings (same as iOS), so a record keeps its identity across platforms.
- Dates are stored as epoch milliseconds (UTC) and shown in the device time zone.
- Enums are stored as their iOS raw strings (`"expense"`, `"monthly"`, ...).
- New columns always have defaults; every schema change ships with a Room migration and a migration test on a seeded database. Room schemas are exported to `app/schemas/` and committed.
- Settings that live in `UserDefaults` on iOS (reporting currency, symbol override, safe-to-spend config, theme state, reminder hour, last backup time) go to DataStore with the same keys.

## 6. UI and theme

- `Theme.kt` is the only place colours appear. Palettes are the same three presets (Sage & Cream, Warm Minimal, Midnight Elegance) with light and dark variants plus custom palettes. No neon, no saturated colours; the existing test that checks chroma < 0.30 and WCAG contrast is ported.
- Shared components mirror `Design/Surfaces`: `Surface`, `SectionTitle`, `StatStrip`, `StatTile`, `RingGauge`, `ProgressRow`, `QuickChip`, `Callout`, charts.
- Hide amounts is a `CompositionLocal` read by `Money.format` callers and a blur modifier for charts.
- Dashboard order matches iOS 2.0.2: hero (Available, Safe to spend, set aside), income/expenses/budget ring/saved/per day tiles, quick chips, one insight, accounts, cash flow, spending by category, goals, savings growth, coming up, recent, All analytics.
- App icon: adaptive icon from the same artwork. Alternate icons are not portable (Android has no safe equivalent without hacks), so they are out of scope for Android.

## 7. Backup and portability

- The Android app reads and writes the same backup format as iOS (JSON, format 6) and the `.monobackup` envelope (SHA-256 checksum, optional AES-GCM with PBKDF2). That lets a user move data between platforms. Before committing to this, port the exact field names, KDF parameters and version rules from `BackupService.swift` and `BackupVault.swift`, and prove it with a round-trip test using a file produced by the iOS app.
- Passphrase lives in the Android Keystore (encrypted preference), never in a backup file.
- Restore makes a safety copy first and offers a test restore, as on iOS.

## 8. Privacy and security

- No INTERNET permission, no analytics, no crash reporters.
- `allowBackup="false"` for Android cloud backup of app data (users use MONO's own backup); screenshots of the app switcher are hidden when Hide amounts or the lock is on (`FLAG_SECURE`, optional setting).
- Database is private app storage. Optional encryption at rest is a later decision, not v1.
- The public repo holds no personal data: the signing keystore and its passwords live outside the repo, `local.properties` and `keystore.properties` are git-ignored, and `applicationId` is a neutral name (`app.mono.finance`).

## 9. Build, test, release

- Gradle with version catalog; JDK 17; Android command-line SDK installed through Homebrew.
- Tests: `:domain` JUnit tests (port of the iOS suite first, so numbers match), Room in-memory and migration tests, Compose UI tests and a screenshot tour on an arm64 emulator (Pixel profile).
- Version: `versionName` follows the feature set, starting at `0.1.0` and reaching `1.0.0` at parity with the iOS screens listed above; `versionCode` increments every build. A changelog and in-app version history mirror iOS.
- Release: `assembleRelease`, signed with a release keystore generated once. **Losing that keystore means installed copies can never be updated**, so it gets backed up outside the repo. The APK and its SHA-256 are attached to a GitHub Release; the README has the download link and install steps ("allow installs from this source").
- Scripts: `tools/release.sh` builds, signs, verifies with `apksigner`, and writes the checksum.

## 10. Delivery stages

1. **Foundation:** Gradle project, theme, `Money`, Room models, DataStore, navigation shell. Emulator boots to an empty Dashboard.
2. **Engines:** all of `:domain` with ported tests green.
3. **Core screens:** Dashboard, Transactions (form, detail, filters), Budget, Goals, Shopping.
4. **Everything else:** Accounts/transfers/currency, Subscriptions, Tools (safe to spend, simulator, what-if, review, insights), Analytics, Settings/Theme, Backup/restore + CSV import, app lock, reminders, receipts, shortcuts.
5. **Release 1.0:** signing, APK on GitHub Releases, README download section.

Each stage ends with tests passing and a commit; nothing is released until you say so.

## 11. Open decisions

- Keep `applicationId = app.mono.finance`, or pick another neutral name?
- Cross-platform backup compatibility (section 7) is the default; say if Android should use its own format instead.
- Which emulator or real Android phone will you use for hands-on checks? Camera, biometrics, notifications and launcher shortcuts can only be fully confirmed on a real phone.
