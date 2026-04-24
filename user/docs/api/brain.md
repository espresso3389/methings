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

The app prefers the AICore Developer Preview backend when ML Kit Prompt API reports that the preview model is available on the device. If it is unavailable, the app falls back to the app-managed LiteRT-LM model file backend.

Returns:
- `configured` (boolean): Whether this model is the active configured embedded brain.
- `selected_model` (string): Resolved model id.
- `status` (object): Embedded backend status, including:
  - `backend` (string): For example `aicore_preview` or `litert_lm`.
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
- `available_backend` (string): First runnable embedded backend, currently `aicore_preview`, `litert_lm`, or `none`.
- `backend_to_use` (string): Backend the app will try for setup/use. This is `aicore_preview` when AICore reports available; otherwise it is `litert_lm` for the built-in fallback path. It is a routing indicator, not a guarantee that the backend is already warmed or that a model file is installed.
- `backend_candidates` (array): Status snapshots for each embedded backend considered by the registry. Intended for diagnostics; the WebView only shows the concise backend-to-use indicator.

## POST /brain/embedded/setup

Start transactional embedded model setup for the selected model.

This is the high-level flow used by the WebView `Save` button for embedded models:
- download to a staged file
- validate that the payload is really a model file
- warm/load the model
- only then commit `brain.config` to the new embedded model

If download, validation, or warmup fails, the previous brain selection is kept.

Body:
- `model` (string, required): Embedded model id. Currently only `gemma4-e2b-it` is supported.
- `url` (string, optional): Direct download URL.
  - Leave empty to use an already-available AICore Developer Preview model.
  - For `gemma4-e2b-it`, leave empty to use the built-in LiteRT-LM download URL when AICore is not runnable.
  - The server selects the Qualcomm QCS8275 artifact on matching devices and the generic Gemma 4 E2B artifact otherwise.
  - For Hugging Face, use `/resolve/...` URLs, not `/blob/...` page URLs.

Returns:
- `status` (string): `started` on success.
- `setup` (object): Initial setup progress snapshot.

## GET /brain/embedded/setup/status

Get current embedded setup progress.

Returns:
- `status` (string): `ok`
- `active` (boolean): Whether a setup job is still active or still in its short sticky post-run window.
- `setup` (object):
  - `active` (boolean)
  - `state` (string): `idle`, `running`, `completed`, `failed`, or `cancelled`
  - `phase` (string)
  - `message` (string)
  - `job_id` (string)
  - `model` (string)
  - `url` (string)
  - `bytes_downloaded` (number)
  - `total_bytes` (number, optional)
  - `can_cancel` (boolean)
  - `committed` (boolean): Whether `brain.config` was finally updated
  - `detail` (string, optional)

## POST /brain/embedded/setup/cancel

Request cancellation of the active embedded setup job.

Returns:
- `status` (string): `ok`
- `cancel_requested` (boolean)
- `job_id` (string, optional)

## POST /brain/embedded/install

Download an embedded model bundle from a direct `http` or `https` URL into the app sandbox.

This is a low-level install endpoint. It does not update `brain.config` and does not provide transactional rollback for the selected brain model.

Body:
- `model` (string, required unless already configured): Embedded model id.
- `url` (string, required): Direct download URL.
  - For Hugging Face, use `/resolve/...` URLs, not `/blob/...` page URLs.

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

Status panel reading guide:
- `response_source` tells you whether the final embedded result came from the original merged output, a repaired output, or the required-tool fallback path.
- `final_tool_call_count` and `final_message_count` describe the final result shape returned by the embedded backend.
- `tool_failures` lists per-tool normalization failures from the last turn; this is the fastest field to inspect when local tool use did not behave as expected.
- `repair_attempt_count` shows whether the backend actually consumed its repair pass, not just whether the turn entered a repair-needed state.

## brain.memory.get

Get persistent brain memory.

**Returns:**
- `content` (string): Persistent memory text.

## brain.memory.set

Update persistent brain memory.

Permission: `device.brain`

**Params:**
- `content` (string, required): New memory content to store.
