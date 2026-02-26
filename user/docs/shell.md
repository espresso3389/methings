# Shell & File Access Tools

## Shell Environment

`run_shell` and `shell_session` use the embedded Linux environment with full bash, packages, and PTY support. They run directly in-process — no external worker needed.

**Capabilities:**
- Full bash shell with all builtins
- Python, pip, node, git, gcc, and other development tools
- Full PTY support with ANSI escape codes and terminal resize
- Package installation via pip
- Working directory defaults to `$HOME`

## `run_shell(command, cwd?, timeout_ms?, env?)`

Execute a one-shot shell command via `bash -lc`. Returns separate stdout and stderr.

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
| Quick command, full Linux shell | `run_shell` |
| Interactive session, PTY | `shell_session` |
| Read/write files in user root | `read_file` / `write_file` |
| Run JavaScript (always available) | `run_js` |
| Make HTTP requests (always available) | `run_curl` |
| Run Python specifically | `run_python` (or `run_shell`) |
