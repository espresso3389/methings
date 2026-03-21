# RTDB Auth Plan

## Target Model

Use Firebase Auth custom tokens where:
- `auth.uid == device_id`
- each installed device authenticates independently
- RTDB rules key off `auth.uid`

This is the simplest model that matches the current me.me design:
- device identity is already stable
- signaling is device-to-device
- inbox paths are device-scoped

## Why Not Use Anonymous Auth

Anonymous auth is easy to bootstrap but it is not aligned with the existing trust model.

Problems:
- Firebase UID is not the same as the me.me device ID
- rules become indirect and harder to reason about
- device reinstall / recovery semantics get messy

## Why Not Reuse Google Sign-In Directly

Provisioning is account-level, but signaling authorization is device-level.

Using account auth directly would blur:
- who owns the account
- which physical installation may read a device inbox

The current signaling data model is much cleaner if each device authenticates as itself.

## Recommended Flow

1. Device already has a stable `device_id`.
2. During provisioning or P2P setup, the gateway verifies device identity.
3. Gateway issues a Firebase custom token whose `uid` is the `device_id`.
4. Client signs in to Firebase with that custom token.
5. RTDB rules allow:
   - read/write to `presence/{device_id}`
   - read from `inbox/{device_id}`
   - write to peer inbox paths as authenticated sender

## Gateway Changes Needed

The gateway should eventually provide a new endpoint such as:

```text
POST /p2p/firebase_token
```

Request:
- authenticated as current device

Response:
- `{ firebase_custom_token, rtdb_database_url, rtdb_root_path }`

The token should be short-lived and issued only to a verified device.

## Client Changes Needed

The Android app should:
- request the Firebase custom token
- sign in with Firebase Auth before enabling RTDB signaling
- refresh/re-authenticate when needed

At that point, `me.me.p2p.config` can safely default to:
- `signaling_transport = "rtdb"`
- populated `rtdb_database_url`
- no WebSocket signaling dependency

## Rules Template

Authenticated rules template:
- `firebase/rtdb.rules.authenticated.json`

Bootstrap / insecure test rules:
- `firebase/rtdb.rules.json`

The bootstrap rules are for local testing only and should not be treated as production-ready.

## Production Cutover Checklist

1. Add Firebase Auth dependency and sign-in flow for custom tokens.
2. Add gateway endpoint for issuing Firebase custom tokens.
3. Deploy `firebase/rtdb.rules.authenticated.json`.
4. Verify two-device RTDB signaling with auth enabled.
5. Remove Cloud Run signaling dependency from default config.
6. Retire the dedicated signaling Cloud Run service.
