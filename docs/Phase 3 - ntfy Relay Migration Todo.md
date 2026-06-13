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

- [ ] Add persistent ntfy settings:
  - [ ] server URL
  - [ ] pair ID
  - [ ] auth token
  - [ ] topic prefix, defaulting to `axon`
- [ ] Add Settings UI for ntfy mode:
  - [ ] transport mode selector
  - [ ] server URL field
  - [ ] pair ID field
  - [ ] token field
  - [ ] save/apply action
  - [ ] connection test action
- [ ] Generate a pair ID if none exists.
- [ ] Validate server URL before starting bridge.
- [ ] Hide LAN-only manual IP and scan controls when ntfy mode is selected.
- [ ] Keep LAN controls visible when LAN mode is selected.

Verification:

- [ ] App restart preserves ntfy settings.
- [ ] Invalid ntfy URL shows a recoverable error.
- [ ] Switching back to LAN restores the old LAN flow.

## Milestone 3: ntfy Server Setup

- [ ] Prepare self-hosted ntfy configuration.
- [ ] Enable HTTPS.
- [ ] Set default access to deny all.
- [ ] Create a dedicated Axon user or token.
- [ ] Allow publish/subscribe only for Axon topics.
- [ ] Set a short cache duration for privacy.
- [ ] Decide whether attachments are disabled for the first version.
- [ ] Document server environment variables or config file values.
- [ ] Document backup/restore expectations for ntfy config.

Recommended policy:

- Sender can publish to `axon-<pairId>-to-receiver`.
- Sender can subscribe to `axon-<pairId>-to-sender`.
- Receiver can subscribe to `axon-<pairId>-to-receiver`.
- Receiver can publish to `axon-<pairId>-to-sender`.

Verification:

- [ ] `curl` can publish a test message with the token.
- [ ] `curl` or browser subscription receives the test message.
- [ ] Unauthorized publish/subscribe attempts fail.

## Milestone 4: Protocol Envelope For Relay Transport

- [ ] Add an Axon relay envelope around `BridgeMessage`.
- [ ] Include:
  - [ ] message ID
  - [ ] pair ID
  - [ ] source device ID
  - [ ] target role
  - [ ] created timestamp
  - [ ] message type
  - [ ] payload version
  - [ ] bridge payload
- [ ] Add deduplication by message ID.
- [ ] Ignore messages from the current local device.
- [ ] Ignore messages for a different pair ID.
- [ ] Add version handling so future payload changes do not crash older builds.
- [ ] Add diagnostics for accepted, ignored, duplicate, and malformed relay messages.

Verification:

- [ ] Duplicate ntfy messages do not create duplicate SMS/call/media events.
- [ ] Wrong pair ID is ignored.
- [ ] Malformed payload is logged without crashing the service.

## Milestone 5: Sender To Receiver Over ntfy

- [ ] Implement ntfy publish from Sender to Receiver.
- [ ] Implement ntfy subscribe on Receiver.
- [ ] Support these message types first:
  - [ ] `NOTIFICATION_EVENT`
  - [ ] call state inside `NotificationPayload`
  - [ ] `MEDIA_UPDATE` without artwork if payload is too large
  - [ ] `MEDIA_CLEAR`
- [ ] Add reconnect handling for the subscription stream.
- [ ] Add retry/backoff for publish failures.
- [ ] Add local queue for short offline periods.
- [ ] Add diagnostics for publish success/failure and subscription state.

Verification:

- [ ] Sender SMS appears on Receiver when devices are not on the same Wi-Fi.
- [ ] Receiver call notification/log updates over ntfy.
- [ ] Receiver media panel updates over ntfy.
- [ ] Receiver service recovers after network loss.

## Milestone 6: Receiver To Sender Commands Over ntfy

- [ ] Publish Receiver commands to `to-sender`.
- [ ] Subscribe on Sender to `to-sender`.
- [ ] Support:
  - [ ] `MEDIA_COMMAND`
  - [ ] `CALL_COMMAND`
- [ ] Route media commands into `MediaSessionTracker`.
- [ ] Route call reject command into the existing Sender call-control path.
- [ ] Add diagnostics for command publish, receive, ignore, and dispatch.

Verification:

- [ ] Receiver phone play/pause controls affect Sender playback over ntfy.
- [ ] Receiver watch controls affect Sender playback over ntfy.
- [ ] Receiver call reject attempts to end the Sender call over ntfy.
- [ ] Commands from wrong pair/device are ignored.

## Milestone 7: Media Artwork Strategy

- [ ] Measure real ntfy payload limits with current artwork payloads.
- [ ] Decide first release behavior:
  - [ ] omit artwork over ntfy, or
  - [ ] aggressively resize/compress artwork, or
  - [ ] use ntfy attachments if acceptable for privacy and server config.
- [ ] Keep artwork over LAN unchanged unless a shared protocol change requires it.
- [ ] Add payload-size diagnostics for media updates.
- [ ] Add a fallback placeholder when artwork is unavailable.

Recommendation:

- First ntfy build should ship without artwork or with very small compressed artwork.
- Attachment-based artwork should wait until the text event path is stable.

Verification:

- [ ] Large album art does not break media sync.
- [ ] Media metadata continues to update when artwork is omitted.
- [ ] Receiver UI handles missing artwork cleanly.

## Milestone 8: Reliability And Offline Behavior

- [ ] Add startup recovery using ntfy cached messages or a `since` strategy.
- [ ] Add local message dedupe cache with expiry.
- [ ] Add bounded retry queue for outbound messages.
- [ ] Add clear bridge states for:
  - [ ] connecting
  - [ ] subscribed
  - [ ] publishing failed
  - [ ] auth failed
  - [ ] server unreachable
- [ ] Add a manual reconnect action.
- [ ] Add diagnostics filtering so ntfy logs stay readable.

Verification:

- [ ] Restarting Receiver does not replay old SMS endlessly.
- [ ] Short network outages recover without manual restart.
- [ ] Auth errors are visible and not treated as normal disconnects.

## Milestone 9: Security Hardening

- [ ] Add app-level pair secret.
- [ ] Encrypt relay payloads before publishing to ntfy.
- [ ] Authenticate message envelopes.
- [ ] Add key rotation or re-pairing behavior.
- [ ] Add a clear "unpair" action.
- [ ] Remove sensitive payloads from verbose diagnostics.
- [ ] Review local storage of ntfy tokens and pair secrets.

Verification:

- [ ] ntfy server operators cannot read SMS/call/media payload content.
- [ ] Messages tampered with in transit are rejected.
- [ ] Unpairing stops both directions from accepting old messages.

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
