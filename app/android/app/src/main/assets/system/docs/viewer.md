# Viewer Control & File Info

Programmatic control of the WebView fullscreen viewer and file metadata inspection. Enables agent-driven presentations when combined with TTS.

## File Info

`GET /user/file/info?path=<rel_path>`

Returns file metadata without serving file bytes. Strips `#page=N` fragment from path before lookup.

### Response

Always returned:

```json
{
  "name": "demo.md",
  "size": 4096,
  "mtime_ms": 1700000000000,
  "mime": "text/markdown",
  "kind": "text",
  "ext": "md"
}
```

- `kind` is one of: `image`, `video`, `audio`, `text`, `bin`

### Extra fields by kind

**Images** (except SVG): image dimensions via `BitmapFactory`.

```json
{ "width": 1920, "height": 1080 }
```

Omitted if the image is corrupt or unreadable.

**Markdown** (`ext: "md"`): Marp detection via YAML front matter (`marp: true`).

```json
{ "is_marp": true, "slide_count": 15 }
```

- `is_marp`: always present for `.md` files.
- `slide_count`: only present when `is_marp` is `true`. Counted by splitting on `\n---\n` after stripping front matter.
- Front matter detection reads only the first 1KB of the file.

## Viewer Control

All viewer endpoints are `POST`, return `{"status":"ok"}` immediately (fire-and-forget via broadcast to the WebView), and require no permissions.

### Endpoints

| Endpoint | Body | Effect |
|----------|------|--------|
| `/ui/viewer/open` | `{"path":"rel/path.md"}` | Open file in viewer (auto-detects type) |
| `/ui/viewer/close` | `{}` | Close viewer, exit immersive mode |
| `/ui/viewer/immersive` | `{"enabled":true}` | Enter/exit immersive (fullscreen) mode |
| `/ui/viewer/slideshow` | `{"enabled":true}` | Enter/exit Marp slideshow mode |
| `/ui/viewer/goto` | `{"page":0}` | Navigate to slide (0-indexed) |

### `/ui/viewer/open`

- The `path` is validated against the user root; returns 404 if the file does not exist.
- Auto-detects file type by extension and opens the appropriate viewer (image, video, audio, text/code, HTML iframe).
- For Marp markdown, the full slide deck is rendered with navigation controls.

#### `#page=N` fragment

Append `#page=N` (0-indexed) to the path to open at a specific slide:

```json
{"path": "presentations/demo.md#page=3"}
```

The fragment is stripped for file validation but passed to the JS viewer. In scroll mode, the viewer scrolls to the target slide. For non-Marp files, the fragment is silently ignored.

This fragment also works in chat `rel_path:` lines:

```
rel_path: presentations/demo.md#page=3
```

The inline Marp preview thumbnail will show the specified slide, and tapping the card opens the viewer at that page.

### `/ui/viewer/close`

Closes the viewer and exits immersive mode if active.

### `/ui/viewer/immersive`

- `enabled: true` — hide system bars and viewer chrome (immersive fullscreen).
- `enabled: false` — restore system bars and viewer chrome.
- No-op if no viewer is open.

### `/ui/viewer/slideshow`

- `enabled: true` — enter single-slide presentation mode (one slide at a time, swipe/arrow navigation).
- `enabled: false` — return to scroll mode (all slides visible).
- No-op if the current file is not a Marp deck.

### `/ui/viewer/goto`

- `page`: 0-indexed slide number. Clamped to `[0, slide_count - 1]`.
- In slideshow mode, displays the target slide. In scroll mode, scrolls to it.
- No-op if the current file is not a Marp deck.

## Autonomous Presentation Example

```bash
# 1. Inspect the deck
curl 'http://127.0.0.1:8765/user/file/info?path=presentations/demo.md'
# -> {"name":"demo.md", "is_marp":true, "slide_count":15, ...}

# 2. Open the viewer at slide 0
curl -X POST http://127.0.0.1:8765/ui/viewer/open \
  -d '{"path":"presentations/demo.md"}'

# 3. Enter slideshow + immersive
curl -X POST http://127.0.0.1:8765/ui/viewer/slideshow -d '{"enabled":true}'
curl -X POST http://127.0.0.1:8765/ui/viewer/immersive -d '{"enabled":true}'

# 4. Navigate slides (with TTS narration between)
curl -X POST http://127.0.0.1:8765/ui/viewer/goto -d '{"page":1}'
# ... tts.speak for slide 2 narration ...
curl -X POST http://127.0.0.1:8765/ui/viewer/goto -d '{"page":2}'

# 5. Close when done
curl -X POST http://127.0.0.1:8765/ui/viewer/close -d '{}'
```
