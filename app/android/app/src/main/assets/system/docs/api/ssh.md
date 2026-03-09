# SSH API

Permission: `device.ssh` (exec, scp)

## ssh.exec

Execute a command on a remote host.

**Params:**
- `host` (string, required): Hostname or IP
- `user` (string, optional): SSH username
- `port` (integer, optional): Default: 22
- `command` (string, required*): Shell command
- `argv` (string[], optional): Alternative to `command`
- `timeout_s` (number, optional): Default: 30 (2-300)
- `no_timeout` (boolean, optional): Disable timeout. Default: false
- `max_output_bytes` (integer, optional): Default: 65536 (4096-524288)
- `pty` (boolean, optional): Allocate PTY. Default: false
- `accept_new_host_key` (boolean, optional): Auto-accept unknown keys. Default: true

**Returns:** `status` (`ok`|`timeout`), `target` (`user@host`), `port`, `argv`, `exit_code`, `stdout`, `stderr`, `truncated`, `timed_out`

## ssh.scp

Transfer files via SCP.

**Params:**
- `host` (string, required): Hostname or IP
- `user` (string, optional), `port` (integer, optional, default 22)
- `direction` (string, required): `upload` | `download`
- `remote_path` (string, required): Path on remote host
- `local_path` (string, required): User-root relative path
- `recursive` (boolean, optional): For directories. Default: false
- `timeout_s` (number, optional): Default: 90 (2-600)
- `no_timeout` (boolean, optional): Default: false

**Returns:** `status` (`ok`|`timeout`), `direction`, `target`, `port`, `local_path`, `remote_path`, `exit_code`, `stdout`, `stderr`, `truncated`

**Notes:** `local_path` must stay within user root. Directories require `recursive: true`.

## ssh.ws.contract

Get interactive SSH WebSocket connection details.

**Returns:** `ws_path` (`/ws/ssh/interactive`), `query` (object: `host`, `user`, `port`)

### WebSocket

Connect to `/ws/ssh/interactive?host=...&user=...` for an interactive terminal session.
