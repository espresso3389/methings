# File Endpoints

User-root file APIs.

Base URL: `http://127.0.0.1:33389`

| Method | Endpoint | Body / Query | Effect |
|--------|----------|--------------|--------|
| `POST` | `/user/upload` | `multipart/form-data` (`file`, optional `dir`) | Save upload under `files/user/<dir>/...`; returns `path` |
| `GET` | `/user/file` | `path=<rel_path>` | Serve file bytes from user-root |
| `GET` | `/user/file/info` | `path=<rel_path>` | Return metadata + derived info |
| `GET` | `/user/list` | `path=<rel_path>` | List files under user-root directory |

## Notes

- Use `rel_path: <path>` in chat responses to trigger inline preview cards.
- `GET /user/file/info` is the lightweight metadata API used before opening in viewer.
- `#page=N` fragments are supported by viewer flows for Marp/HTML navigation where applicable.
- For viewer controls (`/ui/viewer/*`), see `viewer.md`.
