# OAuth via In-App Browser (Plan)

## Goal
Enable provider OAuth flows (OpenAI/Claude/Kimi if needed) using Android In-App Browser / Custom Tabs, while allowing the Python service to initiate and receive auth results.

## Proposed Flow
1) Web UI or Python service requests auth:
   - POST /auth/{provider}/start
2) Android layer opens Custom Tab with provider OAuth URL.
3) Provider redirects to a custom scheme (e.g., kugutz://oauth) or app link.
4) Android captures the callback, extracts code/state.
5) Android posts result to local Python service:
   - POST /auth/{provider}/callback { code, state } or POST /auth/callback { code, state }
6) Python service exchanges code for tokens and stores securely.

## Wiring Points
- Android:
  - OAuthActivity (singleTask) for handling redirect
  - Custom Tabs launch helper
  - Broadcast/bridge to local Python service
- Python:
  - /auth/{provider}/start returns auth URL + state
  - /auth/{provider}/callback handles token exchange
  - /auth/callback handles generic callback via state lookup
  - secure token storage

## Security Notes
- Use PKCE
- Store tokens in encrypted storage (Android Keystore or encrypted DB)
- Validate state parameter

## TODO
- Define provider-specific OAuth scopes/URLs
- Implement token storage policy
- Add UI status panel for auth state
