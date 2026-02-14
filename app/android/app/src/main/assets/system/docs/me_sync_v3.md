# me.sync v3 Concept (QR-Paired Ad-hoc Transfer)

This document defines a dedicated nearby/ad-hoc transfer design for `me.sync`.

## Goals

- Device-to-device transfer without requiring both devices on the same LAN.
- Low-friction UX: QR pair once, then transfer proceeds automatically.
- Keep existing `me.sync` export/import semantics (payload format, wipe default, expiry).
- Preserve explicit user consent and auditability for sensitive actions.

## Non-goals

- Continuous always-on background sync.
- Silent pairing without explicit user action.
- Replacing existing LAN-based import/export path immediately.

## High-level Design

`me.sync v3` adds an ad-hoc transport layer under the existing export/import flow:

1. Source prepares export package (`transfer_id`, expiry, metadata).
2. Source displays a one-time QR "pair ticket".
3. Target scans QR and starts a secure pairing handshake.
4. Devices establish a nearby session (Nearby Connections preferred).
5. Source streams package bytes over the nearby channel.
6. Target verifies integrity, imports package, applies restart/reload behavior.

If nearby session cannot be established, importer can still fallback to existing LAN transfer.

## Transport Strategy

- Primary: Android Nearby Connections (P2P, Bluetooth/Wi-Fi aware under the hood).
- Fallback: Existing SSH/SCP or HTTP LAN path from current `me.sync`.
- Selection: attempt nearby first when QR ticket indicates nearby capability; fallback on timeout/failure.

Note: Nearby Connections does not expose a generic IP socket. The app sends/receives framed payload bytes via Nearby APIs.

## QR Pair Ticket

Use compact URI wrapper:

- `me.things:me.sync.v3:<base64url-json>`

Recommended payload fields:

- `v`: protocol version (`3`)
- `transfer_id`: prepared export ID
- `session_nonce`: random one-time nonce
- `pair_code`: short verifier (human-check optional)
- `expires_at`: absolute UTC timestamp
- `source_name`: user-visible source device label
- `caps`: capability flags (`nearby`, `lan_fallback`)
- `sig`: source signature over payload (optional phase 2 hardening)

Ticket lifetime should be short (for example 5 minutes).

## Security Model

- Pair ticket is single-use; reject replay by `(transfer_id, session_nonce)` cache.
- Export package remains time-limited and stored in app-private area.
- Mutual proof:
  - Target proves possession of scanned ticket (nonce + derived key).
  - Source confirms same session key before sending data.
- Integrity:
  - Source sends package digest (SHA-256) signed/committed in handshake transcript.
  - Target verifies digest before import.
- Authorization:
  - Import still requires explicit local consent according to permission broker policy.
- Audit:
  - Log pair start/accept/reject, transfer start/finish/failure, import start/result.

## Pairing and Handshake

Use ephemeral ECDH to derive a session key:

1. Target scans ticket and sends `pair_init(session_nonce, target_ephemeral_pub)`.
2. Source validates ticket and replies `pair_ack(source_ephemeral_pub, transcript_mac)`.
3. Both derive `session_key = HKDF(ECDH, session_nonce, transfer_id)`.
4. Target sends `ready` with MAC; source begins encrypted chunk stream.

If handshake fails MAC/expiry checks, both sides abort and invalidate ticket.

## Data Plane

- Chunked framed transfer over nearby channel:
  - frame types: `meta`, `chunk`, `eof`, `abort`, `ack`
  - each chunk includes monotonic index + MAC
- Resume behavior (phase 2):
  - optional checkpoint (`last_chunk_index`)
  - phase 1 can start as non-resumable for simplicity.

## UX Flow

Source device:
- `Prepare and Show QR`
- waits for target connection
- shows progress and final status

Target device:
- `Scan QR`
- confirms device name + transfer summary
- pairing and transfer auto-starts
- import confirmation (wipe default on)

Failure UX:
- show explicit reason (`expired ticket`, `pair failed`, `integrity mismatch`, `nearby unavailable`)
- offer LAN fallback when available

## API Shape (Proposed)

New high-level actions (names indicative):

- `me.sync.v3.ticket.create`
- `me.sync.v3.pair.accept`
- `me.sync.v3.transfer.status`
- `me.sync.v3.transfer.cancel`
- `me.sync.v3.import.apply`

Existing `prepare_export` / `import` can remain and be reused internally.

## Implementation Phases

Phase 1:
- QR ticket + nearby handshake
- one-shot transfer (no resume)
- integrity check + import
- audit events

Phase 2:
- resume support
- optional signature-backed ticket verification
- richer diagnostics/telemetry

## Open Questions

- Should pair acceptance on target be one-tap or fully automatic after scan?
- Should "migration mode" (`include_identity=true`) require extra confirmation text?
- Keep nearby endpoint alive for multiple transfers in one session, or one transfer per ticket only?
