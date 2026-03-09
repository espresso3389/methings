# Vault API

Secure credential storage. Values are never exposed in logs or tool history. See [permissions.md](../permissions.md) for permission model.

## vault.credentials.list

`Permission: vault`

List stored credential names (values are not returned).

**Returns:**
- `credentials` (string[]): List of stored credential names

## vault.credentials.get

`Permission: vault`

Retrieve a credential value.

**Params:**
- `name` (string, required): Credential name to retrieve

**Returns:**
- `name` (string): Credential name
- `value` (string): The credential value

## vault.credentials.has

`Permission: vault`

Check if a credential exists.

**Params:**
- `name` (string, required): Credential name to check

**Returns:**
- `name` (string): Credential name
- `exists` (boolean): Whether the credential exists
