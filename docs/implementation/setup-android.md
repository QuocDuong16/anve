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

## Google Maps for M4

Map rendering is optional at build time. Without `ANVE_MAPS_API_KEY`, the app
builds normally and the Live Map screen shows a setup message while sharing
management and the rest of the app remain usable.

1. In Google Cloud, enable **Maps SDK for Android** and create an API key.
2. Restrict it to Android apps. Add the release package `com.anve.app` and
   debug package `com.anve.app.debug`, each with the SHA-1 certificate for its
   signing key. Debug and release keys/certificates may differ. Restrict API
   access to Maps SDK for Android.
3. Supply it locally without committing it:

   ```bash
   export ANVE_MAPS_API_KEY='your-restricted-key'
   make android-build
   ```

   Gradle also accepts `-PANVE_MAPS_API_KEY=...`; avoid storing a real key in
   tracked `gradle.properties`. The key is embedded in the APK and is an
   identifiable, restricted client key rather than a server secret.
4. The Android emulator needs a system image with Google Play services and
   network access to render tiles. API/build tests do not prove map rendering.

See [ADR 0008](../adr/0008-m4-polling-confidence-and-map.md). A build with no
key is valid for CI; do not call the Google Maps surface runtime-verified until
tiles and markers have been observed on an emulator or device.

## Testing

- `make android-test` — JVM unit tests (DTO parsing, session
  refresh-retry against MockWebServer, ViewModel flows, Room queue rules,
  adaptive policy, sync engine, tracking coordinator).
- `make android-build` — debug APK; install with `adb install -r
  apps/android/app/build/outputs/apk/debug/app-debug.apk`.
- `make android-release` — minified release compile/APK (unsigned without a
  release signing config). The mission gate requires release compile even
  though distribution signing is M8 work.
- Field validation on a real device is tracked as its own work item; a
  green CI build is not evidence of on-device behavior.

## M3 location testing (manual, pending device validation)

The JVM suites prove the state machine, queue and sync rules with fake
providers/clocks. They do **not** prove OS behavior. Use this checklist on
a real device/emulator and record the result as *Device Verified* only when
observed.

1. **Permission flow:** install fresh → open Vị trí → read the concise
   disclosure → enable consent → grant foreground location. Deny once and
   verify the app remains usable and explains the limitation. Grant
   approximate-only and verify the UI says "Gần đúng" and tracking continues
   with reduced accuracy.
2. **Background location:** verify the foreground grant is requested first.
   On API 29, verify the separate background permission request appears only
   after that grant. On API 30+, verify the education screen explains why
   background location is needed and opens the supported app-permission
   Settings page with the system option label when available. Return without
   granting and confirm the app still reports background permission missing;
   return after granting and confirm the OS snapshot refreshes. Recomposition
   or resume must not relaunch Settings or repeat prompts automatically.
3. **Screen off / background:** start tracking, lock the screen for several
   minutes, confirm the persistent notification and that new points arrive
   (check queue count/last fix; server rows grow).
4. **Network loss:** enable airplane mode → collect a few points → confirm
   "Không có mạng — vị trí được lưu trên thiết bị" and queue growth →
   disable airplane mode → confirm the queue drains and server rows exist
   once, with original timestamps.
5. **Process kill:** start tracking, swipe the app away or `adb shell am
   force-stop com.anve.app.debug` → reopen → verify the screen says tracking
   was stopped and offers "Tiếp tục theo dõi" (not a false active state).
6. **Reboot:** reboot while tracking → open the app → same honest recovery
   state; no hidden BOOT_COMPLETED restart.
7. **GPS/location switch off:** turn off device location while tracking →
   verify the engine reports the limitation and stops the empty service;
   turn it back on and resume.
8. **Permission revocation:** revoke location permission in system settings
   while tracking → verify tracking stops, no crash, and the UI explains
   how to restore it.
9. **Battery Saver:** enable Battery Saver and verify the UI reports reduced
   frequency; measure drain with `dumpsys batterystats` before/after — do
   not publish a battery claim without measurements.
