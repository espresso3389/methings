# Shell API

Execute shell commands on the device. See also `$sys/docs/shell.md` for the shell environment reference.

## shell.exec

Execute a shell command.

**Params:**
- `command` (string, required): Shell command to execute.
- `timeout_ms` (integer, optional): Command timeout in milliseconds. Default: 30000

**Returns:**
- `stdout` (string): Standard output.
- `stderr` (string): Standard error.
- `exit_code` (integer): Process exit code.
