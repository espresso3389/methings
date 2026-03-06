# SSHD API

Embedded Dropbear SSH server management: configuration, keys, PIN auth, and no-auth mode.

## sshd.status

Get SSHD server status.

**Returns:**
- `enabled` (boolean): Whether SSHD is enabled in config
- `running` (boolean): Whether the SSH server process is running
- `port` (integer): Configured SSH port
- `noauth_enabled` (boolean): Whether no-auth mode is enabled
- `auth_mode` (string): Current authentication mode (key, pin, notification)
- `host` (string): Host IP address
- `client_key_fingerprint` (string|null): Client key fingerprint
- `client_key_public` (string|null): Client public key

## sshd.config

`Permission: device.sshd`

Update SSHD configuration.

**Params:**
- `enabled` (boolean, optional): Enable or disable SSHD
- `port` (integer, optional): SSH port number
- `auth_mode` (string, optional): Authentication mode
- `noauth_enabled` (boolean, optional): Enable or disable no-auth mode

**Returns:**
- `enabled` (boolean), `running` (boolean), `port` (integer), `noauth_enabled` (boolean)

## sshd.keys.list

List authorized SSH keys.

**Returns:**
- `items` (array): Each: `{fingerprint, label, expires_at, created_at}`

## sshd.keys.add

`Permission: device.sshd`

Add an authorized SSH key.

**Params:**
- `key` (string, required): SSH public key in OpenSSH format
- `label` (string, optional): Label for the key
- `expires_at` (integer, optional): Expiry timestamp (epoch millis)

**Returns:**
- `fingerprint` (string): Fingerprint of the added key

## sshd.keys.delete

`Permission: device.sshd`

Delete an authorized SSH key.

**Params:**
- `fingerprint` (string, optional): Key fingerprint to delete
- `key` (string, optional): Public key to delete (used to look up fingerprint)

**Notes:** Provide either `fingerprint` or `key`.

## sshd.keys.policy.get

Get SSH key policy.

**Returns:**
- `require_biometric` (boolean): Whether biometric verification is required for key operations

## sshd.keys.policy.set

`Permission: device.sshd`

Update SSH key policy.

**Params:**
- `require_biometric` (boolean, optional): Whether biometric verification is required

## sshd.pin.status

Get PIN authentication status.

**Returns:**
- `active` (boolean): Whether PIN auth is active
- `pin` (string): Current PIN (empty if not active)
- `expires_at` (integer|null): PIN expiry timestamp

## sshd.pin.start

`Permission: device.sshd`

Start PIN authentication mode.

**Params:**
- `seconds` (integer, optional): PIN validity duration in seconds. Default: 10

**Returns:**
- `active` (boolean): true
- `pin` (string): Generated PIN
- `expires_at` (integer|null): Expiry timestamp

## sshd.pin.stop

`Permission: device.sshd`

Stop PIN authentication mode.

**Returns:**
- `active` (boolean): false

## sshd.noauth.status

Get no-auth mode status.

**Returns:**
- `active` (boolean): Whether no-auth mode is active
- `expires_at` (integer|null): Expiry timestamp

## sshd.noauth.start

`Permission: device.sshd`

Start no-auth (notification) mode.

**Params:**
- `seconds` (integer, optional): Validity duration in seconds. Default: 30

**Returns:**
- `active` (boolean): true
- `expires_at` (integer|null): Expiry timestamp

## sshd.noauth.stop

`Permission: device.sshd`

Stop no-auth mode.

**Returns:**
- `active` (boolean): false
