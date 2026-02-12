# Documentation Index

This folder documents the local control plane, hardware access, and on-device ML/runtime helpers.

## Start Here

- **API Reference**: `docs/api_reference.md` - canonical action map for `device_api`.
- **Permissions**: `docs/permissions.md` - permission scopes and request flow.

## Media + ML

- **Camera**: `docs/camera.md` - CameraX capture/preview.
- **UVC / USB webcams**: `docs/uvc.md` - MJPEG capture + PTZ.
- **Vision (TFLite)**: `docs/vision.md` - RGBA8888 frames + inference.
- **STT**: `docs/stt.md` - Android SpeechRecognizer.
- **TTS (Android)**: `docs/tts.md` - TextToSpeech API.
- **Llama.cpp (Local Models)**: `docs/llama.md` - `llama.run`, `llama.generate`, `llama.tts*`.

## Connectivity

- **USB (generic)**: `docs/usb.md` - device enumeration + transfers.
- **BLE**: `docs/ble.md` - scanning + GATT.

## Notes

- Llama.cpp TTS is documented in `docs/llama.md` (not `docs/tts.md`).
- `docs/api_reference.md` remains the single source of truth for action names.
