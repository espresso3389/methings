# Llama.cpp (Local Models)

`methings` exposes local `llama.cpp` execution through `device_api`.

## Actions

- `llama.status` -> `GET /llama/status`
- `llama.models` -> `GET /llama/models`
- `llama.run` -> `POST /llama/run`
- `llama.generate` -> `POST /llama/generate`

All actions are permission-gated under capability `llama` (`device.llama`).

## Binary Discovery

The runtime searches common locations for binaries:

- `<files>/bin/llama-cli`
- `<files>/user/bin/...`
- `<nativeLibraryDir>/libllama-cli.so`

You can also pass explicit paths in payload:

- `binary` for `llama.run`
- `cli_path` for `llama.status`

## Models

Default model roots:

- `<files>/models`
- `<files>/models/llama`
- `<files>/user/models`
- `<files>/user/models/llama`

`llama.models` lists `.gguf` files found under these roots.

## Examples

### 1) Check runtime

```json
{
  "action": "llama.status",
  "payload": {}
}
```

### 2) Generic command (`llama.run`)

```json
{
  "action": "llama.run",
  "payload": {
    "binary": "llama-cli",
    "args": ["-m", "models/qwen2.5-1.5b-instruct.gguf", "-p", "hello", "-n", "64"],
    "timeout_ms": 180000
  }
}
```

### 3) Text generation convenience (`llama.generate`)

```json
{
  "action": "llama.generate",
  "payload": {
    "model": "qwen2.5-1.5b-instruct.gguf",
    "prompt": "Summarize this in one sentence: ...",
    "n_predict": 128,
    "temperature": 0.7
  }
}
```

## TTS

Use Android TTS APIs documented in `docs/tts.md` (`/tts/*`).