10. **Consent OFF offline:** airplane mode → turn off sharing → verify
    acquisition stops and the pending-sync message appears → restore
    network → verify the server sees consent OFF before any location upload
    (query `location_points`; the queue must have been purged).
11. **Emulator injection:** use Extended Controls → Location or
    `adb emu geo fix <lon> <lat>` and verify accepted coordinates, accuracy,
    motion transitions and queue/sync. `10.0.2.2:8080` reaches a host API
    bound to loopback.
12. **ADB reverse (physical device):** `adb reverse tcp:8080 tcp:8080`,
    build with `-PANVE_API_URL=http://127.0.0.1:8080`; never widen the
    development-login allowlist for a LAN address.

## M4 Live Map validation (manual, pending runtime/device validation)

Use two test accounts in one test Circle. Keep real user coordinates out of
logs, screenshots and shared test artifacts.

1. Sign in as owner A, enable server consent, then explicitly grant viewer B.
   Sign in as B and verify A appears only after the grant; without a grant the
   API must not return A's coordinates.
2. Capture a new point for A. Confirm B sees its recorded time, accuracy and
   freshness. Polling runs every 10 seconds while the map screen is foreground.
3. Revoke B or turn A's consent OFF. With active network, confirm the next
   authorized snapshot removes A's coordinates. Do not expect already viewed
   data or screenshots to be recalled.
4. Leave and rejoin the Circle. Verify a fresh grant is required and pre-grant
   points do not become visible after rejoin or consent ON.
5. Disconnect B's network. Verify the screen reports disconnected and a
   retained in-memory sample ages from Fresh to Delayed/Stale. Confirm it does
   not say A's device is Offline; no presence signal exists in M4.
6. Switch from B to another account on the same installation. Verify B's
   location markers clear and delayed grant mutations are rejected when the
   expected owner does not match the current session.
7. Test a missing/restricted Maps key. The app must not crash and the user
   must still be able to open sharing management.

JVM tests and APK builds do not verify Google tile rendering, device battery,
or platform lifecycle behavior. Record those separately when observed.


## Automated emulator checks

The Android workflow provisions a headless API 35 Google APIs x86_64 emulator
with KVM, disables animations and runs `make android-instrumentation` with a
bounded timeout. The History UI tests use fake test-only data and require no
Maps key, backend or real location; a separate Activity test exercises
background/resume. `make android-test-build` compiles the instrumentation APK
without starting an emulator. To run the tests locally, start a compatible
emulator or connect a device, verify `adb devices -l`, then run
`make android-instrumentation`. A compiled APK alone is not a test result.
CI emulator evidence does not establish real GPS accuracy, OEM behavior or
battery use; those require separate field validation.

## Firebase configuration under development

M6 is still under integration and has not been verified end to end. The
work-in-progress Android build initializes Firebase programmatically and
accepts `ANVE_FIREBASE_APP_ID`, `ANVE_FIREBASE_PROJECT_ID`,
`ANVE_FIREBASE_API_KEY`, and `ANVE_FIREBASE_SENDER_ID` as Gradle properties or
environment variables. All four must describe the same Firebase Android app.
The debug application ID is `com.anve.app.debug`; release uses `com.anve.app`.
Register the appropriate package and signing identities in the Firebase
project. Empty values allow credential-free builds but disable initialization.
Do not put service-account private keys or production credentials in the repo.
See [Firebase Android setup](https://firebase.google.com/docs/android/setup)
and [FCM server authorization](https://firebase.google.com/docs/cloud-messaging/auth-server)
for provider-side setup.
Set `FCM_PROJECT_ID` on the server and provide Google Application Default
Credentials with Firebase Messaging permission. Without it, notification work
remains queued and the SOS screen reports that push is not configured. Actual
delivery remains pending external integration validation.
For a local server process, ADC may be supplied through
`GOOGLE_APPLICATION_CREDENTIALS` pointing to a private service-account file
stored outside the repository. A container must receive that file through a
read-only secret mount and the corresponding in-container path; setting the
project ID alone does not provide credentials. Prefer a workload identity in
an environment that provides one. Do not place private keys in Android builds
or in GitHub Actions logs.
Firebase project configuration is not evidence that an FCM message was sent,
delivered, displayed, or acknowledged.
