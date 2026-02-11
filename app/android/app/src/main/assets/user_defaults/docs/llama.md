# Llama.cpp (Local Models)

`methings` exposes local `llama.cpp` execution through `device_api`.

## Actions

- `llama.status` -> `GET /llama/status`
- `llama.models` -> `GET /llama/models`
- `llama.run` -> `POST /llama/run`
- `llama.generate` -> `POST /llama/generate`
- `llama.tts` -> `POST /llama/tts`

All actions are permission-gated under capability `llama` (`device.llama`).

## Binary Discovery

The runtime searches common locations for binaries:

- `<files>/bin/llama-cli`, `<files>/bin/llama-tts`
- `<files>/user/bin/...`
- `<nativeLibraryDir>/libllama-cli.so`, `<nativeLibraryDir>/libllama-tts.so`

You can also pass explicit paths in payload:

- `binary` for `llama.run/generate/tts`
- `cli_path`/`tts_path` for `llama.status`

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

### 4) TTS convenience (`llama.tts`)

`llama.tts` uses templated args so you can match your `llama-tts` build.

Supported template tokens in `payload.args`:

- `{{model}}`
- `{{text}}`
- `{{output_path}}`

```json
{
  "action": "llama.tts",
  "payload": {
    "model": "MioTTS-0.1B-Q8_0.gguf",
    "text": "Hello from methings",
    "output_path": "captures/miotts.wav",
    "args": ["--model", "{{model}}", "--text", "{{text}}", "--output", "{{output_path}}"]
  }
}
```

On success, include `rel_path: captures/miotts.wav` in your assistant reply so chat can render an audio card.
