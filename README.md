# Plaud Bridge (Android)

A companion app for **Plaud Note Pro** and **Plaud NotePin S** owners who self-host their own
sync server. Plaud Bridge pairs with your recorder over Bluetooth (with optional WiFi fast
transfer), pulls recordings off the device as MP3s, and uploads them to **your**
[`plaud-bridge-server`](https://github.com/adamrb/plaud-bridge-server) — not Plaud's cloud.

> **Your audio never touches Plaud's cloud.** The only thing that goes through Plaud's
> platform is the authentication handshake the device firmware requires (see "How it works").
> Transcription, storage, and everything downstream happen on your own server.

Plaud Bridge is an independent community project. It is **not affiliated with, endorsed by,
or supported by Plaud Inc.** It is adapted from Plaud's official Apache-2.0 Android template
app ([Plaud-AI/plaud-sdk-public](https://github.com/Plaud-AI/plaud-sdk-public)) and bundles
Plaud's proprietary device SDK (`app/libs/plaud-sdk.aar`) — see [NOTICE](NOTICE).

## How it works

```
                  ┌──────────────────────┐
   BLE / WiFi     │   Plaud Bridge app   │   HTTPS (your server)
 ┌───────────┐    │                      │    ┌─────────────────────┐
 │  Plaud    │◄──►│  pair / record /     │◄──►│ plaud-bridge-server │
 │  device   │    │  sync (MP3 export)   │    │  (self-hosted)      │
 └───────────┘    │                      │    │  - stores audio     │
                  └──────────┬───────────┘    │  - issues Plaud     │
                             │                │    user tokens      │
              auth handshake │ ONLY           │  - transcribes      │
              (JWT + device  │                └──────────┬──────────┘
               key exchange) ▼                           │
                  ┌──────────────────────┐               │
                  │  Plaud cloud         │◄──────────────┘
                  │  (platform-us/jp)    │   token issuance only
                  └──────────────────────┘   (no audio, ever)
```

1. **Token, at runtime, from your server.** Plaud devices use end-to-end encryption keyed to a
   Plaud "user access token" (a JWT). Instead of baking a token into the build (as the official
   template does), Plaud Bridge asks your server for one at runtime
   (`POST /api/v1/plaud/user-token` with a stable per-install user id), caches it, and refreshes
   it when it nears expiry. Your server holds the Plaud partner credentials; the app never sees
   them.
2. **Sync.** When the device connects (or when you tap *Sync now*), new recordings are exported
   over BLE — or via the ~10x faster WiFi fast-transfer mode — decrypted, and transcoded to MP3
   on the phone.
3. **Upload.** Each synced recording is uploaded to
   `POST {server}/api/v1/recordings` (multipart) with metadata (`session_id`, `device_sn`,
   `started_at`, `duration_s`). The server deduplicates, so re-uploads are harmless.
4. **Transcripts.** If your server transcribes the audio, the file detail screen fetches and
   displays the transcript (`GET /api/v1/recordings/{id}/transcript`); until then it shows
   "transcription pending".
5. **Optional cleanup.** A settings toggle (default **off**) deletes a recording from the device
   only after your server confirms the upload.

## Setup

### 1. Run a server

Deploy [`plaud-bridge-server`](https://github.com/adamrb/plaud-bridge-server) somewhere the
phone can reach. You'll need its base URL and an API auth token.

**HTTPS is required.** The app only accepts `https://` server URLs: Android blocks cleartext
(plain-HTTP) traffic by default, so an `http://` URL would just fail with an opaque network
error. If you really want plain HTTP on a trusted LAN, you must add a custom
[`networkSecurityConfig`](https://developer.android.com/privacy-and-security/security-config)
to the app (an XML resource allowing cleartext for your specific host, referenced via
`android:networkSecurityConfig` in the manifest) and relax the URL validation — this is
deliberately not shipped.

### 2. Build the app

There are no prebuilt releases; build it yourself with Android Studio (JDK 17):

```bash
git clone https://github.com/adamrb/plaud-bridge-android
cd plaud-bridge-android
# local.properties only needs your Android SDK path — no secrets:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
```

Or open the project in Android Studio and press Run. **No build-time credentials are
required** — everything is configured in-app.

The bundled SDK ships `arm64-v8a` and `armeabi-v7a` native libraries, so run on a physical
device (BLE also doesn't work in the emulator).

### 3. Sideload and onboard

Install the APK (`app/build/outputs/apk/debug/`), then:

1. **Connect your server** — enter the server URL and auth token; *Test connection* checks
   `GET /api/v1/health` and then verifies the token by fetching your first Plaud access token.
2. **Pair your device** — press the record button on your Plaud device to wake it, scan,
   and connect. (See the FAQ about unbinding from the official app first.)
3. Recordings sync automatically on connect (toggle in Settings), or on demand via *Sync now*.

### Settings

| Setting | Notes |
|---|---|
| Automatic sync | Pull new recordings whenever the device connects / stops recording |
| Delete after upload | Remove from device only after the server confirms (default off) |
| Bridge server | Edit URL/token; changes are verified against the server before saving. Switching to a different server host resets local upload/transcript state — recordings re-upload to the new server (it deduplicates) |
| Plaud cloud region | `platform-us.plaud.ai` (default) or `platform-jp.plaud.ai`; restart to apply |
| User ID | The auto-generated per-install id (`pb_<uuid>`) your server sees |

## FAQ

**Why do I have to unbind the device from the official Plaud app first?**
Plaud devices are cryptographically locked to one account at a time. If your recorder is still
bound to your Plaud-app account, the handshake from Plaud Bridge is rejected. Unbind it in the
official app first (Plaud App → Device → Unbind). If you no longer have access to the old
account, Plaud Bridge offers a device-recovery flow that unlocks the device using its cloud
bind history — the device just must not be actively bound to another account.

**Does any of my audio go to Plaud?**
No. Audio moves device → phone → your server. Plaud's cloud is contacted only for the
authentication handshake (token/key exchange the firmware requires) and, optionally, firmware
update checks/downloads. The official cloud-transcription pipeline from the template app has
been removed entirely.

**What's the `pb_...` user id?**
A random per-install identity (generated once, stored locally) that your server uses to request
Plaud user tokens. The device firmware binds to it, so keep the same install (or restore the id)
if you want to reconnect without re-pairing/recovery.

**Which devices work?**
Plaud Note Pro (SN prefix 881) and NotePin S (882) — the devices the underlying SDK supports.

**Firmware updates?**
Supported — Settings shows an *Update* button when the device reports an older version than
Plaud's latest. The firmware image comes from Plaud's platform.

**Recording from the app?**
Yes — start/pause/stop the device's recorder remotely, with a live level meter.

**What does deleting a recording in the app do?**
The *Remove Downloaded Copy* action on a recording removes only the MP3 the phone downloaded
(and the local list entry). It does **not** delete the copy on the recorder, nor anything
already uploaded to your server. Device-side deletion happens only via the *Delete after
upload* setting, and only after the server confirms the upload — and only ever on the exact
device (matched by serial number) the recording came from.

## Development notes

- Architecture follows the upstream template: singleton managers (`DeviceManager`,
  `SyncManager`, `RecordingManager`, `UploadManager`) + ViewBinding fragments/activities.
  Mock managers (`PlaudBridgeApp.USE_MOCK`) allow UI work without hardware.
- New vs. the template: `net/ApiClient` (bridge-server HTTP), `net/TokenManager` (runtime token
  fetch/refresh), `managers/UploadManager` (upload queue + delete-after-upload),
  `ui/onboarding/ServerSetupActivity`. Removed: `TranscriptionManager` (Plaud cloud
  transcription), all `BuildConfig` credential injection.
- minSdk 21, compileSdk 34, Kotlin + coroutines, OkHttp for the server API.

### Testing

JVM unit tests (JUnit4 + Robolectric + OkHttp MockWebServer) live in `app/src/test`:

```bash
./gradlew test          # or: ./gradlew testDebugUnitTest
```

Covered: the strict upload-response contract in `ApiClient` (201/duplicate pairing, HTML-200
rejection, redirects not followed), token expiry handling in `TokenManager`, composite
`(device_sn, session_id)` identity and index corruption recovery in `RecordingStore`, and the
`UploadManager` queue (lost-wakeup dirty flag, blank-SN / wrong-device delete safety, deferred
device deletes). The BLE SDK is faked behind the thin `UploadManager.DeviceLink` seam — nothing
in the test suite talks to real hardware.

### Known limitations

- **Threading (SDK callbacks).** SDK callbacks and manager state transitions are not confined
  to a single dispatcher/actor; state is guarded piecemeal (locks, `@Volatile`, main-thread
  hops). A rapid start/stop or a callback racing a queue clear can, in principle, interleave.
  The right fix is routing all SDK events and commands through one serialized actor — a larger
  refactor deliberately not attempted here. Attribution guards (device serial captured per
  request, stale callbacks dropped) close the dangerous cross-device cases in the meantime.
- **Release signing.** The `release` build type signs with the debug keystore for sideloading
  convenience. For any store-facing or shared build, configure a real protected keystore via
  `signingConfigs` (and consider Gradle dependency verification). The Gradle wrapper pins
  `distributionSha256Sum` for the 8.2 distribution.
- **Backups.** `android:allowBackup` is `false`: the app stores your server bearer token and
  a Plaud JWT, which must not leak through cloud/device-transfer backups. Re-onboard (server
  URL + token) after moving to a new phone; recordings re-download/re-upload safely.

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
The bundled `app/libs/plaud-sdk.aar` is proprietary to Plaud Inc. and licensed separately.

*Plaud, Plaud Note, and NotePin are trademarks of Plaud Inc., used here only to describe
compatibility. This project is unaffiliated with Plaud Inc. — use at your own risk.*
