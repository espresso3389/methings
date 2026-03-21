# RTDB Signaling Setup

## Status

Client support for RTDB signaling is wired in, but production-hardening is not complete yet.

What is ready:
- Android client can use `signaling_transport = "rtdb"`
- RTDB database URL and root path can be supplied at build time
- a minimal rules template exists at `firebase/rtdb.rules.json`

What is not ready yet:
- per-device Firebase Auth
- strict RTDB rules tied to provisioned device identity
- server-issued Firebase custom tokens
- production rollout plan for authenticated rules (`docs/rtdb_auth_plan.md`)

## Build-Time Config

Add to `.local_config/local.env`:

```env
GOOGLE_WEB_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
ME_ME_RTDB_DATABASE_URL=https://<your-db>.firebasedatabase.app
ME_ME_RTDB_ROOT_PATH=me_me_signaling
```

If `ME_ME_RTDB_DATABASE_URL` is set at build time, the client defaults `me.me.p2p.config.signaling_transport` to `rtdb`.

## Temporary Rules

`firebase/rtdb.rules.json` is a bootstrap template only.

It is intentionally minimal so early device testing can start, but it is not appropriate for long-term production use because it does not bind reads/writes to authenticated device identity.

Authenticated production template:
- `firebase/rtdb.rules.authenticated.json`

Deploy example:

```bash
firebase deploy --only database
```

## Recommended Production Follow-Up

1. Add Firebase Auth for device identity.
2. Issue Firebase custom tokens from the gateway after provisioning.
3. Tighten RTDB rules so a device can:
   - write only its own `presence/{deviceId}`
   - read only its own `inbox/{deviceId}`
   - write to a peer inbox only when authorized by a valid session model
4. Add cleanup for stale inbox messages and presence.

## Suggested First Test

1. Enable RTDB in Firebase for the existing `me-things-service` project.
2. Set `ME_ME_RTDB_DATABASE_URL` in `.local_config/local.env`.
3. Build and install the Android app.
4. Confirm `GET /me/me/p2p/config` shows:
   - `signaling_transport = "rtdb"`
   - `rtdb_database_url` populated
5. Test one 1:1 P2P session between two devices.
