# methings -- it thinks, it does

An AI agent framework that runs on Android devices. Cloud AI models (Claude, OpenAI, Kimi, etc.) act as autonomous agents with real access to device resources -- camera, Bluetooth, USB, filesystem, shell -- gated by explicit user consent.

## Architecture

Kotlin owns the always-up control plane; Python is a spawned worker that can crash safely.

```
Android App (Foreground Service)
 +-- Kotlin Control Plane (always up)
 |    +-- Local HTTP Server (127.0.0.1:8765)
 |    +-- WebView UI
 |    +-- Permission Broker (consent + audit)
 |    +-- Credential Vault (Android Keystore AES-GCM)
 |    +-- SSH Server (Dropbear)
 |    +-- Python Runtime Manager
 |
 +-- Python Worker (spawned on demand)
      +-- FastAPI Server (127.0.0.1:8776)
      +-- Agent Runtime (BrainRuntime)
      +-- Tool Router (filesystem, shell, device APIs, cloud)
      +-- Storage (SQLite)
```

## Key Design Principles

- **Outcome-first agents** -- the agent delivers the requested artifact or state change, not an explanation
- **User consent required** -- every device/resource access goes through a permission broker with optional biometric enforcement
- **Crash isolation** -- Python worker can die without taking down the app; Kotlin restarts it on demand
- **Offline by default** -- everything runs locally except explicit cloud API calls
- **Audit trail** -- all tool invocations and permission decisions are logged

## Device Integrations

| Capability | Implementation |
|---|---|
| Camera | CameraX (capture + preview) |
| Bluetooth LE | BLE scan, connect, GATT read/write |
| USB | libusb + libuvc (control/ISO transfer, UVC capture) |
| Vision / ML | TensorFlow Lite on-device inference |
| Text-to-Speech | Android TTS |
| Speech-to-Text | Android STT |
| SSH Server | Dropbear (PIN / notification-based auth) |
| Shell | On-device command execution via tool router |

## Tech Stack

**Android / Kotlin:**
Gradle, Android SDK 34, Kotlin 1.9, NanoHTTPD, Room, CameraX, TFLite

**Python (on-device):**
CPython 3.11+ (Python-for-Android), FastAPI, Uvicorn, Pydantic

**Native (NDK):**
Dropbear SSH, libusb, libuvc, custom C launchers (methingspy, methingssh)

## Project Structure

```
app/                    Android project (Kotlin + native)
server/                 Python local service & agent runtime
docs/                   Design docs and API documentation
scripts/                Build scripts (P4A, Dropbear, libusb, libuvc)
user/                   User-modifiable defaults
protected/              App-private encrypted storage
third_party/            Git submodules (dropbear, libusb, libuvc)
```

## Building

### Prerequisites

- Android SDK (API 34+)
- Android NDK 29.0.14206865
- Python 3.11+ (host, for build scripts)

### Steps

1. Initialize submodules:
   ```
   git submodule update --init --recursive
   ```

2. Build the Python-for-Android runtime:
   ```
   scripts/build_p4a.sh
   ```

3. Build the APK (native libs are compiled automatically):
   ```
   cd app/android
   ./gradlew assembleDebug
   ```

The build process automatically handles:
- Syncing Python server code and user defaults into assets
- Compiling native libraries (Dropbear, libusb, libuvc)
- Building Python facade wheels for Android bindings

### Output

- APK: `app/android/app/build/outputs/apk/`
- Package: `jp.espresso3389.methings`
- Architecture: arm64-v8a only

## Security

- **Credential encryption:** API keys and SSH keys encrypted with Android Keystore (AES-GCM); ciphertext stored in Room DB
- **Permission gating:** Tool invocations requiring device access trigger user consent prompts
- **Biometric option:** Sensitive operations can require fingerprint authentication
- **Audit logging:** All actions logged to persistent audit table
- **Scoped access:** File operations scoped to user root or app-private directories
- **Key lifecycle:** Encryption keys tied to device; removed on app uninstall

## Troubleshooting

### SSH server stops unexpectedly (Android 12+)

Android 12 introduced a "Phantom Process Killer" that terminates child processes
(like the built-in SSH server) when the system is under memory pressure. The app
automatically restarts the SSH server when this happens, but frequent kills may
interrupt active SSH sessions.

To disable the phantom process killer entirely (requires ADB):

    adb shell settings put global settings_enable_monitor_phantom_procs false

To re-enable:

    adb shell settings put global settings_enable_monitor_phantom_procs true

This setting persists across reboots but resets on factory reset.

## License

TBD
