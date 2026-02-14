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
