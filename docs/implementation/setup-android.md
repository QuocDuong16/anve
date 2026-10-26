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
  entry) for a deployed backend. For a physical device on the same machine,
  prefer `adb reverse tcp:8080 tcp:8080` and
  `-PANVE_API_URL=http://127.0.0.1:8080` over exposing the dev server on the
  LAN (see the development sign-in section).
- Release builds have **no default URL** that works: pass a real deployment
  with `-PANVE_API_URL=...` when assembling release artifacts.

## Development sign-in (no Google project yet)

The fixture only works when the server runs with
`ENVIRONMENT=development` and `AUTH_DEV_LOGIN_ENABLED=true`, and the server
additionally enforces a source-address allowlist. Two supported setups keep
that allowlist local.

### Option 1 — Go binary on the host (loopback only)

```bash
cd apps/server
DATABASE_URL='postgres://anve:anve@localhost:5432/anve?sslmode=disable' \
AUTH_DEV_LOGIN_ENABLED=true \
go run ./cmd/api
```

With no `LISTEN_ADDR`, the binary binds `127.0.0.1:8080` and allows only
loopback sources.

- **Emulator:** the debug BuildConfig default `http://10.0.2.2:8080`
  resolves to the host loopback, so no extra configuration is needed.
- **Physical device over USB:** run `adb reverse tcp:8080 tcp:8080`, then
  build with `-PANVE_API_URL=http://127.0.0.1:8080` (the debug network
  security config permits cleartext for loopback). The request arrives at
  the host loopback through ADB.

### Option 2 — Docker Compose

The API container listens on `0.0.0.0:8080` internally (it must accept the
port mapping), but the host port is published on `127.0.0.1` only. To mount
the fixture in the container, add these to `.env` and restart the stack:

```bash
AUTH_DEV_LOGIN_ENABLED=true
AUTH_DEV_LOGIN_ALLOW_NETWORK=true
AUTH_DEV_LOGIN_ALLOWED_CIDRS=127.0.0.0/8,::1/128,172.28.0.0/24
```

The last CIDR is the pinned Compose `edge` bridge (`172.28.0.0/24`); Docker
rewrites the source address of a published connection to that network. If
the subnet collides with an existing Docker network on your machine, change
the `edge` `ipam` pin and this allowlist together. The emulator still uses
`10.0.2.2:8080`; nothing outside the host can reach the port.

### Security boundary

The development login is a privileged test fixture, not an authentication
provider (ADR 0002, ADR 0005):

- The server refuses to start with the fixture enabled unless
  `ENVIRONMENT` is exactly `development`; staging, production, mis-cased and
  unknown names are rejected.
- It also refuses a non-loopback `LISTEN_ADDR` unless
  `AUTH_DEV_LOGIN_ALLOW_NETWORK=true` **and** a non-catch-all
  `AUTH_DEV_LOGIN_ALLOWED_CIDRS` are set. The route checks the request
  source itself; `X-Forwarded-For` and `X-Real-IP` are ignored.
- The route is not mounted at all when the flag is false, and release
  builds never render the form (UI hygiene — the server check is the
  boundary).
- Development identities are isolated from Google identities: neither side
  can sign into an account created by the other, so an arbitrary dev-login
  email can never take over a real Google account.
- Never enable the fixture on a host reachable from an untrusted network,
  and never point it at a production database. For genuinely remote
  development, tunnel to the loopback listener (`ssh -L` or `adb reverse`)
  instead of widening the allowlist.

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
