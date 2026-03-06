# Brain Journal API

Per-session journal storage. File-backed under `files/user/journal/<session_id>/`, auto-rotates when entries exceed limits. Use "append" for timeline entries and "current" for a mutable per-session summary note.

## brain.journal.config

Get journal configuration (limits and storage root).

**Returns:**
- `root_path` (string): Base path for journal storage
- `max_entry_size` (integer): Maximum size of a single entry
- `max_entries` (integer): Maximum entries per session

## brain.journal.current.get

Read the current per-session note.

**Params:**
- `session_id` (string, required): Chat session ID

**Returns:**
- `text` (string): Current note content (empty string if none)

## brain.journal.current.set

Replace the current per-session note.

**Params:**
- `session_id` (string, required): Chat session ID
- `text` (string, required): New note content (replaces existing)

## brain.journal.append

Append a journal entry.

**Params:**
- `session_id` (string, required): Chat session ID
- `kind` (string, required): Entry kind (e.g. milestone, note, error)
- `title` (string, required): Entry title
- `text` (string, optional): Entry body text
- `meta` (object, optional): Arbitrary metadata

**Returns:**
- `entry_id` (string): ID of the appended entry

## brain.journal.list

List recent journal entries (newest first).

**Params:**
- `session_id` (string, required): Chat session ID
- `limit` (integer, optional): Maximum entries to return. Default: 30

**Returns:**
- `entries` (array): Each entry has `id`, `kind`, `title`, `text`, `meta`, `created_at` (epoch ms)
