# AGENTS.md

Guide for coding agents (and humans) working on **Transom for Android**. Read this before
changing anything. User-facing documentation is [README.md](README.md); the server this app talks
to is [transom-server](https://github.com/adamrb/transom-server), whose
`docs/end-to-end.md` describes the whole pipeline.

## What this is

A Kotlin Android app (`cloud.adamrb.transom`, minSdk 21, targetSdk 34) that pairs with a Plaud
recorder through Plaud's proprietary Embedded SDK (`app/libs/plaud-sdk.aar`), pulls recordings
off the device, uploads them to the user's own transom-server, and shows transcripts,
summaries and automation results. No audio ever goes to Plaud's cloud; the SDK only needs a
signed user token, which the app fetches from the user's server at runtime. There are no
build-time secrets.

## Layout (`app/src/main/java/cloud/adamrb/transom/`)

```
TransomApp.kt   Application: manager wiring, USE_MOCK switch for UI work without hardware
managers/           Singletons around the SDK: DeviceManager (BLE/WiFi, pairing), SyncManager
                    (file list + download), UploadManager (queue, delete-after-upload),
                    MarksSyncManager (button-press bookmarks), TitleSyncManager (transcript/title
                    polling), AutomationWatcher (automation outcomes). managers/mock/ = fakes.
net/                ApiClient (every server call), TokenManager (Plaud JWT fetch/refresh),
                    UpdateManager (self-update: manifest, sha256, APK identity), VocabularyImport
storage/            RecordingStore: the on-phone sync index keyed by (device_sn, session_id)
service/            DeviceConnectionService (foreground BLE keepalive), BootReceiver
work/               WorkManager jobs that survive process death (uploads, title sync, watches)
ui/                 ViewBinding activities/fragments: onboarding (QR + manual server setup),
                    main (3 tabs: Home / Recordings / Settings), recordings (merged list),
                    filedetail (player, transcript, summary, automations), settings, update
export/             Markdown export built on-device to match the server's layout
common/             Notifications, JWT parsing, QR payload validation (WebViewOriginPolicy and
                    the dashboard WebView live in ui/library/)
```

Tests live in `app/src/test` (JUnit4 + Robolectric + OkHttp MockWebServer). Nothing in the
suite touches hardware: the SDK is faked behind seams such as `UploadManager.DeviceLink`.

## Setup and everyday commands

Needs JDK 17 and an Android SDK. `local.properties` holds only `sdk.dir`.

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew testDebugUnitTest      # JVM unit tests; run before every commit
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # currently signed with the debug key (see Releasing)
```

Physical device required for anything involving the recorder: BLE does not exist in the
emulator. The SDK aar does ship x86/x86_64 native libraries as well as ARM, so an x86_64 emulator
runs the app for UI and WebView work with `USE_MOCK`; launch it headless with
`-no-window -gpu swiftshader_indirect -feature -Vulkan` (the React dashboard crashes the WebView
on the Vulkan path). Non-exported activities cannot be started with `am start`; launch through the
launcher intent and navigate with taps.

## Rules that are not obvious from the code

- **Never reverse-engineer or bypass the SDK's BLE protocol.** The handshake is encrypted and a
  wrong write can brick a recorder. All device access goes through `plaud-sdk.aar`; update the
  aar only from Plaud's public SDK repo and note the SDK version in the commit.
- **`versionCode` must increase for every APK that will be hosted on a server.** The in-app
  updater installs only a strictly newer `versionCode` with the same package and signer, and the
  server rejects a lower `version_code` upload. Bump `versionCode` and `versionName` together in
  `app/build.gradle`.
- **HTTPS only.** The app refuses `http://` server URLs by design and there is no cleartext
  network security config. Do not add one for convenience.
- **The server API is a contract shared with transom-server.** `ApiClient` is strict about
  status codes and content types on the data endpoints (an HTML 200 from an upload or transcript
  call is an error, redirects are not followed; the health probe only checks for success). New
  fields from the server must be optional so older servers keep working, and the server is always
  upgraded before the app.
- **Identity is `(device_sn, session_id)`**, not filenames. Recordings are matched to server rows
  by server id first, then by that pair.
- **Deletes on the recorder are guarded.** Delete-after-upload only fires after the server
  confirmed the upload, and only for the device whose serial issued the request. Keep the
  attribution checks when touching SyncManager or UploadManager.
- **Self-update verification is not optional.** sha256, size, package name, newer versionCode and
  matching signer lineage are all checked before `PackageInstaller` sees the file. Do not weaken
  any of them.
- **WebView is origin-locked.** The dashboard WebView injects the token only for the configured
  server origin and opens external links in the browser. It needs a `WebChromeClient` or
  `confirm()` silently returns false. Blob downloads are not supported inside the WebView (the
  user gets a toast); copy and export go through the `window.TransomApp` JavaScript bridge.
- **`android:allowBackup` stays false.** The app holds a bearer token and a Plaud JWT.
- **UX rules:** native feel, one Recordings list, status words only while something is in flight,
  no pipeline internals or SDK plumbing in the UI, user words not ours. Transcripts show bookmarks,
  never per-segment timestamps. Summaries render through the shared `MarkdownRenderer` (Markwon);
  test against Markwon spans, not Android `StyleSpan`.
- **No secrets, personal names, hostnames, or private paths in the repo.** Test fixtures use
  fictional names.
- **Naming.** The app is Transom (`cloud.adamrb.transom`). "Plaud" appears only to name the
  vendor's recorder, SDK or cloud (nominative use), never in the app name, package, themes or
  sample vocabulary (samples use the fictional "Parrot Deck"). The `pb_` prefixes on resource
  names, the per-install user id and the dashboard token key are historical; leave them.

## SDK quirks worth knowing

- `GetRecMarkingRsp.getFileList()` is misnamed: it returns the recorder's button-press marks.
  Their unit varies, so `MarkNormalizer` sniffs epoch ms / epoch s / offset ms / offset s.
- SDK callbacks carry no correlation id and are not confined to one thread. Attribution relies
  on capture-at-issue closures, consume-once request objects, and serial re-checks. See "Known
  limitations" in the README before restructuring the managers.
- A recorder can be bound to one app account at a time. The SDK refuses to take over a recorder
  that is still bound to the official Plaud app, so the user has to unbind it there first (the
  README FAQ explains how, and what to do when the old app is gone). Do not hide that from users.

## Releasing

1. Bump `versionCode` and `versionName` in `app/build.gradle`; update the README if behaviour
   changed.
2. Build the APK. For anything shared beyond a personal sideload, sign with a real, protected
   keystore via `signingConfigs` (the `release` build type currently uses the debug key for
   convenience). A published APK's signer becomes the lineage every later update must match.
3. Host it on the server: `POST /api/v1/apk` with `metadata={"version_code","version_name","notes"}`,
   or upload from the dashboard. Phones pick it up at their next update check: automatically at
   most once per 24 hours on foregrounding, or immediately from Settings → Version → Check.
4. If a server image should bundle it, publish `transom.apk` plus `manifest.json` as
   release assets and point the server repo's `APK_RELEASE_URL` variable at them.

## When you change things

- Update `README.md` for user-visible behaviour and `AGENTS.md` for anything a future agent
  needs (new manager, new convention, new quirk).
- Keep the test suite green and add Robolectric or MockWebServer coverage for new contract logic.
- Do not add per-user or per-deployment details (server hostnames, vault layouts) to the repo.
