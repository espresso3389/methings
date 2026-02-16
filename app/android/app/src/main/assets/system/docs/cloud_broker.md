# Cloud Broker

Cloud request broker APIs for placeholder expansion and preference management.

Base URL: `http://127.0.0.1:33389`

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `POST` | `/cloud/request` | Provider-specific request JSON | Expand placeholders, inject secrets, enforce media-upload permission |
| `GET` | `/cloud/prefs` | — | Read cloud broker preferences |
| `POST` | `/cloud/prefs` | Preferences JSON | Update image resize and upload threshold preferences |

## Placeholders

- `${config:...}`: expands config values (including secure credentials via broker).
- `${file:<rel_path>:base64}`: reads file bytes and injects base64 payload.

## Notes

- Keep API keys out of prompts/messages; let the broker inject from vault.
- Large file uploads may require explicit confirmation depending on preferences.

## Provider Adapter Mode (Agent-Friendly)

`cloud_request` tool also supports an adapter input so the agent can call
multiple providers with a shared schema, instead of manually crafting each
provider request body.

Supported providers:
- `openai`
- `deepseek`
- `kimi`
- `gemini` (OpenAI-compatible endpoint)
- `anthropic`

Supported tasks:
- `chat`
- `vision`
- `stt` (OpenAI-compatible `responses` JSON path)

Adapter shape:

```json
{
  "adapter": {
    "provider": "openai",
    "task": "chat",
    "model": "gpt-5-mini",
    "text": "Hello"
  }
}
```

Optional common fields:
- `api_key_credential` (vault key name; default is provider-specific)
- `base_url` (override endpoint)
- `messages` (chat history override)
- `temperature`, `max_tokens`, `timeout_s`

Vision fields:
- `image_path` or `image_paths`

STT fields:
- `audio_path`
- `audio_format` (optional; inferred from extension when omitted)
- `prompt` (optional transcription instruction)
