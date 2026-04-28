# Axon Phase 1 Implementation Plan

## Goal

Build the first usable version of Axon for two specific Android devices:

- Source: Xiaomi 11T Pro
- Sink: Google Pixel 9 Pro XL

Phase 1 mirrors SMS and incoming-call notifications from the Xiaomi to the Pixel over a local WebSocket bridge. Security/authentication is intentionally out of scope for this phase.

## Milestone 1: Real App State

- Split the app into small domain, service, and presentation layers.
- Add role state:
  - Source
  - Sink
- Add bridge state:
  - Disconnected
  - Connecting
  - Connected
  - Error
- Persist the selected role and server IP locally.
- Replace static Home screen values with ViewModel-driven state.

Verification:

- Role switch updates real state.
- App restart keeps the selected role and server IP.
- Home UI no longer depends on hard-coded demo state except for values not implemented yet.

## Milestone 2: Permissions And Settings

- Detect notification access status.
- Detect Android 13+ notification permission.
- Detect battery optimization status.
- Add settings intents for:
  - Notification access
  - App notification permission
  - Battery optimization exemption
- Show accurate granted/denied status on Home.

Verification:

- Permission rows reflect real device status.
- Permissions button opens the relevant system settings flow.

## Milestone 3: Bridge Foreground Service

- Add `BridgeService`.
- Start and stop it from Home.
- Run as a sticky foreground service.
- Show a persistent Axon status notification.
- Decide behavior by selected role:
  - Sink starts server mode.
  - Source starts client mode.
- Publish bridge state back to the UI.

Verification:

- Start bridge creates a foreground notification.
- Stop bridge stops the service.
- State survives Activity close/reopen.

## Milestone 4: WebSocket Transport

- Add Ktor server/client and kotlinx.serialization.
- Sink mode:
  - Bind WebSocket server to port `8080`.
  - Use endpoint `/bridge`.
  - Accept local-network clients.
- Source mode:
  - Connect to `ws://<server-ip>:8080/bridge`.
  - Reconnect with exponential backoff from 2s to 60s.
  - Keep socket alive with ping/pong.
- Send a test event before wiring real notifications.

Verification:

- Pixel in Sink mode and Xiaomi in Source mode connect on LAN.
- Home shows real `Connected`/`Disconnected` state.
- Wrong IP produces a visible error.

## Milestone 5: Notification Mirroring

- Add `AxonNotificationListenerService`.
- Filter source notifications by package:
  - SMS: `com.google.android.apps.messaging`, `com.android.mms`
  - Call: `com.google.android.dialer`, `com.android.server.telecom`
- Extract title, message, package name, category, and posted time.
- Serialize as `NOTIFICATION_EVENT`.
- Send through the active WebSocket client.
- On Sink, inject mirrored notifications through `NotificationManager`.

Verification:

- SMS/call notification on Xiaomi appears as a mirrored Axon notification on Pixel.
- Repeated updates reuse stable IDs instead of spamming.

## Milestone 6: Android-Proofing

- Add wake lock while bridge is active.
- Keep service sticky.
- Add Xiaomi/HyperOS guidance:
  - Auto-start
  - Battery: No restrictions
  - Lock app in recents when needed
- Handle edge cases:
  - Missing notification access
  - Missing server IP
  - Server offline
  - Payload missing title/message
  - Wi-Fi disconnect/reconnect

Verification:

- Bridge keeps running with the screen off.
- Reconnect works after network interruption.
- Failure states are visible and recoverable.

## Execution Order

1. Real Home state and persistence.
2. Permission monitor.
3. Bridge foreground service.
4. WebSocket transport with test events.
5. Notification listener and notification injection.
6. Reliability pass on both target phones.
