# Shell & File Access Tools

## Local Shell (always available)

`local_run_shell` and `local_shell_session` use the native Android shell (`/system/bin/sh`). They are always available, even without Termux.

**Native mode capabilities:**
- Standard shell builtins and toybox/toolbox commands: `ls`, `cat`, `echo`, `mkdir`, `rm`, `cp`, `mv`, `grep`, `sed`, `awk`, `wc`, `sort`, `find`, `tar`, `gzip`, `ping`, `id`, `date`, `env`, `which`, `chmod`, `df`, `du`, `head`, `tail`, `tee`, `xargs`
- Working directory defaults to the app's user directory
- Environment variables and cwd persist within `local_shell_session` sessions

**Native mode limitations:**
- Only `sh` (not `bash`) — no bash-specific syntax (arrays, `[[ ]]`, process substitution)
- No package manager (`apt`, `pkg`) — only what Android ships
- No `python`, `pip`, `node`, `git`, `gcc`, or other development tools
- `local_shell_session` uses pipes (no PTY) — no ANSI escape codes, no terminal resize
- Cannot access Termux files under `/data/data/com.termux/`

## Termux Shell (requires Termux)

`termux_run_shell` and `termux_shell_session` use the Termux worker (port 8776) for full Linux shell access (bash, packages, PTY). The worker starts automatically when needed.

If the Termux worker is unavailable, these tools return `termux_required` error. To recover:
1. Call `device_api(action="termux.status")` to check state
2. Call `device_api(action="termux.restart")` to restart the worker

**`termux_fs` remains Termux-only** — it accesses Termux's home directory which doesn't exist without Termux.

**If Termux is needed but not set up**, call `device_api("termux.show_setup")` to open the setup wizard in the UI. The user can then install and bootstrap Termux without leaving the app.

---

## `local_run_shell(command, cwd?, timeout_ms?, env?)`

Execute a one-shot command in the native Android shell (`/system/bin/sh`). Returns separate stdout and stderr.

**Parameters:**
- `command` (string, required): The shell command to execute.
- `cwd` (string): Working directory. Defaults to the app's user directory.
- `timeout_ms` (integer): Execution timeout in ms. Default 60000, max 300000.
- `env` (object): Extra environment variables to set.

**Returns:**
```json
{
  "status": "ok",
  "exit_code": 0,
  "stdout": "...",
  "stderr": "..."
}
```

**Examples:**
```
local_run_shell(command="ls -la")
local_run_shell(command="id")
local_run_shell(command="cat /proc/cpuinfo | head -20")
```

## `termux_run_shell(command, cwd?, timeout_ms?, env?)`

Execute a one-shot shell command in Termux via `bash -lc`. Returns separate stdout and stderr. Requires Termux worker.

**Parameters:**
- `command` (string, required): The shell command to execute.
- `cwd` (string): Working directory. Defaults to Termux `$HOME`.
- `timeout_ms` (integer): Execution timeout in ms. Default 60000, max 300000.
- `env` (object): Extra environment variables to set.

**Returns:**
```json
{
  "status": "ok",
  "exit_code": 0,
  "stdout": "...",
  "stderr": "..."
}
```

On timeout:
```json
{
  "status": "timeout",
  "exit_code": -1,
  "stdout": "",
  "stderr": "Command timed out after 60s"
}
```

**Examples:**
```
termux_run_shell(command="ls -la ~/methings")
termux_run_shell(command="python3 -c \"import sys; print(sys.version)\"")
termux_run_shell(command="apt list --installed 2>/dev/null | head -20")
termux_run_shell(command="gcc -o hello hello.c && ./hello", cwd="~/projects", timeout_ms=120000)
```

## `local_shell_session(action, session_id?, command?, input?, cwd?, timeout?, env?)`

Manage persistent native Android shell sessions (pipe-based, no PTY). Sessions maintain state (environment variables, current directory) across multiple commands.

### Actions

Same actions as `termux_shell_session` (start, exec, write, read, resize, kill, list), but:
- Uses `/system/bin/sh` instead of Termux bash
- No PTY — `resize` is accepted but has no effect
- No ANSI escape codes in output

## `termux_shell_session(action, session_id?, command?, input?, cwd?, rows?, cols?, timeout?, env?)`

Manage persistent Termux PTY bash sessions. Unlike `termux_run_shell`, sessions maintain state (environment variables, current directory, running processes) across multiple commands. Requires Termux worker.

### Actions

