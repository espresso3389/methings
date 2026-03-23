# Cloud API

Send cloud API requests via the broker with secret injection from vault.

## cloud.request

Permission: broker-managed session approval (`cloud.http` or `cloud.media_upload`, host-scoped)

Send a cloud API request. Two modes:

**Raw mode** -- Supply a full provider request JSON. Placeholders are expanded:
- `${config:<key>}`: expands config/credential values via broker
- `${file:<path>:base64}`: reads file under user root and injects base64 payload

**Adapter mode** -- Use the `adapter` field for simplified multi-provider requests.

**Params (adapter mode):**
- `adapter` (object, required):
  - `provider` (string): `openai` | `deepseek` | `kimi` | `gemini` | `anthropic`
  - `task` (string): `chat` | `vision` | `stt`
  - `model` (string): Model identifier
  - `text` (string): Input text (chat task)
  - `api_key_credential` (string, optional): Vault key name (default is provider-specific)
  - `base_url` (string, optional): Override API endpoint
  - `messages` (object[], optional): Chat history override
  - `temperature` (number, optional): Sampling temperature
  - `max_tokens` (integer, optional): Max output tokens
  - `timeout_s` (integer, optional): Request timeout in seconds
  - `image_path` (string, optional): Single image path (vision task)
  - `image_paths` (string[], optional): Multiple image paths (vision task)
  - `audio_path` (string, optional): Audio file path (stt task)
  - `audio_format` (string, optional): Audio format hint (inferred from extension if omitted)
  - `prompt` (string, optional): Transcription instruction (stt task)

**Returns:**
- `response` (object): Provider response body (proxied as-is)

## file_transfer.prefs.get

Get file transfer preferences (cloud uploads + me.me). See also: [me.me guide](../me_me.md).

**Returns:**
- `auto_upload_no_confirm_mb` (number): Auto-upload threshold in MiB
- `allow_auto_upload_payload_size_less_than_mb` (number): Alias of `auto_upload_no_confirm_mb`
- `min_transfer_kbps` (number): Minimum accepted transfer rate
- `image_resize_enabled` (boolean): Whether image resize is enabled
- `image_resize_max_dim_px` (integer): Maximum image dimension in pixels
- `image_resize_jpeg_quality` (integer): JPEG quality for resized images

## file_transfer.prefs.set

Update file transfer preferences (cloud uploads + me.me). See also: [me.me guide](../me_me.md).

**Params:** Partial preferences object to merge.
