# Documentation Index

This folder documents the local control plane, hardware access, and on-device ML/runtime helpers.

## Start Here

- **API Reference**: [api_reference.md](api_reference.md) — complete action map for all 88 `device_api` actions, WebSocket endpoints, and HTTP-only endpoints.
- **Tools**: [TOOLS.md](../TOOLS.md) — agent tool usage (filesystem, execution, cloud requests) and quickstart examples.
- **Permissions**: [permissions.md](permissions.md) — permission scopes, identity, and request flow.

## Domain Docs

### Media + ML

- **Camera**: [camera.md](camera.md) — CameraX capture/preview.
- **UVC / USB webcams**: [uvc.md](uvc.md) — MJPEG capture + PTZ.
- **Vision (TFLite)**: [vision.md](vision.md) — RGBA8888 frames + inference.
- **STT**: [stt.md](stt.md) — Android SpeechRecognizer.
- **TTS (Android)**: [tts.md](tts.md) — TextToSpeech API.
- **Llama.cpp (Local Models)**: [llama.md](llama.md) — `llama.run`, `llama.generate`, `llama.tts*`.

### Connectivity

- **USB (generic)**: [usb.md](usb.md) — device enumeration + transfers.
- **BLE**: [ble.md](ble.md) — scanning + GATT.

### Hardware / Sensors

- **Sensors**: [sensors.md](sensors.md) — realtime sensor streams via WebSocket.

## Notes

- Llama.cpp TTS is documented in `docs/llama.md` (not `docs/tts.md`).
- Location has no separate domain doc; its reference is in [api_reference.md](api_reference.md).
- [api_reference.md](api_reference.md) is the single source of truth for action names and endpoint mappings.
