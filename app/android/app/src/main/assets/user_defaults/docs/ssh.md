# SSH

This document covers SSH-related APIs: SSHD state/config, PIN auth mode, and authorized key management.

## device_api actions

| Action | Method | Endpoint | Notes |
|--------|--------|----------|-------|
| `ssh.status` | GET | `/ssh/status` | No permission required |
| `ssh.config` | POST | `/ssh/config` | Enable/disable SSHD, update port/options |
| `ssh.keys.list` | GET | `/ssh/keys` | No permission required |
| `ssh.keys.add` | POST | `/ssh/keys/add` | Add one public key |
| `ssh.keys.delete` | POST | `/ssh/keys/delete` | Remove by fingerprint or key |
| `ssh.keys.policy.get` | GET | `/ssh/keys/policy` | No permission required |
| `ssh.keys.policy.set` | POST | `/ssh/keys/policy` | Update key policy flags |
| `ssh.pin.status` | GET | `/ssh/pin/status` | No permission required |
| `ssh.pin.start` | POST | `/ssh/pin/start` | Start temporary PIN auth mode |
| `ssh.pin.stop` | POST | `/ssh/pin/stop` | Stop PIN auth mode |
| `ssh.noauth.status` | GET | `/ssh/noauth/status` | No permission required |
| `ssh.noauth.start` | POST | `/ssh/noauth/start` | Start temporary notification/no-auth mode |
| `ssh.noauth.stop` | POST | `/ssh/noauth/stop` | Stop notification/no-auth mode |

## HTTP endpoints (direct)

### SSHD status/config

- `GET /ssh/status`
  - Returns enabled state, port, auth mode, and runtime status.
- `POST /ssh/config`
  - Typical body fields:
    - `enabled` (bool)
    - `port` (int)
    - `noauth_enabled` (bool, optional)

### Authorized keys

- `GET /ssh/keys`
  - Lists normalized keys.
  - The app may import/sync entries from `authorized_keys` if the file was changed externally.
- `POST /ssh/keys/add`
  - Body:
    - `key` (required): one OpenSSH public key line
    - `label` (optional)
- `POST /ssh/keys/delete`
  - Body can specify either:
    - `fingerprint`
    - `key`

### Key policy

- `GET /ssh/keys/policy`
  - Returns key-policy flags (for example `require_biometric`).
- `POST /ssh/keys/policy`
  - Body:
    - `require_biometric` (bool)

### PIN auth mode

- `GET /ssh/pin/status`
- `POST /ssh/pin/start`
- `POST /ssh/pin/stop`

### Optional no-auth mode endpoints

- `GET /ssh/noauth/status`
- `POST /ssh/noauth/start`
- `POST /ssh/noauth/stop`

## Notes

- SSH key mutation is permission-gated and may require biometric authentication depending on policy.
- Public keys should be passed as a single line (for example `ssh-ed25519 ... comment`).
- If a key exists in the file but is missing from UI state, call `ssh.keys.list` to force refresh/import.
