# Brain API

LLM configuration and persistent memory management.

## brain.config.get

Get current brain / LLM configuration. API key is never returned.

**Returns:**
- `vendor` (string): LLM vendor name (e.g. `anthropic`, `openai`).
- `base_url` (string): API base URL.
- `model` (string): Model identifier.
- `has_api_key` (boolean): Whether an API key is configured.
- `requires_api_key` (boolean): Whether the selected provider requires an API key. `embedded` does not.

## GET /brain/embedded/status

Get install/runtime status for an embedded brain model.

Query params:
- `model` (string, optional): Model id. Defaults to the currently configured brain model.

Returns:
- `configured` (boolean): Whether this model is the active configured embedded brain.
- `selected_model` (string): Resolved model id.
- `status` (object): Embedded backend status, including:
  - `backend` (string)
  - `installed` (boolean)
  - `runnable` (boolean)
  - `loaded` (boolean)
  - `warm` (boolean)
  - `detail` (string)
  - `primary_model_path` (string)
  - `candidate_paths` (array)
  - `last_error` (string)
  - `last_loaded_at_ms` (number)
  - `last_used_at_ms` (number)
  - `last_turn_diagnostics` (object, optional), including:
    - `turn_id` (number)
    - `last_phase` (string)
    - `response_source` (string: `pending`, `original`, `repaired`, or `fallback`)
    - `final_tool_call_count` (number)
    - `final_message_count` (number)
    - `selected_tools` (array)
    - `failed_tools` (array)
    - `tool_failures` (array of `{name, reason}`)
    - `repair_used` (boolean)
    - `repair_attempt_count` (number)
    - `fallback_used` (boolean)
    - `last_summary` (string)
    - `updated_at_ms` (number)
  - capability flags such as `supports_tool_calling`

## POST /brain/embedded/install

Download an embedded model bundle from a direct `http` or `https` URL into the app sandbox.

Body:
- `model` (string, required unless already configured): Embedded model id.
- `url` (string, required): Direct download URL.

Returns:
- `status` (string): `ok` on success.
- `model` (string): Installed model id.
- `saved_path` (string): Final stored file path.
- `embedded_status` (object, optional): Refreshed embedded backend status.

## POST /brain/embedded/warm

Warm the embedded backend for a model so it is loaded and ready before the next turn.

Body:
- `model` (string, optional): Embedded model id. Defaults to the currently configured brain model.

Returns:
- `status` (string): `ok` on success.
- `model` (string): Warmed model id.
- `embedded_status` (object): Refreshed embedded backend status.

## POST /brain/embedded/unload

Unload a cached embedded model instance from memory.

Body:
- `model` (string, optional): Embedded model id. Defaults to the currently configured brain model.

Returns:
- `status` (string): `ok` on success.
- `model` (string): Unloaded model id.
- `unloaded` (boolean): Whether a loaded instance was actually removed.
- `embedded_status` (object): Refreshed embedded backend status after unload.

## brain.memory.get

Get persistent brain memory.

**Returns:**
- `content` (string): Persistent memory text.

## brain.memory.set

Update persistent brain memory.

Permission: `device.brain`

**Params:**
- `content` (string, required): New memory content to store.
