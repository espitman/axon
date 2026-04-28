# Axon

Axon is a private Android-to-Android bridge for keeping a secondary Android phone in sync with important communication events from a primary phone.

The first production target is a two-device setup:

- **Sender**: Xiaomi phone that receives the original SMS and call events.
- **Receiver**: Pixel phone that stays connected to the Sender over the local network, shows mirrored alerts, forwards those alerts to the user's watch through the normal Android notification pipeline, and keeps a local SMS inbox.

Phase 1 intentionally optimizes for practical daily use on the user's own devices. Network security, pairing, encryption, and multi-user hardening are explicitly out of scope for now.

## Product Goal

Axon solves a very specific problem: some notifications and SMS messages arrive on one Android device, but the user wants to see and manage them from another Android device and its connected watch.

The business value is not "another SMS app." Axon is a local device bridge:

- It keeps the original phone as the source of truth.
- It mirrors important events to a second phone without cloud infrastructure.
- It lets the second phone behave like a companion inbox and notification relay.
- It works around OEM-specific background limitations, especially Xiaomi/HyperOS behavior.

The current MVP focuses on reliability for SMS delivery:

1. Sender receives an SMS.
2. Sender resolves the sender name from Contacts when permission is available.
3. Sender sends a structured event to Receiver over WebSocket.
4. Receiver shows a high-priority mirrored notification.
5. Receiver stores the SMS in a thread-based inbox.
6. A connected watch, such as Amazfit through Zepp, can mirror Axon notifications from the Receiver.

## Current Feature Set

### Bridge Modes

Axon has two runtime roles:

- **Sender**
  - Connects to the Receiver IP.
  - Captures SMS events directly through `SMS_RECEIVED`.
  - Also includes a `NotificationListenerService` path for notification-based mirroring.
  - Resolves contact names through `ContactsContract.PhoneLookup` when `READ_CONTACTS` is granted.
  - Publishes events into the WebSocket transport.

- **Receiver**
  - Hosts a local WebSocket server on port `8080`.
  - Receives notification payloads.
  - Displays mirrored notifications using Android `NotificationManager`.
  - Stores received SMS messages in a local thread-based inbox.

### SMS Inbox

The Receiver includes an in-app SMS archive:

- Thread list grouped by sender name or phone number.
- Last message preview.
- Received time.
- Unread badge.
- Conversation screen per sender.
- Persistent local storage.
- Persian-friendly message rendering with Vazirmatn.

The archive is currently local to the Receiver and stores mirrored SMS messages only. It does not read the Receiver's system SMS database.

### Watch Support

Axon does not talk directly to Amazfit watches. Instead, the Receiver creates normal Android notifications and the watch companion app mirrors them.

For Amazfit devices, the Zepp app must be configured to allow notifications from **Axon**. From the watch's point of view, mirrored SMS messages are Axon notifications, not Messages/SMS app notifications.

### Diagnostics

The Settings screen includes a diagnostics panel:

- Fixed-height log box.
- Last 10 entries visible.
- Scrollable log.
- Clear button.
- Ping button for testing Sender/Receiver connectivity.

Diagnostics help identify whether a problem is in SMS capture, contact lookup, WebSocket transport, Receiver notification display, or watch mirroring.

## Technical Architecture

The app is a single Android application that can run in either Sender or Receiver mode.

```text
Sender phone
  SMS BroadcastReceiver / NotificationListenerService
          |
          v
  NotificationEventBus
          |
          v
  Ktor WebSocket client
          |
       LAN Wi-Fi
          |
          v
  Ktor WebSocket server
          |
          v
Receiver phone
  NotificationManager + SMS archive + Compose UI
```

### Layers

- `domain`
  - Bridge roles and state.
  - WebSocket protocol models.
  - SMS archive models.

- `data`
  - Settings persistence.
  - Ktor WebSocket transport.
  - Network/device info helpers.
  - Diagnostics log.
  - In-memory event bus.
  - SMS archive persistence.

- `service`
  - Foreground bridge service.
  - Notification listener.
  - SMS broadcast receiver.
  - Mirrored notification manager.

