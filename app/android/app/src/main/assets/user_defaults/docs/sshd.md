# SSHD (On-device SSH Server)

This document covers SSHD APIs: daemon status/config, PIN mode, and authorized key management.

## device_api actions

| Action | Method | Endpoint | Notes |
|--------|--------|----------|-------|
| `sshd.status` | GET | `/sshd/status` | No permission required |
| `sshd.config` | POST | `/sshd/config` | Enable/disable SSHD, update port/options |
| `sshd.keys.list` | GET | `/sshd/keys` | No permission required |
| `sshd.keys.add` | POST | `/sshd/keys/add` | Add one public key |
| `sshd.keys.delete` | POST | `/sshd/keys/delete` | Remove by fingerprint or key |
| `sshd.keys.policy.get` | GET | `/sshd/keys/policy` | No permission required |
| `sshd.keys.policy.set` | POST | `/sshd/keys/policy` | Update key policy flags |
| `sshd.pin.status` | GET | `/sshd/pin/status` | No permission required |
| `sshd.pin.start` | POST | `/sshd/pin/start` | Start temporary PIN auth mode |
| `sshd.pin.stop` | POST | `/sshd/pin/stop` | Stop PIN auth mode |
| `sshd.noauth.status` | GET | `/sshd/noauth/status` | No permission required |
| `sshd.noauth.start` | POST | `/sshd/noauth/start` | Start temporary notification/no-auth mode |
| `sshd.noauth.stop` | POST | `/sshd/noauth/stop` | Stop notification/no-auth mode |

## HTTP endpoints (direct)

### SSHD status/config

- `GET /sshd/status`
  - Returns enabled state, port, auth mode, and runtime status.
- `POST /sshd/config`
  - Typical body fields:
    - `enabled` (bool)
    - `port` (int)
    - `noauth_enabled` (bool, optional)

### Authorized keys

- `GET /sshd/keys`
  - Lists normalized keys.
  - The app may import/sync entries from `authorized_keys` if the file was changed externally.
- `POST /sshd/keys/add`
  - Body:
    - `key` (required): one OpenSSH public key line
    - `label` (optional)
- `POST /sshd/keys/delete`
  - Body can specify either:
    - `fingerprint`
    - `key`

### Key policy

- `GET /sshd/keys/policy`
  - Returns key-policy flags (for example `require_biometric`).
- `POST /sshd/keys/policy`
  - Body:
    - `require_biometric` (bool)

### PIN auth mode

- `GET /sshd/pin/status`
- `POST /sshd/pin/start`
- `POST /sshd/pin/stop`

### Optional no-auth mode endpoints

- `GET /sshd/noauth/status`
- `POST /sshd/noauth/start`
- `POST /sshd/noauth/stop`

## Notes

- SSH key mutation is permission-gated and may require biometric authentication depending on policy.
- Public keys should be passed as a single line (for example `ssh-ed25519 ... comment`).
- If a key exists in the file but is missing from UI state, call `sshd.keys.list` to force refresh/import.
