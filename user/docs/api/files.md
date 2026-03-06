# Files API

File operations on the app user filesystem and read-only system reference filesystem.

## files.upload

Upload a file to app user root. Uses `multipart/form-data`.

**Params:**
- `file` (binary, required): File to upload
- `dir` (string, optional): Destination directory (relative under user root). Default: `uploads`

**Returns:**
- `path` (string): Saved file path (relative under user root)

**Notes:** Uploaded files are treated as an explicit read grant. Use `rel_path: <path>` in chat responses to trigger inline preview cards.

## files.write

Write file bytes/text to app user filesystem.

Path-style route: `/user/write/<relative-path>` (preferred) or `/user/write` with `path` in body.

**Params:**
- `path` (string, required): User file path (required for body-style route)
- `content` (string|object|array, optional): UTF-8 content
- `body` (optional): Alias of `content`
- `data_b64` (string, optional): Raw bytes as base64
- `encoding` (string, optional): `utf-8` (default) or `base64` when using `content`/`body`

**Returns:**
- `path` (string): Written file path
- `bytes_written` (integer): Bytes written

## files.read

Serve a file from app user root.

Path-style route: `/user/file/<relative-path>` (preferred) or `/user/file?path=...`

**Params:**
- `path` (string, required): Relative path under user root

**Returns:** Raw file bytes with appropriate content type.

## files.info

Get file metadata without serving bytes.

Path-style route: `/user/file/info/<relative-path>` (preferred) or `/user/file/info?path=...`

**Params:**
- `path` (string, required): Relative path under user root (supports `#page=N` fragment)

**Returns:**
- `name` (string): File name
- `size` (integer): File size in bytes
- `mtime_ms` (integer): Last modified time (epoch millis)
- `mime` (string): MIME type
- `kind` (string): `image`, `video`, `audio`, `text`, `bin`
- `ext` (string): File extension
- `width` (integer): Image width in pixels (images only, except SVG)
- `height` (integer): Image height in pixels (images only, except SVG)
- `is_marp` (boolean): Whether this is a Marp presentation (.md only)
- `slide_count` (integer): Number of slides (Marp only)

## files.list

List files in app user directory.

Path-style route: `/user/list/<relative-dir>` (preferred) or `/user/list?path=...`

**Params:**
- `path` (string, optional): Relative path under user root. Empty lists root.

**Returns:**
- `files` (array): Each: `{name, is_dir, size}`

## sys.file

Serve a system reference file (read-only).

Path: `/sys/file/<relative-path>`

System reference docs (docs/, examples/, lib/) are extracted to files/system/ and overwritten on app start. The agent accesses them via the `$sys/` prefix in filesystem tools.

**Params:**
- `path` (string, required): System-root relative path

**Returns:** Raw file bytes.

## sys.list

List system reference directory.

Path: `/sys/list?path=...`

**Params:**
- `path` (string, required): System-root relative directory path

**Returns:**
- `files` (array): Each: `{name, is_dir, size}`
