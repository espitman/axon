# Axon Phase 3 - ntfy Server Setup

## Purpose

This document records the current self-hosted ntfy setup used for Axon Phase 3.

The goal of this server is to replace direct phone-to-phone local networking with a bidirectional relay that can move Axon events between Sender and Receiver even when the devices are not on the same Wi-Fi network.

## Server

Base URL:

```text
https://axon-ntfy.liara.run
```

The server is hosted on Liara and uses ntfy authentication/access control.

## Required Server Environment

The ntfy server must run with authentication enabled and anonymous access denied.

Recommended environment variables:

```text
NTFY_BASE_URL=https://axon-ntfy.liara.run
NTFY_BEHIND_PROXY=true
NTFY_AUTH_FILE=/var/lib/ntfy/user.db
NTFY_AUTH_DEFAULT_ACCESS=deny-all
```

Important:

- `NTFY_AUTH_FILE` enables ntfy authentication and access control.
- `NTFY_AUTH_DEFAULT_ACCESS=deny-all` prevents anonymous read/write access to unknown topics.
- `/var/lib/ntfy/user.db` should live on persistent storage. If the container filesystem is not persistent, users and ACLs may disappear after redeploy.

## Users

Two regular users are used:

```text
axon_sender
axon_receiver
```

Both users are regular `user` role accounts, not admins.

Create them inside the ntfy container after auth is configured:

```sh
ntfy user add axon_sender
ntfy user add axon_receiver
```

For scripted setup, passwords can be supplied with `NTFY_PASSWORD`:

```sh
NTFY_PASSWORD='SENDER_PASSWORD' ntfy user add axon_sender
NTFY_PASSWORD='RECEIVER_PASSWORD' ntfy user add axon_receiver
```

## Topic Naming

ntfy topic names may only contain letters, numbers, underscores, and dashes.

Do not use dots in topic names.

For the first Axon test pair, the pair ID is:

```text
p1-test
```

The active topics are:

```text
axon-p1-test-to-receiver
axon-p1-test-to-sender
```

Meaning:

- `axon-p1-test-to-receiver`: Sender publishes Axon events for Receiver.
- `axon-p1-test-to-sender`: Receiver publishes commands for Sender.

## Access Control

Sender:

- Can write to `axon-p1-test-to-receiver`.
- Can read from `axon-p1-test-to-sender`.

Receiver:

- Can read from `axon-p1-test-to-receiver`.
- Can write to `axon-p1-test-to-sender`.

Commands:

```sh
ntfy access axon_sender axon-p1-test-to-receiver wo
ntfy access axon_sender axon-p1-test-to-sender ro

ntfy access axon_receiver axon-p1-test-to-receiver ro
ntfy access axon_receiver axon-p1-test-to-sender wo
```

Expected `ntfy access` output:

```text
user axon_receiver (role: user, tier: none)
- read-only access to topic axon-p1-test-to-receiver
- write-only access to topic axon-p1-test-to-sender
user axon_sender (role: user, tier: none)
- write-only access to topic axon-p1-test-to-receiver
- read-only access to topic axon-p1-test-to-sender
user * (role: anonymous, tier: none)
- no topic-specific permissions
- no access to any (other) topics (server config)
```

## Manual Verification

### Sender To Receiver

Publish as Sender:

```sh
curl -u axon_sender:'SENDER_PASSWORD' \
  -d 'hello receiver' \
  https://axon-ntfy.liara.run/axon-p1-test-to-receiver
```

Expected response shape:

```json
{
  "event": "message",
  "topic": "axon-p1-test-to-receiver",
  "message": "hello receiver"
}
```

Read as Receiver:

```sh
curl -u axon_receiver:'RECEIVER_PASSWORD' \
  'https://axon-ntfy.liara.run/axon-p1-test-to-receiver/json?poll=1'
```

Expected message:

```text
hello receiver
```

### Receiver To Sender

Publish as Receiver:

```sh
curl -u axon_receiver:'RECEIVER_PASSWORD' \
  -d 'hello sender' \
  https://axon-ntfy.liara.run/axon-p1-test-to-sender
```

Expected response shape:

```json
{
  "event": "message",
  "topic": "axon-p1-test-to-sender",
  "message": "hello sender"
}
```

Read as Sender:

```sh
curl -u axon_sender:'SENDER_PASSWORD' \
  'https://axon-ntfy.liara.run/axon-p1-test-to-sender/json?poll=1'
```

Expected message:

```text
hello sender
```

## Current Verified State

The following flows were verified manually:

- `axon_sender` can publish to `axon-p1-test-to-receiver`.
- `axon_receiver` can read from `axon-p1-test-to-receiver`.
- `axon_receiver` can publish to `axon-p1-test-to-sender`.
- `axon_sender` can read from `axon-p1-test-to-sender`.

This means the relay is ready for the first Axon app integration milestone.

## App Settings For First Integration

Sender device:

```text
Server URL: https://axon-ntfy.liara.run
Pair ID: p1-test
Username: axon_sender
Password: sender password
Publish topic: axon-p1-test-to-receiver
Subscribe topic: axon-p1-test-to-sender
```

Receiver device:

```text
Server URL: https://axon-ntfy.liara.run
Pair ID: p1-test
Username: axon_receiver
Password: receiver password
Subscribe topic: axon-p1-test-to-receiver
Publish topic: axon-p1-test-to-sender
```

## Notes For App Implementation

- Use Basic Auth for the first implementation slice.
- Later, move to ntfy access tokens so the app does not store raw account passwords.
- Use `json?poll=1` only for manual tests and cached-message checks.
- The app should use a live subscription endpoint, either:
  - `https://axon-ntfy.liara.run/<topic>/json`
  - `wss://axon-ntfy.liara.run/<topic>/ws`
- Add message IDs and deduplication in Axon's own relay envelope.
- Do not send full artwork payloads in the first ntfy milestone. ntfy text payload limits are much tighter than the current LAN WebSocket path.
- Do not log passwords, tokens, SMS bodies, or call details in verbose diagnostics.

## Security Follow-Up

Before using this with real personal data for more than local testing:

- Replace test passwords with strong unique passwords.
- Prefer access tokens over account passwords in the Android app.
- Add app-level encryption so ntfy cannot read SMS/call/media payload content.
- Keep `deny-all` enabled for anonymous/default access.
- Confirm `/var/lib/ntfy/user.db` is persisted across deploys.
