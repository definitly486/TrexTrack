# T-Rex Track Exporter (starter)

Android/Kotlin starter project for a standalone GPX exporter targeting Amazfit T-Rex 2 users.

## Important status

This is a **starter source project**, not yet a complete Zepp-cloud client. The app does not use or launch the Zepp app, and does not pretend to authenticate or return fabricated workouts. The current UI supports selecting a local GPX and saving a copy. Cloud account access is represented by `ZeppCloudRepository.kt` and must be implemented after the account authentication and route API are verified.

## Why cloud access is not filled with guessed endpoints

- Zepp's official third-party platform requires partner onboarding, an approved app, and approved permissions.
- A community exporter exists for the reverse-engineered Huami/Zepp API, but its documented token setup extracts a token from the Zepp Android app. That route conflicts with the requirement not to use Zepp at all.
- Therefore the next technical task is to validate a direct account-authentication path that does not require the Zepp app, then implement the actual workout-list and GPS-track endpoints. Do not enter your account password into this scaffold.

## Build

Requirements: JDK 17, Android SDK platform 35, Android Gradle Plugin 8.7.3.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the project is built on FreeBSD and Gradle/AGP rejects the host OS, use the same local workaround that may be needed in your environment:

```sh
JAVA_TOOL_OPTIONS=-Dos.name=Linux ./gradlew assembleDebug
```

## Planned implementation

1. Validate direct Zepp account login without the Zepp app.
2. Store refresh/session tokens in Android Keystore-backed storage; never log credentials.
3. Implement paginated workout history.
4. Download GPS route points and map them to GPX 1.1 (`trk`, `trkseg`, `trkpt`, optional `ele` and `time`).
5. Add date filters, bulk export, duplicate detection and error/retry handling.
6. Test with real T-Rex 2 workouts and compare coordinate count/time/elevation against source data.

## No Zepp dependency

There is no Zepp SDK, Zepp app package reference, Google Play Services dependency, or backend service in this starter.
