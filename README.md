# MONO for Android

A private, offline personal-finance app: accounts, budgets, goals, shopping lists, subscriptions and
"safe to spend". Android port of the iOS app MONO. It has no internet permission, so it cannot send your data anywhere.

**Status:** early development (stage 1 of 5). See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the plan.

## Build

Needs JDK 17 and the Android SDK (platform 35).

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # path to your Android SDK
./gradlew :domain:test          # money logic tests (JVM, seconds)
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

## Layout

- `domain/` pure Kotlin: money, currency, month maths, palettes, and (soon) the finance engines.
- `app/` the Android app: Room database, DataStore settings, Jetpack Compose UI.
- `docs/` design notes.
