# Auth API

Verified owner identity management. Currently only Google Sign-In is supported.

## auth.identity

Get verified owner identities from the encrypted vault.

**Returns:**
- `identities` (string[]): Verified owner identities in `issuer:sub` format (e.g. `google:user@gmail.com`). Empty array if none configured.

## auth.signout

Remove verified Google owner identity from the encrypted vault.

**Notes:** After sign-out, `auto_approve_own_devices` will no longer match any peer identity.
