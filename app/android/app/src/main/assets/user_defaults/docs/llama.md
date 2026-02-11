# Llama.cpp (Local Models)

`methings` exposes local `llama.cpp` execution through `device_api`.

## Actions

- `llama.status` -> `GET /llama/status`
- `llama.models` -> `GET /llama/models`
- `llama.run` -> `POST /llama/run`
- `llama.generate` -> `POST /llama/generate`
- `llama.tts` -> `POST /llama/tts`
- `llama.tts.speak` -> `POST /llama/tts/speak`
- `llama.tts.speak.status` -> `POST /llama/tts/speak/status`
- `llama.tts.speak.stop` -> `POST /llama/tts/speak/stop`

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
    "args": ["-m", "{{model}}", "-p", "{{text}}", "-o", "{{output_path}}"]
  }
}
```

On success, include `rel_path: captures/miotts.wav` in your assistant reply so chat can render an audio card.

Important notes for current Android runtime:

- Prefer `-p` / `-o` for prompt/output. `--text` is not accepted by this `llama-tts` build.
- Do not rely on `--tts-oute-default` by default. It may fail when remote presets are unavailable.
- For MioTTS, prefer explicit local args and local vocoder path (example below).

MioTTS explicit/local pattern (no remote preset dependency):

```json
{
  "action": "llama.tts",
  "payload": {
    "model": "MioTTS-0.1B-Q4_K_M.gguf",
    "text": "MioTTS test",
    "output_path": "captures/miotts.wav",
    "args": [
      "-m", "{{model}}",
      "-p", "{{text}}",
      "-o", "{{output_path}}",
      "--model-vocoder", "/data/user/0/jp.espresso3389.methings/files/user/.cache/llama.cpp/ggml-org_WavTokenizer_WavTokenizer-Large-75-F16.gguf"
    ]
  }
}
```

### 5) Direct speaker playback with streaming (`llama.tts.speak`)

This endpoint starts synthesis and plays audio on the device speaker while the WAV grows.

```json
{
  "action": "llama.tts.speak",
  "payload": {
    "model": "MioTTS-0.1B-Q8_0.gguf",
    "text": "Hello from methings",
    "output_path": "captures/miotts_stream.wav",
    "args": ["--model", "{{model}}", "--text", "{{text}}", "--output", "{{output_path}}"]
  }
}
```

The response returns `speech_id`. Poll status and stop if needed:

```json
{"action":"llama.tts.speak.status","payload":{"speech_id":"tts_xxx"}}
```

```json
{"action":"llama.tts.speak.stop","payload":{"speech_id":"tts_xxx"}}
```

If you already have a generated audio file and only need playback, use `media.audio.play` with `path`.
