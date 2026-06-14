# Axon Phase 3 - ntfy Relay Migration Todo

## Goal

Move Axon from a LAN-only direct bridge to an optional self-hosted ntfy relay transport.

The first target is not to remove the current LAN WebSocket bridge. The safer path is to add ntfy as a second transport mode, prove SMS, calls, media, and reverse commands over it, then decide whether LAN should remain as a fallback or become a developer-only mode.

Security was intentionally out of scope for Phase 1 and Phase 2. Phase 3 introduces server-side access control immediately, but app-level end-to-end encryption should be treated as a follow-up hardening milestone.

## Current Baseline

- Receiver currently hosts a local Ktor WebSocket server on port `8080`.
- Sender connects directly to Receiver over LAN.
- Receiver discovery scans the local subnet.
- Messages use `BridgeMessage` JSON with types such as:
  - `NOTIFICATION_EVENT`
  - `MEDIA_UPDATE`
  - `MEDIA_COMMAND`
  - `MEDIA_CLEAR`
  - `CALL_COMMAND`
  - `PING`
  - `ACK`
- Reverse commands already exist:
  - Receiver media controls send commands back to Sender.
  - Receiver call reject sends a call command back to Sender.
- Media artwork currently travels as Base64 in the LAN payload.

## Target Architecture

Use two ntfy topics per paired Axon setup.

ntfy topic names cannot contain dots, so Axon topics should use dashes or underscores:

- `axon-<pairId>-to-receiver`
- `axon-<pairId>-to-sender`

Sender publishes SMS, call, and media events to `to-receiver`.

Receiver subscribes to `to-receiver`, renders notifications, updates inbox/call log/media UI, and mirrors watch state.

Receiver publishes media and call commands to `to-sender`.

Sender subscribes to `to-sender` and dispatches commands to Android media sessions or telecom APIs.

## Topic Naming Rules

- Generate a random `pairId` during pairing.
- Do not use phone model names, user names, or phone numbers in topics.
- Keep topics unguessable when public ntfy or shared infrastructure is used.
- Prefer self-hosted ntfy with authentication and deny-by-default access control.

## Milestone 1: Transport Mode Foundation

- [x] Add a transport mode setting:
  - [x] `LAN`
  - [x] `NTFY`
- [x] Keep the existing LAN implementation working.
- [x] Introduce a transport boundary so app/service logic does not depend directly on LAN WebSocket details.
- [x] Move LAN-specific code behind a `LanBridgeTransport` style implementation.
- [x] Define a shared transport contract for:
  - [x] start as Sender
  - [x] start as Receiver
  - [x] stop
  - [x] publish bridge state through callbacks
  - [x] send outbound bridge messages through the transport implementation
  - [x] receive inbound bridge messages through the transport implementation
- [x] Add an `NtfyBridgeTransport` skeleton for the next milestone.
- [x] Keep current diagnostics intact.

Verification:

- [x] Debug build succeeds after the transport split.
- [ ] LAN mode still connects manually by IP.
- [ ] LAN discovery still works or fails exactly as before.
- [ ] SMS, call, media update, media commands, and call reject still work in LAN mode.

## Milestone 2: ntfy Settings And Pair Identity

- [x] Add persistent ntfy settings:
  - [x] server URL
  - [x] pair ID
  - [x] username
  - [x] password/token credential
  - [x] topic prefix, defaulting to `axon`
- [x] Add Settings UI for ntfy mode:
  - [x] transport mode selector
  - [x] server URL field
  - [x] pair ID field
  - [x] username field
  - [x] password/token field
  - [x] topic prefix field
  - [x] generated topic preview
  - [x] save/apply action
  - [x] connection test action
- [x] Generate a pair ID if none exists.
- [x] Validate server URL before ntfy connection test.
- [x] Hide LAN-only manual IP and scan controls when ntfy mode is selected.
- [x] Keep LAN controls visible when LAN mode is selected.
- [x] Make the bridge foreground service mode-aware so ntfy mode does not require Receiver IP or LAN discovery.

Verification:

- [x] Debug build succeeds after ntfy settings UI and persistence.
- [ ] App restart preserves ntfy settings.
- [ ] Invalid ntfy URL shows a recoverable error.
- [ ] Switching back to LAN restores the old LAN flow.

## Milestone 3: ntfy Server Setup

- [x] Prepare self-hosted ntfy configuration.
- [x] Enable HTTPS.
- [ ] Set default access to deny all.
- [x] Create dedicated Axon users.
- [ ] Allow publish/subscribe only for Axon topics.
- [ ] Set a short cache duration for privacy.
- [x] Decide whether attachments are disabled for the first version.
- [x] Document server environment variables or config file values.
- [x] Document backup/restore expectations for ntfy config.

Recommended policy:

- Sender can publish to `axon-<pairId>-to-receiver`.
- Sender can subscribe to `axon-<pairId>-to-sender`.
- Receiver can subscribe to `axon-<pairId>-to-receiver`.
- Receiver can publish to `axon-<pairId>-to-sender`.

Verification:

- [x] `curl` can publish a test message with authenticated Axon users.
- [x] `curl` subscription receives the authenticated test messages.
- [ ] Unauthorized publish/subscribe attempts fail.

