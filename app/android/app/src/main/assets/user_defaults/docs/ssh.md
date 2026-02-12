# SSH (Remote Host Client)

This document covers SSH client APIs for connecting from the app to other hosts.

## device_api actions

| Action | Method | Endpoint | Notes |
|--------|--------|----------|-------|
| `ssh.exec` | POST | `/ssh/exec` | One-shot remote command execution |
| `ssh.scp` | POST | `/ssh/scp` | Upload/download files via SCP |
| `ssh.ws.contract` | GET | `/ssh/ws/contract` | Returns interactive WebSocket path/contract |

## HTTP endpoints (direct)

- `POST /ssh/exec`
  - Runs one remote command and returns stdout/stderr/exit code.
- `POST /ssh/scp`
  - Transfers one file (`upload` or `download`).
- `GET /ssh/ws/contract`
  - Returns the contract for interactive SSH over WebSocket (`/ws/ssh/interactive`).

## Notes

- These are outbound client operations (app -> remote host), not SSHD management.
- For on-device SSH server config/keys/auth mode, see [sshd.md](sshd.md).
