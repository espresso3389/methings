# UI API

WebView control, fullscreen viewer, and settings navigation. See [viewer.md](../viewer.md) for autonomous presentation examples.

## ui.reload

Reload WebView from disk. Call after modifying `www/index.html`.

## ui.reset

Reset UI to factory default (re-extracts from APK assets). Auto-reloads.

## ui.version

Get UI asset version from `www/.version`. Returns plain text.

## viewer.open

Open file in fullscreen viewer. Auto-detects type (image, video, audio, text/code, HTML, Marp).

**Params:**
- `path` (string, required): User-root relative path. Append `#page=N` (0-indexed) for Marp slide navigation.

**Notes:** `#page=N` also works in chat `rel_path:` lines for inline Marp preview thumbnails.

## viewer.close

Close viewer and exit immersive mode.

## viewer.immersive

**Params:** `enabled` (boolean, required): true = hide system bars/chrome, false = restore

## viewer.slideshow

Toggle Marp slideshow mode.

**Params:** `enabled` (boolean, required): true = single-slide mode, false = scroll mode

## viewer.goto

**Params:** `page` (integer, required): 0-indexed slide number, clamped to valid range.

## settings.sections

List Settings sections and setting-key mappings.

**Returns:** `sections` (array of `{id, label}`), `settings` (map: setting key -> section ID)

## settings.navigate

Navigate to a Settings section.

**Params:** `section_id` (string) or `setting_key` (string) -- provide one.

## ui.me_sync.export.show

Show the me.sync export UI.
