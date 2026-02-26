# Shell & File Access Tools

## Local Shell (always available)

`local_run_shell` and `local_shell_session` use the native Android shell (`/system/bin/sh`). They are always available, even without the worker.

**Native mode capabilities:**
- Standard shell builtins and toybox/toolbox commands: `ls`, `cat`, `echo`, `mkdir`, `rm`, `cp`, `mv`, `grep`, `sed`, `awk`, `wc`, `sort`, `find`, `tar`, `gzip`, `ping`, `id`, `date`, `env`, `which`, `chmod`, `df`, `du`, `head`, `tail`, `tee`, `xargs`
- Working directory defaults to the app's user directory
- Environment variables and cwd persist within `local_shell_session` sessions

**Native mode limitations:**
- Only `sh` (not `bash`) — no bash-specific syntax (arrays, `[[ ]]`, process substitution)
- No package manager (`apt`, `pkg`) — only what Android ships
- No `python`, `pip`, `node`, `git`, `gcc`, or other development tools
- `local_shell_session` uses pipes (no PTY) — no ANSI escape codes, no terminal resize

## Worker Shell (requires worker)

`run_shell` and `shell_session` use the embedded worker (port 8776) for full Linux shell access (bash, packages, PTY). The worker starts automatically when needed.

Normal behavior: these tools automatically try to start/recover the worker before running the command.

If the worker is still unavailable, these tools return `worker_required`. Recovery order:
1. Retry the same tool call once (auto-recovery may still be in progress)
2. If it still fails, call `device_api(action="worker.restart")` and retry once
3. If it still fails, call `device_api(action="worker.status")` to diagnose state

## Arduino CLI DNS Workaround (worker module)

Android DNS behavior can break `arduino-cli` downloads in some environments.
Use the built-in worker module:

- `device_api(action="worker.arduino_proxy.enable", payload={"listen_port":38888,"downloads_ipv4":"104.18.11.21"})` — starts a local proxy (default `127.0.0.1:38888`), maps `downloads.arduino.cc` to a fixed IPv4 (default `104.18.11.21`), and runs `arduino-cli config set network.proxy http://127.0.0.1:<port>`. Both fields are optional.
- `device_api(action="worker.arduino_proxy.status")` — returns module/proxy status.

The worker installs a lightweight `arduino-cli` wrapper in `~/methings/bin` so normal shell/session usage is transparent.

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

## `run_shell(command, cwd?, timeout_ms?, env?)`

Execute a one-shot shell command via `bash -lc`. Returns separate stdout and stderr. Requires worker.

**Parameters:**
- `command` (string, required): The shell command to execute.
- `cwd` (string): Working directory. Defaults to `$HOME`.
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
run_shell(command="pip list 2>/dev/null | head -20")
run_shell(command="gcc -o hello hello.c && ./hello", cwd="~/projects", timeout_ms=120000)
```

## `local_shell_session(action, session_id?, command?, input?, cwd?, timeout?, env?)`

Manage persistent native Android shell sessions (pipe-based, no PTY). Sessions maintain state (environment variables, current directory) across multiple commands.

### Actions

Same actions as `shell_session` (start, exec, write, read, resize, kill, list), but:
- Uses `/system/bin/sh` instead of bash
- No PTY — `resize` is accepted but has no effect
- No ANSI escape codes in output

## `shell_session(action, session_id?, command?, input?, cwd?, rows?, cols?, timeout?, env?)`

Manage persistent PTY bash sessions. Unlike `run_shell`, sessions maintain state (environment variables, current directory, running processes) across multiple commands. Requires worker.

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
-> {session_id: "a1b2c3d4e5f6", output: "user@localhost:~/project$ "}

# Run commands
shell_session(action="exec", session_id="a1b2c3d4e5f6", command="export MY_VAR=hello")
shell_session(action="exec", session_id="a1b2c3d4e5f6", command="echo $MY_VAR")
-> {output: "echo $MY_VAR\r\nhello\r\nuser@localhost:~/project$ ", alive: true}

# Interactive: answer a prompt
shell_session(action="write", session_id="a1b2c3d4e5f6", input="y\n")

# Clean up
shell_session(action="kill", session_id="a1b2c3d4e5f6")
```

**Notes:**
- Sessions auto-expire after 30 minutes of inactivity.
- PTY output includes terminal control sequences (ANSI codes).
- For simple one-shot commands, prefer `run_shell` instead.

## When to Use Which Tool

| Need | Tool |
|------|------|
| Quick command, native Android shell | `local_run_shell` |
| Quick command, full Linux (worker) | `run_shell` |
| Interactive session, native shell | `local_shell_session` |
| Interactive session, worker PTY | `shell_session` |
| Read/write files in user root | `read_file` / `write_file` |
| Run JavaScript (no worker needed) | `run_js` |
| Make HTTP requests (no worker needed) | `run_curl` |
| Run Python specifically | `run_python` (or `run_shell`) |
