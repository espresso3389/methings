# Local Python Service Scaffold

This is a minimal local HTTP server intended to run on-device.

## Endpoints (stub)
- GET /health
- POST /sessions
- GET /sessions/{id}
- POST /sessions/{id}/messages
- POST /tools/{tool}/invoke
- POST /permissions/request
- GET /permissions/pending
- POST /permissions/{id}/approve
- POST /permissions/{id}/deny
- GET /logs/stream (SSE)
- GET /webhooks
- POST /webhooks
- POST /webhooks/test
- GET /audit/recent
- POST /auth/{provider}/config
- POST /auth/{provider}/start
- POST /auth/{provider}/callback
- POST /auth/callback
- GET /auth/{provider}/status
- POST /keys/{provider}
- GET /keys/{provider}/status
- DELETE /keys/{provider}

## Run (desktop)
```
python app.py
```

## Next Steps
- Wire log streaming to UI.
- Implement tool routing + permission checks.
- Replace SQLite with a more robust store if needed.
- Implement Slack/Discord webhook configuration UI.
- Add tool implementations (filesystem/shell) behind permission checks.
- Enable SQLCipher by ensuring pysqlcipher3 is available and SQLCIPHER_KEY_FILE is set.
