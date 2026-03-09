# Scheduler API

Scheduled code execution engine for `run_js` (QuickJS) and `run_python` (embedded worker) runtimes. See also: [run_js.md](../run_js.md)

## scheduler.status

Get scheduler engine status.

**Returns:**
- `started` (boolean): Whether the scheduler engine is running
- `running_count` (integer): Number of currently executing schedules
- `running_ids` (array of string): IDs of currently executing schedules

## scheduler.schedules

List all schedules.

**Returns:**
- `schedules` (array of Schedule): See Schedule object below

## scheduler.capability_map

Get the device_api capability map for permission pre-acquisition. Informational -- `run_js` permissions are checked automatically at create/update time; `run_python` requires manual pre-acquisition.

**Returns:**
- `capabilities` (array): Each: `{prefix, tool, capability, label}`

## scheduler.create

`Permission: device.scheduler`

Create a new schedule.

**Params:**
- `name` (string, required): Human-readable label
- `launch_type` (string, required): `daemon` (runs on service start), `periodic` (recurring), `one_time` (fires once, auto-disables)
- `schedule_pattern` (string, optional): Pattern for periodic: `minutely`, `hourly`, `daily`, `weekly:Mon`..`weekly:Sun`, `monthly:1`..`monthly:28`. Empty for daemon/one_time. Default: ""
- `runtime` (string, required): `run_js` or `run_python`
- `code` (string, required): Source code to execute
- `args` (string, optional): Extra CLI args (run_python only). Default: ""
- `cwd` (string, optional): Working directory (run_python only). Default: ""
- `timeout_ms` (integer, optional): Execution timeout. Default: 60000
- `enabled` (boolean, optional): Default: true
- `meta` (string, optional): JSON blob for extensibility. Default: "{}"

**Returns:** Schedule object

**Notes:** For `run_js`, device_api permissions are auto-checked at creation for the `scheduler` identity. For `run_python`, pre-acquire permissions manually: call each needed endpoint with `{"identity": "scheduler"}`, approve on device, then create the schedule. Max 50 schedules.

## scheduler.get

Get a schedule by ID.

**Params:**
- `id` (string, required): Schedule UUID

**Returns:** Schedule object + `running` (boolean)

## scheduler.update

`Permission: device.scheduler`

Update a schedule. Only include fields to change.

**Params:**
- `id` (string, required): Schedule UUID
- `name`, `schedule_pattern`, `runtime`, `code`, `args`, `cwd`, `timeout_ms`, `enabled`, `meta` (all optional, same types as create)

**Returns:** Updated Schedule object

**Notes:** When `code` is updated for `run_js`, permissions are re-checked.

## scheduler.delete

`Permission: device.scheduler`

Delete a schedule.

**Params:**
- `id` (string, required): Schedule UUID

**Returns:**
- `status` (string): `ok` or `not_found`

## scheduler.trigger

`Permission: device.scheduler`

Trigger immediate execution of a schedule.

**Params:**
- `id` (string, required): Schedule UUID

**Returns:**
- `status` (string): `ok` or `error`
- `error` (string): Error code if failed (e.g. `not_found`, `already_running`)

## scheduler.log

Get execution log for a schedule.

**Params:**
- `id` (string, required): Schedule UUID
- `limit` (integer, optional): Max entries. Default: 20. Max: 200

**Returns:**
- `logs` (array of ExecutionLogEntry)

### Schedule object

`{id, name, launch_type, schedule_pattern, runtime, code, args, cwd, timeout_ms, enabled, created_at, updated_at, last_run_at, next_run_at, run_count, error_count, meta}`

### ExecutionLogEntry

`{id, schedule_id, started_at, finished_at, status, result, console_output, error}`

- `status`: `running`, `ok`, `error`, `timeout`, `interrupted`
- `result`: Return value / stdout (truncated to 8 KB)
- `console_output`: console.log output (JS) or stderr (Python)
