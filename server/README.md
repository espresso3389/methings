# Local Python Service Scaffold

This is a minimal local HTTP server intended to run on-device.

## Endpoints (stub)
- GET /health
- GET /ui/version
- POST /tools/{tool}/invoke
- POST /permissions/request
- GET /permissions/pending
- POST /permissions/{id}/approve
- POST /permissions/{id}/deny
- GET /logs/stream (SSE)
- POST /programs/start
- POST /programs/{id}/stop
- GET /programs
- GET /audit/recent
- GET /vault/credentials
- GET /vault/credentials/{name}
- POST /vault/credentials/{name}
- DELETE /vault/credentials/{name}
- POST /services/register
- GET /services
- GET /services/{name}
- GET /services/{name}/vault/{credential}

## SSHD
SSHD is handled by the Kotlin control plane (not the Python worker).
Use the local Kotlin HTTP server endpoints instead of the Python worker for
SSH config, keys, PIN auth, and notification-based auth.

## Run (desktop)
```
python app.py
```

## Next Steps
- Wire log streaming to UI.
- Implement tool routing + permission checks.
- Replace SQLite with a more robust store if needed.
- Add tool implementations (filesystem/shell) behind permission checks.
- Credentials are stored as ciphertext by the Kotlin control plane using Android Keystore (AES-GCM).
- Credential vault endpoints require an approved permission_id from /permissions/request.
- Service registration requires a credential permission and creates a service-specific vault snapshot.
- Service vault uses per-service Android Keystore keys via the local vault server on 127.0.0.1:8766.