- `presentation`
  - `HomeViewModel`.
  - Device role, permissions, diagnostics, inbox state.

- `MainActivity.kt`
  - Jetpack Compose UI.
  - Home, Settings, Inbox, and thread screens.
  - Page transitions.
  - Runtime permission requests.

## Transport Protocol

Axon uses Ktor WebSockets and kotlinx.serialization JSON.

Default endpoint:

```text
ws://<receiver-ip>:8080/bridge
```

Important message types:

```json
{
  "type": "HELLO",
  "hello": {
    "role": "Source",
    "deviceName": "Xiaomi 11T Pro",
    "appVersion": "0.1.0"
  }
}
```

```json
{
  "type": "NOTIFICATION_EVENT",
  "payload": {
    "id": "stable-id",
    "category": "SMS",
    "originDevice": "Xiaomi 11T Pro",
    "title": "Sender name or number",
    "message": "SMS body",
    "packageName": "android.provider.Telephony.SMS_RECEIVED",
    "postedTime": 1710000000000
  }
}
```

```json
{
  "type": "PING"
}
```

The Sender reconnects with exponential backoff. Both sides support ping/ack diagnostics.

## Android Permissions

Axon uses permissions according to role and feature:

- `INTERNET`
  - WebSocket client/server.

- `ACCESS_NETWORK_STATE`
  - Local IP and network state display.

- `FOREGROUND_SERVICE`
  - Keeps the bridge active.

- `FOREGROUND_SERVICE_DATA_SYNC`
  - Foreground service type for Android 14+.

- `POST_NOTIFICATIONS`
  - Receiver needs this to show mirrored notifications.

- `RECEIVE_SMS`
  - Sender uses this to receive SMS directly through Android's SMS broadcast path.

- `READ_CONTACTS`
  - Sender uses this to resolve phone numbers to contact names.

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  - Opens the system exemption flow.

- `WAKE_LOCK`
  - Keeps the bridge alive while running.

Notification Access is not a manifest runtime permission. It is managed through Android settings and is used by `AxonNotificationListenerService`.

## Xiaomi / HyperOS Notes

For Sender mode on Xiaomi/HyperOS, Android permissions are often not enough. The user may also need to configure:

- Auto-start enabled.
- Battery set to "No restrictions."
- Axon locked in recents.
- Notification access enabled when using notification-listener capture.
- SMS and Contacts permissions allowed.

The SMS broadcast path was added because some Xiaomi setups do not reliably expose SMS notifications through `NotificationListenerService`.

## Receiver / Watch Notes

Receiver notifications are normal Android notifications created by Axon. For Amazfit:

- Open Zepp.
- Enable App Alerts.
- Allow notifications from Axon.
- Make sure Zepp has notification access.
- Disable aggressive battery restrictions for Zepp if notifications are delayed.

If SMS reaches the Pixel but not the watch, the bridge is working and the remaining issue is usually the watch companion app's notification allowlist.

## Build And Run

Requirements:

- Android Studio / Android SDK.
- JDK 17.
- Gradle wrapper included in the repo.

Debug build:

```bash
./gradlew :app:assembleDebug
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

Run helper:

```bash
./scripts/android_run.sh
```

## Current Limitations

- No encryption or authentication yet.
- Designed for trusted LAN use.
- Manual Receiver IP entry.
- SMS archive is local to the Receiver only.
- No reply support.
- No message deletion UI yet.
- No search yet.
- No cloud sync.
- Call mirroring exists through notification filtering but SMS is currently the more tested path.

## Roadmap

### Phase 1

- Stable Sender/Receiver bridge.
- SMS mirroring.
- Contact name resolution.
- Receiver notification display.
- Watch mirroring through the Receiver's notification pipeline.
- Thread-based Receiver inbox.
- Diagnostics and ping/pong tooling.

### Phase 2

Planned media integration:

- Source media session tracking.
- Receiver-side shadow media session.
- Watch media controls.
- Back-channel commands from Receiver/watch to Sender.

## Repository Notes

Generated build outputs are ignored. APKs are not committed. The source of truth is the Android project under `app/`, the Gradle wrapper, scripts, and documentation under `docs/`.
