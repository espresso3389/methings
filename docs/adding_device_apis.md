# Adding Device API Actions — Checklist

When adding a new `device_api` action (or a new action family), update the
following locations:

## 1. `ToolDefinitions.kt` — `ACTIONS` map

File: `app/android/app/src/main/java/jp/espresso3389/methings/service/agent/ToolDefinitions.kt`

Add the action with its HTTP method, path, and `requiresPermission` flag.

## 2. `CapabilityMap.kt` — `PREFIX_MAP` (if new permission-requiring prefix)

File: `app/android/app/src/main/java/jp/espresso3389/methings/service/agent/CapabilityMap.kt`

If the new action **requires permission** and belongs to a **new prefix**
(the part before the first `.`, e.g. `tts` in `tts.speak`), add a mapping:

```kotlin
"newprefix" to Triple("device.newprefix", "capability_name", "Human-readable label"),
```

This is used by the scheduler to pre-acquire permissions at schedule creation
time so scheduled JS code runs unattended. The `/scheduler/capability_map`
API auto-derives the full map from `ACTIONS` — prefixes without a `PREFIX_MAP`
entry appear as `"none"` (no permission needed).

If the prefix already exists (e.g. adding `ble.gatt.subscribe` when `ble` is
already mapped), no change is needed.

## 3. `LocalHttpServer.kt` — route handler

File: `app/android/app/src/main/java/jp/espresso3389/methings/service/LocalHttpServer.kt`

Add the HTTP route handler that implements the action.

## 4. OpenAPI spec (if agent-facing)

Directory: `user/docs/openapi/`

Update the relevant `paths/*.yaml` file and `openapi.yaml` per the API docs
policy in `AGENTS.md`.
