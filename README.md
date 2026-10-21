# Re:Dollars — Android

Native Android client for [Bangumi Dollars](https://bgm.tv/dollars), the realtime chat room on
[Bangumi](https://bgm.tv). Kotlin + Jetpack Compose + Material 3.

Reading and realtime updates go through the [re-dollars-backend](../re-dollars-backend) service;
posting goes directly to Bangumi over native HTTP using your logged-in session, as the
[re-dollars-preact](../re-dollars-preact) userscript does. See
[ANDROID_APP_SPEC.md](../ANDROID_APP_SPEC.md) for the full technical spec this app was built from.

## Features

- Live message stream over WebSocket, with REST-backed history/scrollback and gap recovery on reconnect
- Sending messages with optimistic bubbles, send/confirm, and retry on failure
- Replies (quote), reactions, edit/delete for your own messages
- BBCode rendering: text styles, links, `[user]` mentions, `[quote]`, `[img]`/`[video]`/`[audio]`, `[code]`, `[mask]` spoilers, smilies, and BMO emoji
- Image lightbox, media gallery, in-app search
- Presence (online count, typing indicators), push notifications (FCM), voice message recording
- Per-user blocking, account sheet, Material 3 theming with dynamic color and light/dark support

## Requirements

- Android Studio (current stable)
- JDK 17
- Android SDK: minSdk 26, targetSdk 36, compileSdk 37

## Setup

1. Open the `re-dollars-Android/` directory in Android Studio and let it sync Gradle.
2. Push notifications require Firebase: drop your own `app/google-services.json` in place of/next
   to the existing one, or leave it as-is if you're not touching FCM. The build stays green either
   way (the `google-services` plugin is only applied if the file exists).
3. Release builds are signed using `release.keystore` at the project root, with credentials from
   `local.properties` (`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`) or the
   matching environment variables. Not needed for debug builds.
4. Backend/auth endpoints are hardcoded in
   [`Config.kt`](app/src/main/java/mk/ry/redollars/net/Config.kt) — edit there to point at a
   different backend or Bangumi host.

## Build & run

```bash
./gradlew assembleDebug      # debug APK
./gradlew installDebug       # install on a connected device/emulator
./gradlew assembleRelease    # signed release APK
```

## Authentication

Login opens an on-demand WebView against Bangumi (`bgm.tv`). On successful login,
website cookies, the browser User-Agent and user identity are saved using the existing
credential preferences. Normal cold startup restores identity from storage and validates
the backend token over HTTP; it does not initialize WebView. Posting uses OkHttp with the
saved Bangumi cookies, while reading and realtime updates use REST/WebSocket.

The one-time [rymk-auth](https://auth.ry.mk) authorization provides a separate backend
credential for backend operations. This token does not replace the Bangumi website cookie.
The login WebView is destroyed when the login overlay closes.

Upgrading from the older WebView-posting version requires opening login once to capture
the website credentials into native storage. HTTP 200 alone is not accepted as a sent
message: the response must contain `status: "ok"`. Failures retain the draft, and ambiguous
network failures are never automatically retried. HTML/challenge responses reopen the
interactive login flow. Native posting still needs verification with a real logged-in account.



## Certificate pinning

TLS pins prefer long-lived Let's Encrypt **YE1/YE2 intermediates** for backend hosts we do not
control (so routine leaf rotation does not break the app). See
[docs/certificate-pinning.md](docs/certificate-pinning.md). Verify locally with:

```bash
python3 tools/verify-certificate-pins.py
```

## Architecture

MVVM with a single-activity Compose UI:

```
app/src/main/java/mk/ry/redollars/
 ├─ net/       REST client, WebSocket client, models, config, uploads
 ├─ data/      MessageRepository — merges WS events + REST paging + Room cache
 ├─ di/        Hilt modules
 ├─ ui/
 │   ├─ chat/    ChatScreen and its sheets (account, block manager, gallery,
 │   │            notifications, reactions, search, typing indicator, ...)
 │   ├─ render/  BBCode → Compose renderer, image/video/audio, smilies
 │   └─ theme/   Material 3 theme
 ├─ bmo/       BMO emoji codec/renderer (Bangumi's custom emoji system)
 ├─ push/      FCM messaging service
 └─ voice/     Voice message recording
```

`ChatViewModel` drives `ChatScreen`; `MessageRepository` is the single source of truth, backed by
Room and kept in sync by `DollarsWs` (WebSocket) and `RestApi`.