Current blocker:

- Anonymous publish and subscribe currently return HTTP `200`.
- Wrong-direction authenticated publish also currently returns HTTP `200`.
- This means the auth database/ACL exists, but the running ntfy server is not enforcing `auth-file` and `auth-default-access=deny-all` yet.

## Milestone 4: Protocol Envelope For Relay Transport

- [x] Add an Axon relay envelope around `BridgeMessage`.
- [x] Include:
  - [x] message ID
  - [x] pair ID
  - [x] source device ID
  - [x] target role
  - [x] created timestamp
  - [x] message type
  - [x] payload version
  - [x] bridge payload
- [x] Add persistent local device ID.
- [x] Add deduplication by message ID.
- [x] Ignore messages from the current local device.
- [x] Ignore messages for a different pair ID.
- [x] Ignore messages for a different target role.
- [x] Add version handling so future payload changes do not crash older builds.
- [x] Add diagnostics for accepted, ignored, duplicate, and malformed relay messages.
- [x] Wire the envelope codec into the ntfy transport skeleton for Milestone 5.

Verification:

- [x] Debug build succeeds after adding the relay envelope codec.
- [ ] Duplicate ntfy messages do not create duplicate SMS/call/media events.
- [ ] Wrong pair ID is ignored.
- [ ] Malformed payload is logged without crashing the service.

Implementation files:

- `app/src/main/java/com/axon/bridge/domain/BridgeProtocol.kt`
- `app/src/main/java/com/axon/bridge/data/RelayEnvelopeCodec.kt`
- `app/src/main/java/com/axon/bridge/data/AxonSettings.kt`
- `app/src/main/java/com/axon/bridge/data/NtfyBridgeTransport.kt`

## Milestone 5: Sender To Receiver Over ntfy

- [x] Implement ntfy publish from Sender to Receiver.
- [x] Implement ntfy subscribe on Receiver.
- [x] Support these message types first:
  - [x] `NOTIFICATION_EVENT`
  - [x] call state inside `NotificationPayload`
  - [x] `MEDIA_UPDATE` without artwork if payload is too large
  - [x] `MEDIA_CLEAR`
- [x] Add reconnect handling for the subscription stream.
- [x] Add retry/backoff for publish failures.
- [x] Add local in-memory queue for short offline periods.
- [x] Add diagnostics for publish success/failure and subscription state.

Verification:

- [x] Debug build succeeds after Sender-to-Receiver ntfy transport implementation.
- [ ] Sender SMS appears on Receiver when devices are not on the same Wi-Fi.
- [ ] Receiver call notification/log updates over ntfy.
- [ ] Receiver media panel updates over ntfy.
- [ ] Receiver service recovers after network loss.

Implementation files:

- `app/src/main/java/com/axon/bridge/data/NtfyBridgeTransport.kt`
- `app/src/main/java/com/axon/bridge/service/BridgeService.kt`
- `app/src/main/java/com/axon/bridge/data/RelayEnvelopeCodec.kt`

## Milestone 6: Receiver To Sender Commands Over ntfy

- [x] Publish Receiver commands to `to-sender`.
- [x] Subscribe on Sender to `to-sender`.
- [x] Support:
  - [x] `MEDIA_COMMAND`
  - [x] `CALL_COMMAND`
- [x] Route media commands into `MediaSessionTracker`.
- [x] Route call reject command into the existing Sender call-control path.
- [x] Add diagnostics for command publish, receive, ignore, and dispatch.

Verification:

- [x] Debug build succeeds after Receiver-to-Sender ntfy command implementation.
- [ ] Receiver phone play/pause controls affect Sender playback over ntfy.
- [ ] Receiver watch controls affect Sender playback over ntfy.
- [ ] Receiver call reject attempts to end the Sender call over ntfy.
- [ ] Commands from wrong pair/device are ignored.

Implementation files:

- `app/src/main/java/com/axon/bridge/data/NtfyBridgeTransport.kt`
- `app/src/main/java/com/axon/bridge/service/BridgeService.kt`
- `app/src/main/java/com/axon/bridge/data/RelayEnvelopeCodec.kt`

## Milestone 7: Media Artwork Strategy

- [x] Measure relay envelope payload size before publishing media over ntfy.
- [x] Decide first release behavior:
  - [x] inline artwork only when the full relay envelope is below `3500` UTF-8 bytes.
  - [x] omit artwork over ntfy when the payload is larger.
  - [ ] use ntfy attachments if acceptable for privacy and server config.
- [x] Keep artwork over LAN unchanged.
- [x] Add payload-size diagnostics for media updates.
- [x] Add a fallback placeholder when artwork is unavailable.
- [x] Render artwork in the Receiver media panel when it is available.

Recommendation:

- First ntfy build should ship without artwork or with very small compressed artwork.
- Attachment-based artwork should wait until the text event path is stable.

Verification:

- [x] Debug build succeeds after artwork policy and UI fallback changes.
- [ ] Large album art does not break media sync.
- [ ] Media metadata continues to update when artwork is omitted.
- [ ] Receiver UI handles missing artwork cleanly.