#### `start` — Create a new session
- `cwd` (string): Initial working directory.
- `rows`, `cols` (integer): Terminal size (default 24x80).
- `env` (object): Extra environment variables.
- Returns: `{session_id, output}` (output contains the initial shell prompt).

#### `exec` — Send command and read output
- `session_id` (string, required): Session ID from `start`.
- `command` (string, required): Command to execute.
- `timeout` (integer): Read timeout in seconds (default 30, max 300).
- Returns: `{output, alive}`.

#### `write` — Raw stdin write
- `session_id` (string, required).
- `input` (string, required): Raw data to write to stdin (e.g., `"y\n"` for confirmation prompts).
- Returns: `{ok}`.

#### `read` — Read buffered output
- `session_id` (string, required).
- Returns: `{output, alive}` with any output since last read.

#### `resize` — Resize terminal
- `session_id` (string, required).
- `rows`, `cols` (integer).

#### `kill` — Terminate session
- `session_id` (string, required).

#### `list` — List active sessions
- Returns: `{sessions: [{session_id, alive, idle_seconds, uptime_seconds}]}`.

**Example flow:**
```
# Start a session
termux_shell_session(action="start", cwd="~/project")
-> {session_id: "a1b2c3d4e5f6", output: "user@localhost:~/project$ "}

# Run commands
termux_shell_session(action="exec", session_id="a1b2c3d4e5f6", command="export MY_VAR=hello")
termux_shell_session(action="exec", session_id="a1b2c3d4e5f6", command="echo $MY_VAR")
-> {output: "echo $MY_VAR\r\nhello\r\nuser@localhost:~/project$ ", alive: true}

# Interactive: answer a prompt
termux_shell_session(action="write", session_id="a1b2c3d4e5f6", input="y\n")

# Clean up
termux_shell_session(action="kill", session_id="a1b2c3d4e5f6")
```

**Notes:**
- Sessions auto-expire after 30 minutes of inactivity.
- PTY output includes terminal control sequences (ANSI codes).
- For simple one-shot commands, prefer `termux_run_shell` instead.

## `termux_fs(action, path, content?, encoding?, max_bytes?, offset?, show_hidden?, parents?, recursive?)`

Access files in the Termux filesystem (under `$HOME`). Unlike the built-in filesystem tools which operate on the app's user root, this tool accesses Termux's own file tree.

### Actions

#### `read` — Read file content
- `path` (string, required): File path (absolute or relative to `$HOME`).
- `max_bytes` (integer): Max bytes to read (default 1MB, max 10MB).
- `offset` (integer): Byte offset to start reading from.
- Returns: `{content, encoding, size, offset, read_bytes, truncated}`.
  - `encoding` is `"utf-8"` for text files, `"base64"` for binary.

#### `write` — Write file content
- `path` (string, required).
- `content` (string, required): File content.
- `encoding` (string): `"utf-8"` (default) or `"base64"`.
- Returns: `{path, size}`.

#### `list` — List directory
- `path` (string, required): Directory path.
- `show_hidden` (boolean): Include dotfiles (default false).
- Returns: `{items: [{name, is_dir, size, mtime}]}`.

#### `stat` — File metadata
- `path` (string, required).
- Returns: `{is_file, is_dir, is_link, size, mtime, atime, mode}`.

#### `mkdir` — Create directory
- `path` (string, required).
- `parents` (boolean): Create parent directories (default true).

#### `delete` — Delete file or directory
- `path` (string, required).
- `recursive` (boolean): Delete non-empty directories (default false).

**Security:** All paths are resolved and validated to be under Termux `$HOME`.

**Examples:**
```
termux_fs(action="list", path="~/methings/server", show_hidden=true)
termux_fs(action="read", path="~/.bashrc")
termux_fs(action="write", path="~/script.py", content="print('hello')")
termux_fs(action="stat", path="~/methings")
```

## When to Use Which Tool

| Need | Tool |
|------|------|
| Quick command, native Android shell | `local_run_shell` |
| Quick command, full Linux (Termux) | `termux_run_shell` |
| Interactive session, native shell | `local_shell_session` |
| Interactive session, Termux PTY | `termux_shell_session` |
| Read/write files in Termux home | `termux_fs` |
| Read/write files in app user root | `read_file` / `write_file` |
| Run JavaScript (no Termux needed) | `run_js` |
| Make HTTP requests (no Termux needed) | `run_curl` |
| Run Python specifically | `run_python` (or `termux_run_shell`) |
