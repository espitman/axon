# Axon Phase 3 - ntfy Server Setup

## Purpose

This document records the current self-hosted ntfy setup used for Axon Phase 3.

The goal of this server is to replace direct phone-to-phone local networking with a bidirectional relay that can move Axon events between Sender and Receiver even when the devices are not on the same Wi-Fi network.

## Server

Base URL:

```text
https://axon-ntfy.liara.run
```

The server is hosted on Liara. Axon can use it with or without ntfy authentication.

For the current personal setup, ntfy authentication is optional because Axon encrypts and authenticates relay payloads in the Android app with the shared pair secret.

Use ntfy authentication/access control when the server is shared, public, or exposed to users you do not trust.

## Personal Server Mode

For a private server used only by one Axon pair or one trusted owner, the minimal setup is:

```text
NTFY_BASE_URL=https://axon-ntfy.liara.run
NTFY_BEHIND_PROXY=true
```

In this mode:

- Axon username and password/token fields should be left empty.
- ntfy topics may be anonymously readable/writable.
- Axon still encrypts payload content before publish.
- Axon rejects payloads that cannot be decrypted/authenticated with the pair secret.
- Topic names, timestamps, message sizes, client IPs, and activity remain visible to the server.
- Random pair IDs and strong pair secrets are important because server ACLs are not protecting topics.

## Optional Server Auth Mode

For a shared or more exposed server, run ntfy with authentication enabled and anonymous access denied.

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

## Minimal Setup Checklist With ntfy Auth

Use this order if ntfy authentication/access control is enabled:

1. Deploy ntfy behind HTTPS.
2. Set `NTFY_BASE_URL` to the public HTTPS URL.
3. Set `NTFY_BEHIND_PROXY=true` when a reverse proxy terminates TLS.
4. Mount persistent storage for `/var/lib/ntfy`.
5. Set `NTFY_AUTH_FILE=/var/lib/ntfy/user.db`.
6. Set `NTFY_AUTH_DEFAULT_ACCESS=deny-all`.
7. Start ntfy once so the auth database can be created.
8. Create the Sender and Receiver users.
9. Grant topic-specific ACLs.
10. Run the authenticated and unauthorized `curl` checks in this document.
11. Put the same server URL, pair ID, and pair secret into both Axon phones.
12. Put each role's username and password/token into Axon.

If `ntfy user add` says `auth-file does not exist`, start the server once with `NTFY_AUTH_FILE` configured, then run the user command again inside the same configured container/environment.

If `ntfy user add --auth-file=...` fails, do not pass `--auth-file`; this ntfy CLI reads the configured auth file from server configuration or environment.

## Users For Auth Mode

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

The following security checks currently fail:

- Anonymous publish to `axon-p1-test-to-receiver` returns HTTP `200`.
- Anonymous subscribe to both Axon topics returns HTTP `200`.
- `axon_sender` can publish to `axon-p1-test-to-sender`, even though it should be read-only for that topic.

This means the relay message path works, but server-side auth/ACL enforcement is not active in the running ntfy process yet.

Before real app traffic is sent through this server, restart/redeploy ntfy with:

```text
NTFY_AUTH_FILE=/var/lib/ntfy/user.db
NTFY_AUTH_DEFAULT_ACCESS=deny-all
```

Then re-run the unauthorized access checks below.

## Security Verification

Anonymous publish must fail:

```sh
curl -o /tmp/axon_ntfy_anonymous_publish.json -w '%{http_code}\n' \
  -d 'anonymous should fail' \
  https://axon-ntfy.liara.run/axon-p1-test-to-receiver
```

Wrong-direction publish must fail:

```sh
curl -o /tmp/axon_ntfy_wrong_direction.json -w '%{http_code}\n' \
  -u axon_sender:'SENDER_PASSWORD' \
  -d 'sender should not write command topic' \
  https://axon-ntfy.liara.run/axon-p1-test-to-sender
```

Anonymous subscribe must fail:

```sh
curl -o /tmp/axon_ntfy_anonymous_read.json -w '%{http_code}\n' \
  'https://axon-ntfy.liara.run/axon-p1-test-to-receiver/json?poll=1'
```

Expected result:

```text
401 or 403
```

## App Settings For Personal Open Relay

Sender device:

