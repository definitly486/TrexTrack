# T-Rex Track Exporter (starter)

Android/Kotlin starter project for a standalone GPX exporter targeting Amazfit T-Rex 2 users.

## Status

Прямая авторизация в аккаунт Zepp (без официального приложения) и хранение сессии через Android Keystore (`EncryptedSharedPreferences`) реализованы.

Текущий UI:

- вход по email/телефону и паролю;
- сохранение `app_token` / `login_token` / `user_id` в Keystore-backed хранилище;
- локальный открытие/сохранение GPX.

Список тренировок и скачивание GPS-треков — следующий этап.

## Build

Requirements: JDK 17, Android SDK platform 35, Android Gradle Plugin 8.7.3.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If the project is built on FreeBSD and Gradle/AGP rejects the host OS:

```sh
JAVA_TOOL_OPTIONS=-Dos.name=Linux ./gradlew assembleDebug
```

Release signing is optional: set `TREXTRACK_STORE_FILE`, `TREXTRACK_STORE_PASSWORD`, `TREXTRACK_KEY_ALIAS`, `TREXTRACK_KEY_PASSWORD` only when you need a signed release APK.

## Planned implementation

1. ~~Validate direct Zepp account login without the Zepp app.~~
2. ~~Store refresh/session tokens in Android Keystore-backed storage; never log credentials.~~
3. Implement paginated workout history.
4. Download GPS route points and map them to GPX 1.1 (`trk`, `trkseg`, `trkpt`, optional `ele` and `time`).
5. Add date filters, bulk export, duplicate detection and error/retry handling.
6. Test with real T-Rex 2 workouts and compare coordinate count/time/elevation against source data.

## No Zepp dependency

There is no Zepp SDK, Zepp app package reference, Google Play Services dependency, or backend service in this project. Auth uses the same public HTTPS endpoints as community tools (encrypted credential exchange + token exchange).
