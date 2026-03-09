# Permissions API

Manage permission requests and grants. See also: [permissions concept guide](../permissions.md).

## permissions.request

Create a permission request.

**Params:**
- `tool` (string, required): Tool category (e.g. `device.usb`, `device.vision`)
- `detail` (string, required): Human-readable description for the approval prompt
- `scope` (string, required): Grant lifetime: `once` | `program` | `session` | `persistent`
- `capability` (string, required): Capability tag for the grant

**Returns:** `PermissionRequiredResponse` with request details.

## permissions.pending

List pending (unapproved) permission requests.

**Returns:**
- `requests` (PermissionRequest[]): Array of pending requests

## permissions.grants

List active (non-expired) permission grants.

**Returns:**
- `grants` (object[]): Each grant contains:
  - `id` (string): Grant ID
  - `tool` (string): Tool category
  - `capability` (string): Capability tag
  - `identity` (string): Caller identity
  - `scope` (string): Grant scope
  - `approved_at` (int64): Approval timestamp

## permissions.get

Get permission request status by ID.

**Params:**
- `id` (string, required): Permission request ID (path parameter)

**Returns:**
- `request` (PermissionRequest): Full request details and status

## permissions.approve_or_deny

Approve or deny a permission request.

**Params:**
- `id` (string, required): Permission request ID (path parameter)
- `approved` (boolean, required): true to approve, false to deny

## permissions.clear

Clear all permission grants.

## permissions.prefs.get

Get permission preferences.

**Returns:**
- `remember_approvals` (boolean): Whether to remember approvals across sessions

## permissions.prefs.set

Update permission preferences.

**Params:**
- `remember_approvals` (boolean, optional): Whether to remember approvals across sessions
