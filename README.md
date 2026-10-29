# MONO for Android

**Website:** https://overfit-djxuv.github.io/MONO/

A private, offline personal-finance app: accounts, budgets, goals, shopping lists, subscriptions and
"safe to spend". Android version of the iPhone app MONO. It has **no internet permission**, so it cannot send your data anywhere.

## Download

Get the latest signed APK from the [Releases](../../releases) page, then:

1. Open the downloaded file on your phone and allow "Install unknown apps" for your browser or file manager when asked.
2. Optional check: the release lists a SHA-256. On a computer, `shasum -a 256 MONO-<version>.apk` should print the same value.
3. Updates: install the newer APK over the old one. Your data stays, as long as it is signed with the same key (every release is).

Needs Android 8.0 or newer. Back up from **Tools → Backup & recovery** before updating or uninstalling; uninstalling deletes the app's data.

## What it does

Dashboard with charts and Safe to spend · Transactions with search, filters and details · Budget · Goals · Shopping ·
Subscriptions and bills · Accounts, transfers and exchange rates · Insights, Monthly review, Purchase simulator, What-if lab ·
CSV import · Receipts (read on the phone) · Encrypted backups that open on the iPhone app too · App lock · Reminders ·
Launcher shortcuts · Theme editor.

## Build

Needs JDK 17 and the Android SDK (platform 35).

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # path to your Android SDK
./gradlew :domain:test            # money logic tests (JVM, seconds)
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest   # on-device UI tests (emulator or phone)
```

### Signed release

Create a keystore once and keep it **outside** this folder, with a backup (lose it and nobody can update the app):

```sh
keytool -genkeypair -keystore ~/safe-place/mono-release.jks -alias mono -keyalg RSA -keysize 4096 -validity 10950
cat > keystore.properties <<'P'     # never committed (see .gitignore)
storeFile=/absolute/path/mono-release.jks
storePassword=...
keyAlias=mono
keyPassword=...
P
tools/release.sh                  # tests, builds, signs, verifies: dist/MONO-<version>.apk + .sha256
```

## Layout

- `domain/` pure Kotlin: money, currency, month maths, the finance engines, CSV and receipt parsing, palettes.
- `app/` the Android app: Room database, DataStore settings, Jetpack Compose UI, backup, reminders, lock.
- `docs/ARCHITECTURE.md` how it fits together.

## License

MIT, see [LICENSE](LICENSE).
