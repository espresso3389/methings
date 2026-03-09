# Notifications API

Notification preferences management.

## notifications.prefs.get

Get current notification preferences.

**Returns:**
- `notify_android` (boolean): Whether Android notifications are enabled.
- `notify_sound` (boolean): Whether notification sound is enabled.
- `notify_webhook_url` (string): Webhook URL for notifications (empty string if not set).

## notifications.prefs.set

Update notification preferences. Fields are partially merged (only provided fields are updated).

**Params:**
- `notify_android` (boolean, optional): Enable/disable Android notifications.
- `notify_sound` (boolean, optional): Enable/disable notification sound.
- `notify_webhook_url` (string, optional): Webhook URL for notifications.

**Returns:**
Full updated preferences (same fields as `notifications.prefs.get`).
