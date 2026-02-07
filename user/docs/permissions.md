# Permissions Model (Device + Files)

Kugutz uses explicit user consent for device/resource access.

## Key Concepts

- Permissions are requested by a tool/capability (e.g. `device.usb`, `device.vision`).
- Grants are remembered per `(identity, capability)` for a time window (scope/TTL).
- The agent should *not* ask the user repeatedly once a capability is approved for the session.

## Typical Flow

1. Agent calls a `device_api` action that requires permission.
2. The system prompts the user in-app.
3. User approves.
4. Agent retries automatically.

## Scopes (Conceptual)

- `once`: one short-lived approval for a single operation
- `program`: approval for the current program/task (short TTL)
- `session`: approval for the current chat/session (recommended for interactive work)
- `persistent`: long-lived approval (use sparingly)

## Filesystem

- Agent filesystem tools are restricted to the user root directory.
- Developer option: set brain config `fs_scope="app"` to allow tools to access the whole app private dir.

