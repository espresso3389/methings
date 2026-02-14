# Vault Endpoints

Credential-vault APIs.

Base URL: `http://127.0.0.1:33389`

| Method | Endpoint | Body | Effect |
|--------|----------|------|--------|
| `GET` | `/vault/credentials` | — | List stored credential names |
| `POST` | `/vault/credentials/get` | `{"name":"..."}` | Read credential value |
| `POST` | `/vault/credentials/has` | `{"name":"..."}` | Check whether a credential exists |