Implementation files:

- `app/src/main/java/com/axon/bridge/data/MediaArtworkPolicy.kt`
- `app/src/main/java/com/axon/bridge/data/NtfyBridgeTransport.kt`
- `app/src/main/java/com/axon/bridge/MainActivity.kt`

## Milestone 8: Reliability And Offline Behavior

- [x] Add startup recovery using ntfy cached messages with a bounded `since=10m` strategy.
- [x] Add local message dedupe cache with expiry.
- [x] Add bounded retry queue for outbound messages.
- [x] Persist the bounded retry queue across short service restarts.
- [x] Add clear bridge states for:
  - [x] connecting
  - [x] subscribed
  - [x] publishing failed
  - [x] auth failed
  - [x] server unreachable
- [x] Add a manual reconnect action.
- [x] Add diagnostics filtering so ntfy logs stay readable.

Verification:

- [x] Debug build succeeds after reliability/offline behavior changes.
- [ ] Restarting Receiver does not replay old SMS endlessly.
- [ ] Short network outages recover without manual restart.
- [ ] Auth errors are visible and not treated as normal disconnects.

Implementation files:

- `app/src/main/java/com/axon/bridge/data/NtfyBridgeTransport.kt`
- `app/src/main/java/com/axon/bridge/data/NtfyPendingMessageStore.kt`
- `app/src/main/java/com/axon/bridge/data/RelayEnvelopeCodec.kt`
- `app/src/main/java/com/axon/bridge/presentation/HomeViewModel.kt`
- `app/src/main/java/com/axon/bridge/MainActivity.kt`

## Milestone 9: Security Hardening

- [x] Add app-level pair secret.
- [x] Encrypt relay payloads before publishing to ntfy.
- [x] Authenticate message envelopes.
- [x] Add key rotation or re-pairing behavior.
- [x] Add a clear "unpair" action.
- [x] Remove sensitive payloads from verbose diagnostics.
- [x] Review local storage of ntfy tokens and pair secrets.

Verification:

- [x] Debug build succeeds after relay encryption and unpair changes.
- [x] ntfy server operators cannot read SMS/call/media payload content.
- [x] Messages tampered with in transit are rejected by AES-GCM authentication.
- [x] Unpairing stops both directions from accepting old messages by rotating pair ID and clearing pair secret.

Implementation files:

- `app/src/main/java/com/axon/bridge/data/RelayCrypto.kt`
- `app/src/main/java/com/axon/bridge/data/RelayEnvelopeCodec.kt`
- `app/src/main/java/com/axon/bridge/domain/BridgeProtocol.kt`
- `app/src/main/java/com/axon/bridge/domain/BridgeModels.kt`
- `app/src/main/java/com/axon/bridge/data/NtfyPendingMessageStore.kt`
- `app/src/main/java/com/axon/bridge/presentation/HomeViewModel.kt`
- `app/src/main/java/com/axon/bridge/MainActivity.kt`

## Milestone 10: Documentation And Public Release Readiness

- [ ] Update README with both LAN and ntfy transport modes.
- [ ] Update GitHub Pages copy from "without a cloud relay" to "LAN or self-hosted relay".
- [ ] Add self-hosted ntfy setup guide.
- [ ] Add privacy warning for non-encrypted relay mode.
- [ ] Add troubleshooting for:
  - [ ] auth failure
  - [ ] wrong pair ID
  - [ ] server unreachable
  - [ ] payload too large
  - [ ] commands not returning to Sender
- [ ] Add screenshots after UI is stable.

Verification:

- [ ] A new user can understand which mode to choose.
- [ ] A technical user can self-host ntfy and pair two phones from the docs.
- [ ] Public docs do not imply end-to-end security before it exists.

## First Implementation Slice

The first coding slice should be intentionally small:

- [ ] Add transport mode setting.
- [ ] Preserve LAN as default.
- [ ] Add ntfy settings fields.
- [ ] Add `NtfyBridgeTransport` skeleton.
- [ ] Publish and subscribe to a simple test `PING`/`ACK` over ntfy.
- [ ] Show ntfy connection state in diagnostics.

Stop after this slice and test before moving SMS/call/media traffic.

## Open Decisions

- [ ] Should ntfy mode become the default for public users?
- [ ] Should LAN discovery stay as a fallback?
- [ ] Should pairing be QR-code based before public release?
- [ ] Should app-level encryption ship in the first ntfy public build or the second?
- [ ] Should artwork be omitted, compressed inline, or sent as attachments?
- [ ] Should ntfy topics be generated by the app or manually configured by the user?

## Definition Of Done For Phase 3

- [ ] Two phones can mirror SMS over self-hosted ntfy without sharing a local network.
- [ ] Incoming/ongoing/ended call state mirrors over ntfy.
- [ ] Receiver call reject reaches Sender over ntfy.
- [ ] Media metadata mirrors over ntfy.
- [ ] Receiver phone and watch media controls reach Sender over ntfy.
- [ ] LAN mode still works unless intentionally removed.
- [ ] Diagnostics make ntfy failures understandable.
- [ ] Public docs clearly describe privacy, setup, and limitations.
