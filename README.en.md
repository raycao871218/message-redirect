# GuGuGu (咕咕咕) · Message Redirect

[![切换到简体中文](https://img.shields.io/badge/简体中文-切换语言-0969da?style=for-the-badge)](README.md)
[![English · Current language](https://img.shields.io/badge/English-Current_language-2ea44f?style=for-the-badge)](README.en.md)

When you have several phones, taking all of them with you is not always practical—and verification codes often arrive on the one you left at home. GuGuGu is a little messenger for that stay-at-home Android phone. It forwards SMS messages and verification codes to an email inbox you can check on the go, or stores them in your own archive service.

The project is designed primarily for deployment on your home network. The self-hosted backend does not need to be exposed to the internet. While away, you can read forwarded messages by email or connect to your home VPN to access the archive.

GuGuGu is a native Android SMS forwarding app with an optional Docker-based, self-hosted service. You can send messages directly from the phone via SMTP, or archive them through your own service using SQLite, PostgreSQL, MySQL, or MariaDB. The project is released under the [MIT License](LICENSE).

## Project structure

```text
apps/android/  Native Android app with an independent Gradle build
server/        Archive API, database adapters, Dockerfile, and server tests
deploy/        Docker Compose templates and example environment variables
docs/          Deployment, branding, and other documentation
scripts/       Repository-level testing, deployment, and local maintenance tools
```

The app and server communicate over an HTTP API and can be developed, built, and released independently. Both currently live in one Git repository.

## Features

- A setup wizard guides first-time configuration. Saved configurations appear as cards you can tap to switch between destinations. Detailed forms only appear when adding or editing a configuration. You can save multiple email or archive destinations.
- Email is sent directly from the phone, with no backend required. Built-in setup guidance covers Gmail, QQ Mail, NetEase Mail, and custom SMTP servers. Personal Microsoft email accounts require OAuth2; this version provides connection details but does not yet implement Microsoft sign-in.
- The receiving phone number can be read from the SIM with permission or entered manually, with dual-SIM support. Emails include both the receiving number and the original sender. The receiving number is stored with each message; later changes do not rewrite historical records. Unknown numbers are shown as “Not recorded.”
- The home screen shows status without displaying a message list or message bodies. Messages are first stored in a queue on the phone and marked complete only after the archive service confirms a successful write. If an email submission has an uncertain outcome, automatic retries are paused to avoid blindly sending duplicates.
- The service supports persistent storage, health checks, access-token authentication, and transactional writes. Retrying the same `(device_id, message_id)` does not create duplicate records. Conflicting content under the same ID returns HTTP 409 and rolls back the entire batch.
- A standalone admin panel lets you test and save database connections, reset the app access token, and generate a pairing QR code. Scanning a code in the app opens a preview; it takes effect only after you confirm the imported details and save the configuration.
- `/docs` serves a local Swagger UI, and `/openapi.json` provides the API specification for building your own clients. Neither NASBridge nor an external documentation CDN is required.
- A cozy, crayon-inspired interface features a charmingly wonky spotted dove with grey-brown feathers and a black neck patch dotted with white spots. Choose a self-hosted archive and the dove puts on a cyber helmet and armored jacket; switch back to email and its usual look returns. The wizard and editor preview the change immediately. Launcher icons support Android adaptive and monochrome themed icons. See the [branding and icon notes](docs/branding.md).

## Docker quick start

You need Docker Engine or Docker Desktop and Docker Compose v2. From the repository root, enter `deploy/` and run the following commands. The default template uses SQLite and stores data in a Docker named volume.

```sh
cd deploy
cp .env.example .env
python3 ../scripts/init-secrets.py
cp compose.example.yaml compose.yaml
docker compose up -d --build
docker compose ps
```

`init-secrets.py` generates random tokens and database passwords for a new deployment without overwriting existing files. Deployment credentials are stored in the ignored `deploy/.secrets/` directory. Newly created directories have permissions of 0700, and the files can be mounted read-only by the containers. Keep the parent directory private.

Open the service URL in a browser to access the admin panel, “服务小窝” (Service Nest). Sign in with the admin password in your local `deploy/.secrets/admin-password` file. This password is separate from the app access token. Enter a service URL reachable from your phone and generate a QR code. In the app, add a “自建数据库” (Self-hosted database) configuration, tap “扫码配置” (Scan configuration), confirm the details, and save. The camera runs only while the scanning page is open and closes when you leave. You can also enter the details manually without granting camera permission.

The database configuration and any reset access token are stored in `/data/settings.json`, so keep the data volume. On first startup, the service imports connection settings and the token from environment variables or legacy JSON. After that, settings saved through the panel take precedence; recreating the container does not restore an old token. Changing the connection affects subsequent writes and does not automatically migrate existing messages.

By default, Compose binds only to `127.0.0.1:18081`. To connect from your phone, change `SMS_BIND_ADDRESS` in your local `.env` to a reachable address on the server, or configure an HTTPS reverse proxy. Use `SMS_HTTP_PORT` to change the host port.

To create a self-hosted database configuration card in the app, provide:

- **Service URL:** `https://your-service-domain`, or the service address on a trusted local network.
- **Access token:** The contents of your local `.secrets/api-token` file. Real tokens are never bundled in source code, images, or examples.

The app connects to the companion archive service; database credentials stay on the server. The admin panel handles configuration. Developers can use the Bearer-authenticated API to page through messages, retrieve message details, and manage shared read status. See the [API documentation](docs/api.md) for the complete protocol. Queries do not automatically mark messages as read or change the phone's inbox state. PostgreSQL users can also deploy [NASBridge](https://github.com/raycao871218/NASBridge) separately; it is not a dependency of GuGuGu's archive service.

## Choosing a database

All templates are in `deploy/`. For a new deployment, copy one template to your local `compose.yaml`, then run the startup commands above.

| Template | Deployment | Data location |
| --- | --- | --- |
| `compose.example.yaml` | Service + SQLite | `sms-data` named volume |
| `compose.postgres.example.yaml` | Service + PostgreSQL 17 | `postgres-data` named volume |
| `compose.mysql.example.yaml` | Service + MySQL 8.4 | `mysql-data` named volume |
| `compose.mariadb.example.yaml` | Service + MariaDB 11.4 | `mariadb-data` named volume |
| `compose.external.example.yaml` | Service only, connected to an existing database | Your own database |

For containerized databases, set the database name and application user with `SMS_DB_NAME` and `SMS_DB_USER` in `.env`. Application and database administrator passwords use separate local secret files. Database ports are not published to the host. Removing or recreating the service container does not delete named volumes; `docker compose down -v` deletes data volumes, so back them up first.

See the [deployment guide](docs/deployment.md) for all parameters, external databases, upgrades, and backups. For custom clients, see the [API and QR code format](docs/api.md).

**For an existing deployment, keep your own `compose.yaml`, networks, volumes, and database configuration. Back up your data, then upgrade the service image. Do not overwrite a running deployment with a new template.** Legacy `.secrets/server.json` files without `database.type` are still treated as PostgreSQL configurations, preserving the `message_redirect.sms_messages` table and existing idempotency hashes.

## Android usage and building

Requires Android 6.0 or later. During setup, choose “邮件转发” (Email forwarding) or “自建数据库” (Self-hosted database), set the receiving phone number, and grant permission to receive SMS. Later, use “管理转发配置” (Manage forwarding configurations) to choose a configuration card. The main switch on the home screen controls forwarding; switching cards does not enable forwarding when it is paused. The app receives new messages only and does not automatically import your existing inbox.

Use an app password for Gmail or a client authorization code for QQ Mail and NetEase Mail. Email credentials are encrypted with Android Keystore and stored in the phone's private app settings. Leave the credential field blank when editing to keep the saved value. Changing the server or account requires entering the credential again.

If sending is interrupted, the app may be unable to determine whether an email was sent. It will not automatically retry that email, to avoid duplicates. Open “更多操作 → 检查邮件是否收到” (More actions → Check email delivery), check your inbox and spam folder, then choose “已收到” (Received) or “没收到，重发” (Not received, resend). Resending may produce a duplicate email. The home screen shows a manual-check count only when your attention is needed; emails currently being sent are shown separately.

“更多操作 → 关于咕咕咕” (More actions → About GuGuGu) shows the installed version, build number, and dove mascot. “更多操作 → 使用说明” (More actions → Usage guide) explains automatic forwarding, failed-message retries, email delivery checks, configuration switching, and background operation. New messages remain queued on the phone while forwarding is paused and resume sending when it is enabled again. Messages that have already started sending retain their original email recipient or service URL and continue when you switch back to the corresponding configuration. System background and battery restrictions may delay processing. After force-stopping the app, open it again to resume operation.

Building requires JDK 17, Android SDK Platform 34, and Build Tools 34.0.0. Set the SDK path in the untracked `apps/android/local.properties` file:

```properties
sdk.dir=/path/to/Android/sdk
```

```sh
./apps/android/gradlew -p apps/android :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
```

Updates must use the same signing key as the installed app. A personal debug key can be stored in the ignored `.secrets/android-debug.keystore` file at the repository root. Do not uninstall just to resolve a signature mismatch: uninstalling deletes the local queue and encrypted configuration.

By default, the app allows HTTPS and local test addresses. If you need HTTP on your local network, copy `apps/android/app/src/main/res/xml/network_security_config.xml` to the ignored `apps/android/app/src/debug/res/xml/network_security_config.xml` and add your own address only in that local override. Use HTTPS over untrusted networks.

The app does not handle MMS, RCS, or other messaging apps, send SMS, or request to become the default SMS app. For public distribution, you must arrange signing and meet the distribution platform's SMS permission requirements.

## Development and testing

Run these commands from the repository root. On Windows, use `apps/android/gradlew.bat -p apps/android` for Gradle commands.

```sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r server/requirements.txt httpx
python -m unittest discover -s server/tests -p 'test_*.py' -v
```

These tests use mocks or temporary SQLite databases. They cover authentication, input validation, transaction commits and rollbacks, concurrent duplicate submissions, failures, and configuration compatibility without connecting to production databases. Container integration tests use isolated containers, networks, and random test credentials:

```sh
python3 scripts/test-containers.py
# Or test a single engine
python3 scripts/test-containers.py --engines mysql
```

The script checks actual writes across all four database engines, Unicode, duplicate retries, conflict rollbacks, and data persistence after recreating the service container. It cleans up the test resources it creates. GitHub Actions runs these checks as well.

Android tests use synthetic SMS messages and a local SMTP fixture without sending real email. Build with `-PuiPreview` for UI regression testing in a separate `.preview` package. Regular builds do not include test configurations.

```sh
./apps/android/gradlew -p apps/android -PuiPreview :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
adb install -r apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.nas.messageredirect.preview.test/com.nas.messageredirect.SmsInstrumentation
adb shell am instrument -w -e ui_flow true com.nas.messageredirect.preview.test/com.nas.messageredirect.SmsInstrumentation
```

## Open source and privacy

This repository contains only source code, public templates, and examples. Actual `.env` files, `.secrets/`, `compose.yaml`, SMS databases, logs, runtime screenshots, APKs, and signing keys are ignored. Check commit history before publishing code: ignore rules do not remove secrets that have already been committed.

Application code is licensed under [MIT](LICENSE). See [THIRD_PARTY.md](THIRD_PARTY.md) for dependency licenses and notices.
