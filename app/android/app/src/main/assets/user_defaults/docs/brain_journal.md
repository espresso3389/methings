# Brain Journal

Per-session journal APIs backed by files under `files/user/journal/<session_id>/...`.

Base URL: `http://127.0.0.1:8765`

| Method | Endpoint | Body / Query | Effect |
|--------|----------|--------------|--------|
| `GET` | `/brain/journal/config` | — | Return journal limits and root path |
| `GET` | `/brain/journal/current` | `session_id=<sid>` | Read current per-session note |
| `POST` | `/brain/journal/current` | `{"session_id":"<sid>","text":"..."}` | Replace current note |
| `POST` | `/brain/journal/append` | `{"session_id":"<sid>","kind":"milestone","title":"...","text":"...","meta":{...}}` | Append journal entry |
| `GET` | `/brain/journal/list` | `session_id=<sid>&limit=30` | List recent entries |

## Notes

- Storage is file-backed and rotates when entries exceed limits.
- `append` is intended for timeline-like entries; `current` is for mutable session summary.
