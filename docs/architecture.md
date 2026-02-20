# Architecture: Native Agent Runtime + Optional Termux

## Core Principles
- The app owns the entire control plane: UI server, agent runtime, storage, permissions, SSH.
- The AI agent runs natively in the app process (no external dependencies for core functionality).
- Termux is **optional** — provides a general-purpose Linux environment for agentic shell tasks.
- Local UI and health endpoints are served by the app.

## Process Model
```
Android App
  ├─ Local HTTP Server (127.0.0.1:33389)  [always up]
  ├─ AgentRuntime                          [started on demand]
  │   ├─ LlmClient (SSE streaming to OpenAI / Anthropic APIs)
  │   ├─ ToolExecutor (filesystem, device API, journal, memory)
  │   └─ DeviceToolBridge (calls device handlers via loopback)
  ├─ UI assets served from files/www
  ├─ Storage + permissions (Room + Keystore AES-GCM)
  └─ Termux (optional, general-purpose Linux environment)
```

## Local Service (always up)
- Serves `/health` and static UI at `/ui/*` on `127.0.0.1:33389`.
- Provides `/termux/*` endpoints to start/stop/restart the Termux worker.
- Hosts SSHD control and credential vault endpoints.
- SSH key management requires one-time permission; biometric prompt can be enforced via `/ssh/keys/policy`.
- PIN auth is supported via a short-lived PIN file.
- SSH provides transport only (via Termux OpenSSH); no SSH CLI tools are bundled.
- Permissions + SSH key storage use a plain Room DB; credentials are encrypted with Android Keystore (AES-GCM) and stored as ciphertext in the same DB.

## Agent Runtime
- Implemented in the `service.agent` package.
- **AgentRuntime**: Queue-based loop with interrupt support, processes chat messages and events.
- **LlmClient**: SSE streaming for both OpenAI Responses API and Anthropic Messages API.
- **ToolExecutor**: Dispatches tool calls (filesystem, device API, journal, memory, shell, web search, cloud requests).
- **DeviceToolBridge**: Executes device API actions via HTTP loopback to LocalHttpServer handlers.
- **AgentStorage**: Chat message persistence in `agent/agent.db` (SQLite). Migrates old messages from legacy `protected/app.db` on first run.
- **JournalStore**: JSONL file-based session journal.
- All `/brain/*` routes are handled directly by the app — no external process proxy.

## Termux (optional, on-demand)
- Provides a general-purpose Linux environment for agentic tasks (shell commands, package management, SSH).
- Started on-demand when the agent invokes shell tools (`run_python`, `run_pip`, `run_curl`).
- Not required for agent startup or core functionality.
- Worker health endpoint: `127.0.0.1:8776` (when running).
- Can crash without affecting the agent — only shell tool calls will fail.

## Data Storage
| Data | Location | Format |
|------|----------|--------|
| Chat messages | `files/agent/agent.db` | SQLite (AgentStorage) |
| Brain config | SharedPreferences (`brain_config`) | Key-value |
| API keys | SharedPreferences (per-provider slots) | Key-value |
| Journal | `files/user/journal/` | JSONL files |
| Memory | `files/user/MEMORY.md` | Markdown |
| Permissions/credentials | Room DB + Android Keystore | Encrypted |

## Notes
- Credential encryption keys live in Android Keystore and are removed on app uninstall. Vault data is not intended to be portable or backed up.
- `third_party/libuvc` may appear as persistent local/untracked workspace noise; ignore it unless you are intentionally updating that submodule content.
