# Shell & File Access Tools

## Native Fallback

`run_shell` and `shell_session` work **without Termux**. When the Termux worker is unreachable, they automatically fall back to native Android shell execution using `/system/bin/sh` via `ProcessBuilder`.

**Native mode capabilities:**
- Standard shell builtins and toybox/toolbox commands: `ls`, `cat`, `echo`, `mkdir`, `rm`, `cp`, `mv`, `grep`, `sed`, `awk`, `wc`, `sort`, `find`, `tar`, `gzip`, `ping`, `id`, `date`, `env`, `which`, `chmod`, `df`, `du`, `head`, `tail`, `tee`, `xargs`
- Working directory defaults to the app's user directory
- Environment variables and cwd persist within `shell_session` sessions

**Native mode limitations:**
- Only `sh` (not `bash`) — no bash-specific syntax (arrays, `[[ ]]`, process substitution)
- No package manager (`apt`, `pkg`) — only what Android ships
- No `python`, `pip`, `node`, `git`, `gcc`, or other development tools
- `shell_session` uses pipes (no PTY) — no ANSI escape codes, no terminal resize
- Response includes `"backend": "native"` so you can detect which mode was used

**`termux_fs` remains Termux-only** — it accesses Termux's home directory which doesn't exist without Termux.

---

These tools use Termux when available (full bash, packages, PTY). The worker starts automatically when needed.

## `run_shell(command, cwd?, timeout_ms?, env?)`

Execute a one-shot shell command in Termux via `bash -lc`. Returns separate stdout and stderr.

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
run_shell(command="ls -la ~/methings")
run_shell(command="python3 -c \"import sys; print(sys.version)\"")
run_shell(command="apt list --installed 2>/dev/null | head -20")
run_shell(command="gcc -o hello hello.c && ./hello", cwd="~/projects", timeout_ms=120000)
```

## `shell_session(action, session_id?, command?, input?, cwd?, rows?, cols?, timeout?, env?)`

Manage persistent PTY bash sessions. Unlike `run_shell`, sessions maintain state (environment variables, current directory, running processes) across multiple commands.

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
shell_session(action="start", cwd="~/project")
→ {session_id: "a1b2c3d4e5f6", output: "user@localhost:~/project$ "}

# Run commands
shell_session(action="exec", session_id="a1b2c3d4e5f6", command="export MY_VAR=hello")
shell_session(action="exec", session_id="a1b2c3d4e5f6", command="echo $MY_VAR")
→ {output: "echo $MY_VAR\r\nhello\r\nuser@localhost:~/project$ ", alive: true}

# Interactive: answer a prompt
shell_session(action="write", session_id="a1b2c3d4e5f6", input="y\n")

# Clean up
shell_session(action="kill", session_id="a1b2c3d4e5f6")
```

**Notes:**
- Sessions auto-expire after 30 minutes of inactivity.
- PTY output includes terminal control sequences (ANSI codes).
- For simple one-shot commands, prefer `run_shell` instead.

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
| Run a quick command, get stdout/stderr | `run_shell` |
| Interactive session, maintain state | `shell_session` |
| Read/write files in Termux home | `termux_fs` |
| Read/write files in app user root | `read_file` / `write_file` |
| Run JavaScript (no Termux needed) | `run_js` |
| Make HTTP requests (no Termux needed) | `run_curl` |
| Run Python specifically | `run_python` (or `run_shell`) |
