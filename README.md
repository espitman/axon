# Axon

Axon is a private Android-to-Android bridge for mirroring SMS, call state, media metadata, and media controls between two Android phones.

Axon has two roles:

- **Sender**: the Android phone that receives the original SMS, call, and media-session events.
- **Receiver**: the Android phone that shows mirrored alerts, keeps the local inbox and call log, exposes a shadow media session, and mirrors notifications/controls to a connected watch through Android's normal notification and media pipeline.

The app now supports two transport modes:

- **Local Wi-Fi / LAN**: direct Ktor WebSocket connection between the two phones.
- **Self-hosted ntfy relay**: HTTPS relay through a user-controlled ntfy server for devices that are not on the same local network.

Axon is still experimental. It is built for personal devices first, not for broad managed deployments.

## Product Goal

Axon solves a specific personal-device problem: important messages, calls, and media controls may live on one Android phone, while the user wants to see or control them from another Android phone and its paired watch.

Axon is not a replacement SMS app and it is not a cloud sync service:

- The Sender remains the source of truth.
- The Receiver behaves like a companion inbox, call surface, notification relay, and media controller.
- Watch support comes from normal Android notifications and media sessions.
- Transport can be direct LAN or a self-hosted ntfy relay.

## Current Feature Set

### SMS Mirror And Inbox

On Sender:

- Captures SMS directly through `SMS_RECEIVED`.
- Can use `NotificationListenerService` as an additional notification capture path.
- Resolves contact names through `ContactsContract.PhoneLookup` when `READ_CONTACTS` is granted.

On Receiver:

- Shows mirrored SMS notifications.
- Stores mirrored SMS in a thread-based local inbox.
- Supports unread badges, conversation view, and Persian-friendly text rendering with Vazirmatn.
- Supports long-press selection and deletion for individual messages and entire threads.

The Receiver archive is local to Axon. It does not read or modify the Receiver's system SMS database.

### Call Mirroring

Axon mirrors call state from Sender to Receiver:

- Incoming, ongoing, and ended call state.
- Receiver notifications and in-app call surface.
- Receiver call log.
- Long-press selection and deletion for call log items.
- Experimental reject command from Receiver back to Sender.

Reject support depends on Android version, device policy, default dialer restrictions, and permissions. It may fail on some devices even when the bridge itself is working.

### Media And Watch Controls

Sender tracks active media sessions and sends metadata to Receiver:

- Track title, artist, album, playback state, and supported actions.
- Artwork over LAN.
- Artwork over ntfy is compacted into the encrypted relay payload when possible; it is omitted only if it still exceeds the safe payload limit.

Receiver creates a shadow media session so media controls can flow back to Sender:

- Receiver phone controls.
- Notification controls.
- Watch controls, when the watch companion app routes media buttons through the Receiver phone.

### Watch Support

Axon does not talk directly to watches. The Receiver creates normal Android notifications and media sessions. A watch companion app or Android system bridge mirrors those to the watch.

For companion-app-based watches:

- Allow notifications from **Axon** in the companion app.
- Make sure the companion app has notification access when required.
- Disable aggressive battery restrictions for Axon and the companion app if alerts are delayed.

## Transport Modes

### Local Wi-Fi / LAN

LAN mode is the simplest path when both phones are on the same trusted network or hotspot.

```text
Sender phone
  SMS / Call / Media sources
          |
          v
  Ktor WebSocket client
          |
       Local Wi-Fi
          |
          v
  Ktor WebSocket server on Receiver :8080
          |
          v
Receiver notifications, inbox, call log, media session
```

LAN mode supports:

- Receiver discovery on the local subnet.
- Manual Receiver IP entry.
- Direct WebSocket endpoint:

```text
ws://<receiver-ip>:8080/bridge
```

Use LAN mode only on networks you trust.

### Self-Hosted ntfy Relay

ntfy mode is for cases where the two phones are not on the same Wi-Fi network.

Axon uses two ntfy topics per pair:

```text
axon-<pairId>-to-receiver
axon-<pairId>-to-sender
```

Direction:

- Sender publishes SMS, call, and media events to `to-receiver`.
- Receiver subscribes to `to-receiver`.
- Receiver publishes media and call commands to `to-sender`.
- Sender subscribes to `to-sender`.

Required app settings on both devices:

- Server URL, for example `https://axon-ntfy.example.com`.
- Pair ID.
- Pair secret.
- Username, optional when the personal ntfy server allows anonymous topic access.
- Password or token credential, optional with username.
- Topic prefix, default `axon`.

The pair ID and pair secret must match on both phones.

Relay payloads are encrypted in the app before publishing to ntfy:

- AES-GCM encryption.
- Key derived from the shared pair secret.
- Message authentication rejects tampered payloads.
- Unpairing rotates the pair ID and clears the old pair secret.

The ntfy server still sees topic names, timestamps, message sizes, client IPs, and account activity. For a personal server, Axon can run without ntfy authentication because the app payload is encrypted and authenticated with the pair secret. ntfy authentication and deny-by-default ACLs are still recommended when the server is shared, public, or exposed to people you do not trust.

Self-hosting guide:

- [Phase 3 - ntfy Server Setup](./docs/Phase%203%20-%20ntfy%20Server%20Setup.md)
- [Phase 3 - ntfy Relay Migration Todo](./docs/Phase%203%20-%20ntfy%20Relay%20Migration%20Todo.md)

## Technical Architecture

The app is a single Android application that can run as Sender or Receiver.

Main layers:

- `domain`
  - Bridge roles, models, and protocol types.
