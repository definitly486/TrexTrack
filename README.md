# T-Rex Track Exporter (starter)

Android/Kotlin starter project for a standalone GPX exporter targeting Amazfit T-Rex 2 users.

## Status

Прямая авторизация в аккаунт Zepp (без официального приложения) и хранение сессии через Android Keystore (`EncryptedSharedPreferences`) реализованы.

Текущий UI:

- вход по email/телефону и паролю;
- сохранение `app_token` / `login_token` / `user_id` в Keystore-backed хранилище;
- список тренировок из облака Zepp и экспорт GPS-треков в GPX;
- локальный открытие/сохранение GPX;
- **новая страница «Работа напрямую с часами»**: BLE-сканирование, подключение GATT к Amazfit T-Rex 2, обнаружение сервисов, ввод Huami auth key, каркас загрузки треков с часов.

## Прямая работа с часами

Кнопка на главном экране открывает `WatchDirectActivity`:

1. **Сканировать BLE** — поиск устройств (имена с T-Rex / Amazfit / Zepp / Huami).
2. Выбор устройства из списка.
3. **Подключить** — GATT connect + service discovery (отображаются UUID и свойства характеристик).
4. **Ввести Huami auth key** — опционально; для T-Rex 2 обычно нужен (как в Gadgetbridge; ключ можно получить через `huami-token` после привязки в Zepp).
5. **Загрузить треки с часов** — каркас: проверяет соединение, наличие Huami-сервисов (`fee0`/`fee1`) и auth key; полный протокол выгрузки activity + GPS points — следующий этап (референс: Gadgetbridge Zepp OS).

Загрузка треков **из облака** уже реализована на главном экране; эта страница именно для прямого BLE.

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

## CI (GitHub Actions)

После каждого push workflow **Debug APK** (`.github/workflows/debug-build.yml`):

1. Собирает `assembleDebug` на Ubuntu (JDK 17).
2. Загружает APK как artifact в Actions.
3. Публикует pre-release с тегом `debug-<branch>-<run_number>` и прикреплённым APK.

Артефакты и релизы: вкладка **Actions** / **Releases** репозитория.

## Planned implementation

1. ~~Validate direct Zepp account login without the Zepp app.~~
2. ~~Store refresh/session tokens in Android Keystore-backed storage; never log credentials.~~
3. ~~Implement paginated workout history.~~
4. ~~Download GPS route points and map them to GPX 1.1 (`trk`, `trkseg`, `trkpt`, optional `ele` and `time`).~~
5. Add date filters, bulk export, duplicate detection and error/retry handling (частично есть).
6. Test with real T-Rex 2 workouts and compare coordinate count/time/elevation against source data.
7. **Прямая синхронизация с часов**: порт логики activity/GPS sync с Huami-протокола (Gadgetbridge) поверх уже реализованного GATT + auth key.

## No Zepp dependency

There is no Zepp SDK, Zepp app package reference, Google Play Services dependency, or backend service in this project. Auth uses the same public HTTPS endpoints as community tools (encrypted credential exchange + token exchange). Direct watch path uses only Android BLE APIs.
