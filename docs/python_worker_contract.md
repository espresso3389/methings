# Python Worker Contract

The Python server is a **worker**. Kotlin owns the always‑on control plane, permissions, SSHD, UI hosting, and credential vault. The Python worker is started on demand and can crash without taking down the app.

## Responsibilities (Python)
- Agent execution logic and tool orchestration.
- Running user/agent code in isolated child processes (future).
- Compute-heavy or Python‑native features (e.g., indexing, transforms, SDK calls).
- Expose worker endpoints **only** for tasks/agents (not UI or system control).

## Not Responsibilities (Python)
- No UI hosting (served by Kotlin).
- No permissions or access control decisions.
- No SSHD management.
- No credential storage or key management.
- No always‑on lifecycle guarantees.

## Allowed Endpoints (Worker)
These are handled by the Python worker when it is running:
- `POST /agent/run`
- `GET /agent/tasks`
- `GET /agent/tasks/{id}`
- `GET /logs/stream` (optional if log streaming is done by Kotlin)

## Disallowed / Deprecated Endpoints
These should remain in Kotlin (or be removed from Python):
- `/ui/*`
- `/permissions/*`
- `/ssh/*`
- `/vault/*`
- `/python/*` (Kotlin control plane owns worker lifecycle)

## Data Access Rules
- Sensitive data (credentials) must be accessed **through Kotlin APIs**, not directly from Python.
- Python may receive only the minimum necessary inputs per request.
- Long‑running jobs should report progress back to Kotlin via status endpoints or logs.

## Lifecycle
- Kotlin starts the worker when a task requires it.
- Kotlin may restart the worker as an emergency action.
- The UI only exposes an “Emergency restart” control.

## Future Notes
- Add a child‑process model so user/agent code runs in separate Python processes.
- Define a strict per‑tool allowlist enforced by Kotlin.