- `data`
  - Settings persistence.
  - LAN and ntfy transport implementations.
  - Relay envelope codec and encryption.
  - Retry queue and diagnostics.
  - SMS and call archive persistence.
- `service`
  - Foreground bridge service.
  - Notification listener.
  - SMS broadcast receiver.
  - Call state monitoring.
  - Media session tracking.
  - Mirrored notification manager.
- `presentation`
  - `HomeViewModel`.
  - Role, transport, permissions, diagnostics, inbox, calls, and media state.
- `MainActivity.kt`
  - Jetpack Compose UI.
  - Home, Settings, Messages, Thread, Calls, and page transitions.

## Protocol Notes

LAN mode sends `BridgeMessage` JSON over WebSocket.

ntfy mode wraps `BridgeMessage` in an Axon relay envelope, encrypts the bridge payload, publishes the encrypted envelope text to ntfy with HTTPS `POST`, and receives peer messages through ntfy's long-lived `/json` stream.

Important bridge message types:

```text
HELLO
PING
ACK
NOTIFICATION_EVENT
MEDIA_UPDATE
MEDIA_CLEAR
MEDIA_COMMAND
CALL_COMMAND
```

The ntfy relay envelope includes:

- Message ID.
- Pair ID.
- Source device ID.
- Target role.
- Created timestamp.
- Message type.
- Payload version.
- Encrypted payload.

The receiver side ignores duplicate messages, local echo messages, wrong pair IDs, wrong target roles, malformed payloads, and payloads that fail authentication.

## Android Permissions

Axon uses permissions according to role and feature:

- `INTERNET`
  - LAN WebSocket and ntfy HTTPS transport.
- `ACCESS_NETWORK_STATE`
  - Local IP, network state display, and Wi-Fi change handling.
- `FOREGROUND_SERVICE`
  - Keeps the bridge active.
- `FOREGROUND_SERVICE_DATA_SYNC`
  - Foreground service type for Android 14+.
- `POST_NOTIFICATIONS`
  - Receiver shows mirrored notifications.
- `RECEIVE_SMS`
  - Sender receives SMS directly through Android's SMS broadcast path.
- `READ_CONTACTS`
  - Sender resolves phone numbers to contact names.
- `READ_PHONE_STATE`
  - Sender tracks call state.
- `READ_CALL_LOG`
  - Sender can enrich call details when Android provides them.
- `ANSWER_PHONE_CALLS`
  - Sender attempts experimental call reject commands.
- `USE_FULL_SCREEN_INTENT`
  - Receiver can request full-screen call alerts where Android allows it.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  - Opens the system exemption flow.
- `WAKE_LOCK`
  - Helps keep the bridge alive while running.

Notification Access is managed through Android settings and is used by `AxonNotificationListenerService`.

## Build And Run

Requirements:

- Android Studio / Android SDK.
- JDK 17.
- Gradle wrapper included in the repo.

Debug build:

```bash
./gradlew :app:assembleDebug
```

Run helper:

```bash
./scripts/android_run.sh
```

Release build:

```bash
./scripts/android_release.sh
```

The release script copies the APK to:

```text
/Users/espitman/Desktop/axon-release.apk
```

Install with adb:

```bash
adb install -r /Users/espitman/Desktop/axon-release.apk
```

## Troubleshooting

### LAN Scan Finds Nothing

- Confirm both phones are on the same Wi-Fi, hotspot, or subnet.
- Disable VPN, proxy, or private DNS features that isolate local traffic.
- Try manual Receiver IP entry.
- Confirm Receiver bridge service is running.

### ntfy Auth Failure

- If your personal server allows anonymous access, leave both username and password/token empty.
- If your server requires auth, fill both username and password/token.
- Confirm the app username and password/token match the ntfy user.
- Confirm topic ACLs allow the correct direction for each user.

### Wrong Pair ID Or Pair Secret

- Both phones must use the same pair ID and pair secret.
- If one phone was unpaired, copy the new pair identity to the other phone.
- Wrong pair ID messages are ignored.
- Wrong pair secret messages fail authentication and are ignored.

### ntfy Server Unreachable

- Open the server URL in a browser or test it with `curl`.
- Check HTTPS and reverse-proxy configuration.
- Check DNS and firewall rules.
- Use diagnostics and reconnect after changing settings.

### Payload Too Large

- ntfy mode keeps relay messages small.
- Large media artwork is compacted first and may be omitted only if it still exceeds the safe payload limit.
- Metadata and controls should continue working even when artwork is heavily compressed or not sent.

### Commands Do Not Return To Sender

- Check that Receiver can publish to `axon-<pairId>-to-sender`.
- Check that Sender can subscribe to `axon-<pairId>-to-sender`.
- Confirm both phones have the same pair ID and pair secret.
- For media, confirm Sender has Notification Access and can see the active media session.
- For call reject, confirm `ANSWER_PHONE_CALLS` is granted and the device permits the action.

## Current Limitations

- Axon is experimental and side-load oriented.
- ntfy mode requires a self-hosted server for real personal data.
- ntfy payloads are encrypted, but relay metadata such as topics, timing, and message sizes remain visible to the server.
- ntfy credentials are optional; pair secrets are stored locally on the device.
- Full-screen call alerts depend on Android and OEM settings.
- Reject call is experimental and may fail on some devices.
- Watch behavior depends on the phone companion app and its allowlist.
- Local archive data stays inside Axon on the Receiver.
- No SMS reply support yet.
- No inbox search yet.

## Repository Notes

Generated build outputs are ignored. APKs are not committed. The source of truth is the Android project under `app/`, the Gradle wrapper, scripts, and documentation under `docs/`.
