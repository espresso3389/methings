# Screen API

Screen keep-on control.

## screen.status

Get current screen keep-on state.

**Returns:**
- `keep_screen_on` (boolean): Whether the screen is currently being kept on.
- `expires_at` (integer): Unix epoch millis when keep-screen-on will expire. 0 if no timeout or disabled.

## screen.keep_on

Enable or disable keep-screen-on.

`Permission: device.screen`

**Params:**
- `enabled` (boolean, optional): Whether to keep the screen on. Default: true
- `timeout_s` (integer, optional): Auto-off timeout in seconds (0-86400). 0 means no timeout (stays on until explicitly disabled). Default: 0

**Returns:**
Same fields as `screen.status`.
