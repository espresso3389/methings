# me.sync v4 Streaming Protocol (Draft)

Status: Draft  
Last updated: 2026-02-15

## 1. Goal

`me.sync v4` is a streaming import/export protocol for reliable device-to-device migration.

- Avoid monolithic ZIP-only transfer as the primary data plane.
- Transfer file-by-file with per-file integrity checks.
- Keep current atomic apply model (staging -> swap/commit).
- Support retry of failed files at the end of bulk transfer.

## 2. Non-goals (first version)

- Delta sync across historical snapshots.
- Multi-writer sync conflict resolution.
- Cross-session dedup store.

## 3. Core Model

### 3.1 Transfer session

A session has:

- `session_id`
- `ticket_id` / auth context
- `manifest_hash`
- `created_at`, `expires_at`
- receiver state:
  - `pending_paths`
  - `ok_paths`
  - `failed_paths` with reason

### 3.2 Manifest

Sender generates a deterministic manifest before file streaming.

Each entry contains:

- `path` (relative, normalized)
- `kind` (`file`, `dir`, `symlink` future)
- `size`
- `sha256`
- `mode` (optional)
- `mtime_ms` (optional)

Manifest includes:

- `schema_version=4`
- `entry_count`
- `total_bytes`
- `manifest_sha256`

## 4. Transport framing

v4 payload is logical stream frames (over Nearby stream, later reusable for LAN/HTTP2/WebSocket).

Frame types:

- `manifest_begin`
- `manifest_chunk`
- `manifest_end`
- `file_begin` (path, size, sha256)
- `file_chunk` (bytes)
- `file_end`
- `phase_end` (bulk complete)
- `retry_request` (failed paths)
- `retry_begin` / `retry_end`
- `finalize_request`
- `finalize_result`
- `abort`

## 5. Receiver pipeline

### Phase A: Prepare staging

- Create isolated staging root, e.g. `cache/me_sync_v4/<session_id>/staging`.
- No live data mutation.

### Phase B: Bulk receive

- Receive all manifest entries in sender order.
- For each file:
  - stream to temp file in staging
  - calculate SHA-256 while writing
  - compare to declared hash
  - mark `ok` or `failed`

### Phase C: Retry failed (end-of-bulk)

- Receiver sends `retry_request` with `failed_paths`.
- Sender retransmits only failed entries.
- Repeat until `failed_paths` empty or `max_retry` reached.

### Phase D: Finalize

- Verify:
  - all required entries are `ok`
  - no unknown files
  - manifest hash and counts match
- If valid: apply atomically using current me.sync swap/rollback path.
- If invalid: abort, keep current data unchanged.

## 6. Retry policy

Default:

- `max_retry_rounds = 3`
- Retry only paths in `failed_paths`.
- Failure reasons (normalized):
  - `io_error`
  - `hash_mismatch`
  - `size_mismatch`
  - `timeout`
  - `protocol_error`

Result semantics:

- Success only when all required files pass verification.
- Partial success is never committed.

## 7. Integrity and safety

- Per-file hash verification is mandatory.
- Manifest hash verification is mandatory.
- Path traversal protection is mandatory (`..`, absolute path reject).
- Final apply uses staging -> swap + rollback (already adopted in v3 import path).

## 8. Resume behavior

If stream disconnects:

- Receiver keeps session state until TTL.
- On reconnect with same `session_id`, sender requests `failed + pending` list.
- Sender resumes from remaining files only.

If resume token/session is invalid:

- Start a new v4 session.

## 9. API surface (proposed)

Names are draft; align with existing `/me/sync/v3/*` namespace during implementation.

- `POST /me/sync/v4/session/create` (exporter)
- `POST /me/sync/v4/session/start` (importer)
- `GET /me/sync/v4/session/status?session_id=...`
- `POST /me/sync/v4/session/abort`

Nearby transport handlers:

- `receiveFrame(sessionId, frame)`
- `sendFrame(sessionId, frame)`

## 10. UI progress model (proposed)

Import phases:

- `nearby_connecting`
- `manifest_receiving`
- `bulk_transferring`
- `retrying_failed`
- `final_verifying`
- `applying`
- `restarting_runtime`
- `completed` / `failed`

Progress fields:

- `files_ok`
- `files_failed`
- `files_total`
- `bytes_received`
- `bytes_total`
- `transfer_bps`
- `retry_round`
- `detail`

## 11. Compatibility plan

Rollout:

1. Keep v3 intact.
2. Add v4 as opt-in path for Nearby transport first.
3. Fallback to v3 on peer capability mismatch.
4. Promote v4 after regression pass.

Capability flag example:

- `caps: { "me_sync_v4_streaming": true }`

## 12. Open questions

- Whether to keep optional archive mode as emergency fallback.
- Retry fairness for very large single-file failures.
- Optimal chunk size and backpressure policy for Nearby transport.
- Whether manifest should include optional compression hints per file.
