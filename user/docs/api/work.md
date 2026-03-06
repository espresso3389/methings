# Work API

WorkManager-based background job scheduling for app update checks.

## work.app_update_check.status

Get app update check job status.

**Returns:**
- `schedule` (object): Current schedule configuration and state
- `tracker` (object): Tracker snapshot with last check info
- `work_states` (object): WorkManager state for periodic and one-time jobs

## work.app_update_check.schedule

`Permission: device.work`

Schedule periodic app update check.

**Params:**
- `interval_minutes` (integer, optional): Check interval in minutes (min 15). Default: 360
- `require_charging` (boolean, optional): Only run while charging. Default: false
- `require_unmetered` (boolean, optional): Only run on unmetered (WiFi) network. Default: false
- `replace` (boolean, optional): Replace existing schedule. Default: true

## work.app_update_check.run_once

`Permission: device.work`

Run a one-time app update check.

**Params:**
- `require_charging` (boolean, optional): Default: false
- `require_unmetered` (boolean, optional): Default: false

## work.app_update_check.cancel

`Permission: device.work`

Cancel both periodic and one-time app update check jobs.
