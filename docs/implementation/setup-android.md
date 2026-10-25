# Android development setup — AnVề

How to run and connect the Android app to a backend. The project is
developed from the CLI (no Android Studio required); `ANDROID_HOME` must
point at an SDK that has the compile SDK pinned in
`apps/android/gradle/libs.versions.toml`.

## Backend URL

The API base URL is a BuildConfig field:

- Debug builds default to `http://10.0.2.2:8080` — the emulator's alias for
  the host machine, matching `make docker-up` on the same machine. The
  debug network security config allows cleartext **only** for `10.0.2.2`,
  `localhost` and `127.0.0.1`.
- Override with `-PANVE_API_URL=https://your-host` (or a `gradle.properties`
  entry) — needed for real devices on the same LAN (use your machine's LAN
  IP and an HTTPS or deliberately-permitted setup) or deployed backends.
- Release builds have **no default URL** that works: pass a real deployment
  with `-PANVE_API_URL=...` when assembling release artifacts.

## Development sign-in (no Google project yet)

1. Start the stack with the development login mounted:

   ```bash
   make docker-up
   AUTH_DEV_LOGIN_ENABLED=true DATABASE_URL=... PORT=8080 go run ./cmd/api  # from apps/server
   ```

   The Compose `api` service does not mount the dev login; run the binary
   locally as above (or add the env var to `infra/docker/docker-compose.yml`
   temporarily) and point the app at it.

2. In the app (debug builds only), the sign-in screen shows a
   "Đăng nhập phát triển" section when the server advertises `devLogin`.
   Enter any email — the backend creates the account and returns a real
   session; the flow is identical to production past the identity check.

The development login is refused at startup when `ENVIRONMENT=production`
(ADR 0002) and release builds never render the section.

## Google Sign-In (production path)

Prerequisites: a Google Cloud project with the **Google Play services**
available to your users (any normal Android device).

1. **OAuth Client ID (Web)**: In Google Cloud Console → APIs & Services →
   Credentials, create an *OAuth client ID* of type **Web application**.
   This is the *server* client ID — the Android app requests ID tokens for
   this audience and the Go backend verifies them against the same value.

2. **Server config**: set on the API deployment:

   ```
   AUTH_GOOGLE_CLIENT_ID=<the client ID from step 1>
   ```

   When unset, the server advertises `googleSignIn: false` and
   `POST /api/v1/auth/google` answers `503 AUTH_NOT_CONFIGURED` (fail
   closed, no fake verification).

3. **App config**: copy the client ID into
   `apps/android/app/src/main/res/values/config_local.xml` (gitignored):

   ```xml
   <resources>
       <string name="anve_google_server_client_id" translatable="false">
           1234567890-abc.apps.googleusercontent.com
       </string>
   </resources>
   ```

   The placeholder in `values/config.xml` keeps the build green without
   credentials; `GoogleCredentialSource.isConfigured` gates the button.

4. The app uses Credential Manager (`androidx.credentials`) with
   `GetGoogleIdOption`; no `google-services.json` is needed because the
   app talks to the AnVề backend, not Firebase, for sign-in.

## What is NOT configured by this repo

- No real OAuth client ID is committed (by design — see ADR 0002).
- `google-services.json` does not exist; it becomes relevant with FCM (M6),
  not with sign-in.

## Testing

- `make android-test` — JVM unit tests (DTO parsing, session
  refresh-retry against MockWebServer, ViewModel flows).
- `make android-build` — debug APK; install with `adb install -r
  apps/android/app/build/outputs/apk/debug/app-debug.apk`.
- Field validation on a real device is tracked as its own work item; a
  green CI build is not evidence of on-device behavior.
