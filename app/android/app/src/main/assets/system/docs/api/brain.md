# Brain API

LLM configuration and persistent memory management.

## brain.config.get

Get current brain / LLM configuration. API key is never returned.

**Returns:**
- `vendor` (string): LLM vendor name (e.g. `anthropic`, `openai`).
- `base_url` (string): API base URL.
- `model` (string): Model identifier.
- `has_api_key` (boolean): Whether an API key is configured.

## brain.memory.get

Get persistent brain memory.

**Returns:**
- `memory` (string): Persistent memory text.

## brain.memory.set

Update persistent brain memory.

**Params:**
- `memory` (string, required): New memory content to store.
