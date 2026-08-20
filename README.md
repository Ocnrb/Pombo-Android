# [Pombo](https://pombo.cc)

# Pombo Android

Android client for **Pombo**, a decentralized P2P chat app built on the
[Streamr](https://streamr.network) network — a native Kotlin/Jetpack Compose
UI with an embedded JavaScript transport bridge. Networking runs the official
Streamr JS SDK in a headless WebView; UI, state, storage, symmetric crypto
and notifications are native Kotlin. It's a port of the Pombo web app,
mirroring its wire protocol so both clients talk to each other on the same
channels and DMs.

## Architecture

```
┌───────────────────────────── Native app (Kotlin) ──────────────────────────────┐
│  Jetpack Compose UI — dark theme, pill nav (mobile viewport of the web app)    │
│  AppViewModel — channels, messages, identity, sync                            │
│  Protocol.kt — envelope/hash format, byte-compatible with the Pombo web app    │
│  WalletStore — private key in EncryptedSharedPreferences (Android Keystore)   │
├──────────────────────────────── PomboBridge.kt ────────────────────────────────┤
│  The only file that knows about the WebView.                                  │
│  Kotlin → JS: evaluateJavascript → bridgeCall(id, method, argsB64)            │
│  JS → Kotlin: @JavascriptInterface Native.result / Native.message / status    │
├─────────────────────────── Headless WebView (JS) ──────────────────────────────┤
│  assets/pombo_bridge.html — StreamrClient (same config as the web app) + ethers│
│  assets/pombo-vendor.bundle.js — vendor bundle (Streamr SDK + ethers 6)       │
└─────────────────────────────────────────────────────────────────────────────────┘
```

A headless WebView runs the Streamr JS SDK and `ethers` as the transport /
signing layer — the same approach used by the PomboTV Android proof of
concept. Everything else (UI, state, storage, crypto for symmetric
encryption) is native Kotlin.

Key design decisions:

- **Wire-compatible with the Pombo web app** — same stream IDs (triple-stream
  layout, see `core/StreamConstants.kt`), same message envelope, and the same
  canonical hash (`{"protocol":"POMBO","version":1,...}` → keccak256 →
  `personal_sign`). The web app is used as the reference peer during testing.
- **`ethers` stays in the JS bridge** (wallet signing, ECDH key agreement) to
  guarantee byte-for-byte compatibility with the web app's crypto. Symmetric
  encryption (AES-GCM) and local persistence are native (JCA / encrypted
  SharedPreferences).
- **No passwords** — identity is a private key (imported or generated),
  stored in `EncryptedSharedPreferences` backed by the Android Keystore.
  Multiple accounts are supported.
- The vendor bundle (`app/src/main/assets/pombo-vendor.bundle.js`) is built by
  the web app; when the SDK is updated there, the bundle is copied over.

## Features

- Public channels: create/join, native (non-Streamr-hosted) channel
  membership and moderation, password-protected channels (AES-GCM), invites.
- Direct messages: end-to-end encrypted via ECDH + HKDF + AES-GCM, with
  ephemeral presence/typing indicators.
- Reactions, message edit/delete, read/unread tracking, chat history via
  Streamr resend.
- Push notifications (Firebase Cloud Messaging) waking the app to fetch new
  messages over Streamr.
- On-chain awareness: gas estimation and balance checks before any
  transaction, plus a chain-mismatch guard.
- Deterministic SVG avatars matching the web app's generator.
- Cross-device sync of channels, contacts and settings.

See `MEMORY.md`-style module docs in-code (`core/`, `data/`, `ui/`) for
details; each subsystem is self-contained and documented at the top of its
file where behavior isn't obvious from the code.

## Requirements

- Android Studio (Koala or newer) with JDK 17.
- Android SDK 35 (`compileSdk`/`targetSdk`), minimum supported OS: Android 8.0
  (`minSdk` 26).
- A `google-services.json` for Firebase Cloud Messaging (already included for
  the project's Firebase project; replace it if you fork with your own).

## Build

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

Release builds are unminified (the JS bridge relies on exact global names)
and signed with the debug key for local testing only — a real signing key is
required for a store build.

## Testing

```
gradlew.bat test
```

Unit tests cover the protocol encoding/hashing and sync-merge logic
(`app/src/test/java/com/pombo/android/core`).

## Project layout

```
app/src/main/java/com/pombo/android/
├── bridge/    WebView bridge (the only Streamr/ethers entry point)
├── core/      Protocol, crypto, stream constants, stores shared across UI
├── data/      Local persistence (channels, contacts, invites, settings, sync)
├── identity/  Wallet/key storage
├── push/      FCM registration and wake-up handling
└── ui/        Compose screens, theme, avatars, dialogs
```

## Status

Public channels, DMs, invites, push notifications, gas/chain guards and
cross-device sync are implemented and interoperate with the web app. Ongoing
work: full UI parity with the web app (explore, settings polish), P2P media
transfer, and additional moderation tooling.
