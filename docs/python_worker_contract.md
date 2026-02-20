# Termux Worker Contract (Optional)

The AI agent runs natively in the app process. Termux is **optional** and provides a general-purpose Linux environment for agentic shell tasks.

The app owns the entire control plane: agent runtime, UI hosting, permissions, SSHD control, credential vault, and all `/brain/*` routes.

## Responsibilities (Termux — when installed)
- Provide a Linux environment for shell command execution on behalf of the agent.
- Host SSH server (OpenSSH via Termux).
- Run user/agent scripts and package management (`pip install`, etc.).

## Not Responsibilities (Termux)
- No agent execution or tool orchestration (handled by the built-in AgentRuntime).
- No UI hosting (served by the app).
- No permissions or access control decisions.
- No SSHD management decisions (the app controls start/stop).
- No credential storage or key management.
- No always-on lifecycle guarantees.

## Shell Exec Endpoint (Worker)
When the Termux worker is running (`127.0.0.1:8776`):
- `POST /shell/exec` — execute allowed commands (`python`, `pip`)

The agent's ToolExecutor delegates `run_python`/`run_pip` to this endpoint via HTTP loopback.

Note: `run_js` (QuickJS) and `run_curl` (native HTTP) are handled in-process by the app and do not use the Termux worker.

## Lifecycle
- Worker is **not** auto-started at boot.
- The app starts the worker on-demand when the agent invokes a shell tool.
- Worker can crash without affecting core agent functionality — only shell tool calls will fail.
- `TermuxWorkerManager.ensureWorkerForShell()` starts the worker if not already running.

## Data Access Rules
- Sensitive data (credentials) must be accessed **through app APIs**, not directly from Termux processes.
- Termux receives only the minimum necessary inputs per request.