```text
Server URL: https://axon-ntfy.liara.run
Pair ID: p1-test
Pair secret: shared secret generated in Axon
Username: empty
Password: empty
Publish topic: axon-p1-test-to-receiver
Subscribe topic: axon-p1-test-to-sender
```

Receiver device:

```text
Server URL: https://axon-ntfy.liara.run
Pair ID: p1-test
Pair secret: same shared secret as Sender
Username: empty
Password: empty
Subscribe topic: axon-p1-test-to-receiver
Publish topic: axon-p1-test-to-sender
```

## App Settings With ntfy Auth

Sender device:

```text
Server URL: https://axon-ntfy.liara.run
Pair ID: p1-test
Pair secret: shared secret generated in Axon
Username: axon_sender
Password: sender password
Publish topic: axon-p1-test-to-receiver
Subscribe topic: axon-p1-test-to-sender
```

Receiver device:

```text
Server URL: https://axon-ntfy.liara.run
Pair ID: p1-test
Pair secret: same shared secret as Sender
Username: axon_receiver
Password: receiver password
Subscribe topic: axon-p1-test-to-receiver
Publish topic: axon-p1-test-to-sender
```

## Notes For App Implementation

- If username and password/token are both empty, Axon publishes and subscribes without an `Authorization` header.
- If username and password/token are both filled, Axon uses Basic Auth.
- If only one auth field is filled, Axon treats the settings as invalid.
- Later, access tokens are preferred over raw account passwords when auth is enabled.
- Axon relay payloads are encrypted with an app-level pair secret before publish.
- Use `json?poll=1` only for manual tests and cached-message checks.
- The app should use a live subscription endpoint, either:
  - `https://axon-ntfy.liara.run/<topic>/json`
  - `wss://axon-ntfy.liara.run/<topic>/ws`
- Add message IDs and deduplication in Axon's own relay envelope.
- Do not send full artwork payloads in the first ntfy milestone. ntfy text payload limits are much tighter than the current LAN WebSocket path.
- Do not log passwords, tokens, SMS bodies, or call details in verbose diagnostics.

## Security Follow-Up

Before using this with real personal data for more than local testing:

- Use a strong random pair ID.
- Keep the Axon pair secret private and identical on both paired devices.
- If ntfy auth is enabled, replace test passwords with strong unique passwords.
- If ntfy auth is enabled, prefer access tokens over account passwords in the Android app.
- If ntfy auth is enabled, keep `deny-all` enabled for anonymous/default access.
- If ntfy auth is enabled, confirm `/var/lib/ntfy/user.db` is persisted across deploys.

## Troubleshooting

### Auth Setup In Axon

- For personal open relay mode, leave both username and password/token empty.
- For auth mode, fill both username and password/token.
- If only one auth field is filled, Axon rejects the settings.
- In auth mode, confirm the username/password or token is correct for that role.
- In auth mode, confirm `ntfy access` shows read/write permissions for the exact topic names used by Axon.

### Wrong Pair ID

- Axon topic names are derived from topic prefix and pair ID.
- `p1-test` becomes `axon-p1-test-to-receiver` and `axon-p1-test-to-sender` when the prefix is `axon`.
- Both phones must use the same pair ID.
- Do not use dots in pair IDs if they will become topic names.

### Wrong Pair Secret

- The pair secret is not part of ntfy access control.
- Axon uses it to encrypt and authenticate relay payloads.
- If the pair secret differs, messages may arrive from ntfy but Axon rejects them during decrypt/authentication.
- Re-pair or copy the generated secret to both phones.

### Server Unreachable

- Test the base URL in a browser.
- Test publish/read with the `curl` commands above.
- Check reverse proxy, DNS, TLS certificate, and firewall settings.
- Check whether the Android device can reach the public URL on mobile data and Wi-Fi.

### Payload Too Large

- ntfy mode should carry compact encrypted text payloads.
- Axon omits large media artwork over ntfy.
- If metadata stops too, check diagnostics for publish failures or payload-size messages.

### Commands Do Not Return To Sender

- Receiver must have write access to `axon-<pairId>-to-sender`.
- Sender must have read access to `axon-<pairId>-to-sender`.
- Pair ID and pair secret must match on both phones.
- Sender must have Notification Access for media-session commands.
- Sender must have the required call permission and device support for call reject.
