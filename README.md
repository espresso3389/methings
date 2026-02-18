# me.things -- it thinks, it does

An Android app that turns your phone into an AI-powered agent with real access to device hardware. Cloud AI models (Claude, OpenAI, Gemini, DeepSeek, Kimi, etc.) act as autonomous agents that can operate your camera, Bluetooth, USB devices, sensors, filesystem, and shell -- all gated by explicit user consent.

Your devices can also talk to each other. **me.me** connects your devices over WiFi, Bluetooth LE, WebRTC DataChannel (P2P across NATs), or cloud relay for encrypted messaging and file transfer. **me.sync** migrates your entire setup between devices with a QR code.

## What It Can Do

| Category | Capabilities |
|---|---|
| **AI Agents** | Multi-provider LLM chat (Claude, OpenAI, Gemini, DeepSeek, Kimi), persistent memory, tool invocation, background tasks |
| **Camera** | Still capture, live preview stream, front/back/external lens |
| **Audio** | Recording (AAC), live PCM streaming, playback, text-to-speech, speech recognition |
| **Video** | Recording (H.265/H.264), live frame streaming, screen recording |
| **Bluetooth LE** | Scanning, GATT read/write/notify, device connections |
| **USB** | Device enumeration, control/bulk/isochronous transfers, UVC webcam capture |
| **Sensors** | Accelerometer, gyroscope, magnetometer, and more via real-time WebSocket streams |
| **Location** | GPS, fused, network, passive providers |
| **Vision / ML** | On-device TensorFlow Lite inference on camera frames |
| **Shell** | On-device command execution, Python eval |
| **SSH** | Built-in SSH server (Dropbear) with PIN/key/biometric auth; SSH client for remote exec and SCP |
| **Browser** | Agent-controllable WebView with screenshot, JS injection, tap/scroll simulation |
| **Cloud** | API broker with automatic secret injection from encrypted vault |
| **me.me** | Encrypted device-to-device messaging over WiFi/BLE/WebRTC P2P/relay, file transfer, auto-discovery |
| **me.sync** | Full device state migration via QR code (Nearby Connections or LAN) |
| **Notifications** | Android notifications, webhooks, Firebase Cloud Messaging |

## Architecture

Kotlin owns the always-up control plane; Python is a spawned worker that can crash safely.

```
Android App (Foreground Service)
 +-- Kotlin Control Plane (always up)
 |    +-- Local HTTP API (127.0.0.1:33389)
 |    +-- WebView UI
 |    +-- Permission Broker (consent + audit)
 |    +-- Credential Vault (Android Keystore AES-GCM)
 |    +-- me.me Engine (BLE + WiFi + WebRTC P2P + relay transport)
 |    +-- SSH Server (Dropbear)
 |    +-- Python Runtime Manager
 |
 +-- Python Worker (spawned on demand)
      +-- FastAPI Server (127.0.0.1:8776)
      +-- Agent Runtime (BrainRuntime)
      +-- Tool Router (device APIs, filesystem, shell, cloud)
      +-- Storage (SQLite)
```

Every device capability is exposed as an HTTP endpoint on `127.0.0.1:33389`. The on-device agent (or any local client) calls these endpoints to interact with hardware. All sensitive operations require user consent through the permission broker.

## Key Design Principles

- **Outcome-first agents** -- the agent delivers the requested artifact or state change, not an explanation
- **User consent required** -- every device/resource access goes through a permission broker with optional biometric enforcement
- **Crash isolation** -- Python worker can die without taking down the app; Kotlin restarts it on demand
- **Offline by default** -- everything runs locally except explicit cloud API calls
- **Audit trail** -- all tool invocations and permission decisions are logged

## Security

- **Credential encryption** -- API keys and SSH keys encrypted with Android Keystore (AES-GCM); ciphertext stored in Room DB
- **Verified device ownership** -- Google Sign-In via Credential Manager for me.me auto-approve; identity stored in encrypted vault
- **End-to-end encryption** -- me.me messages encrypted with AES-GCM; session keys derived from X25519/ECDH via HKDF-SHA256; WebRTC P2P uses DTLS transport encryption
- **Permission gating** -- tool invocations requiring device access trigger user consent prompts
- **Biometric option** -- sensitive operations can require fingerprint/face authentication
- **Scoped access** -- file operations scoped to user root or app-private directories
- **Key lifecycle** -- encryption keys tied to device; removed on app uninstall

## Requirements

- Android 8.0+ (API 26+), targeting Android 14 (API 34)
- arm64-v8a architecture
- Google Play Services (for Google Sign-In and Nearby Connections)

## Tech Stack

**Android / Kotlin:**
Gradle, Android SDK 34, Kotlin, NanoHTTPD, Room, CameraX, TFLite, AndroidX Credentials, Firebase Cloud Messaging, Stream WebRTC Android

**Python (on-device):**
CPython 3.11+ (Python-for-Android), FastAPI, Uvicorn, Pydantic

**Native (NDK):**
Dropbear SSH, libusb, libuvc, custom C launchers

## Project Structure

```
app/                    Android project (Kotlin + native)
server/                 Python local service & agent runtime
user/                   User-modifiable defaults, docs, OpenAPI spec
scripts/                Build scripts (P4A, Dropbear, libusb, libuvc)
docs/                   Design and debugging docs
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

3. Set up local build config:
   ```
   mkdir .local_config
   ```

   Create `.local_config/local.env` with your build variables:
   ```
   GOOGLE_WEB_CLIENT_ID=your-web-client-id.apps.googleusercontent.com
   ```

   Place your Firebase config:
   ```
   cp /path/to/google-services.json .local_config/google-services.json
   ```

   The `.local_config/` directory is gitignored. See [Local Build Config](#local-build-config) below for details.

4. Build the APK (native libs are compiled automatically):
   ```
   cd app/android
   ./gradlew assembleDebug
   ```

The build process automatically handles:
- Copying `google-services.json` from `.local_config/` (or decoding from env var on CI)
- Loading build variables from `.local_config/local.env` (env vars take precedence)
- Syncing Python server code and user defaults into assets
- Compiling native libraries (Dropbear, libusb, libuvc)
- Building Python facade wheels for Android bindings

### Output

- APK: `app/android/app/build/outputs/apk/`
- Package: `jp.espresso3389.methings`
- Architecture: arm64-v8a only

## Local Build Config

The `.local_config/` directory (gitignored) holds per-developer build secrets:

```
.local_config/
  local.env              # KEY=VALUE pairs loaded by build.gradle.kts
  google-services.json   # Firebase config (copied into app/ at build time)
```

### `.local_config/local.env`

Environment variables override values in this file. Supported keys:

| Key | Description |
|---|---|
| `GOOGLE_WEB_CLIENT_ID` | Google OAuth Web Client ID for Credential Manager sign-in |

### `.local_config/google-services.json`

Download from [Firebase Console](https://console.firebase.google.com/) -> Project Settings -> Your apps -> Android (`jp.espresso3389.methings`).

### Google Cloud / Firebase Setup

To enable Google Sign-In for verified device ownership:

1. Create a project in [Google Cloud Console](https://console.cloud.google.com/)
2. Configure **APIs & Services -> OAuth consent screen** (External)
3. Create OAuth Client IDs under **APIs & Services -> Credentials**:
   - **Web application** -- use this Client ID as `GOOGLE_WEB_CLIENT_ID`
   - **Android** (debug) -- package `jp.espresso3389.methings` + debug keystore SHA-1:
     ```
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
     ```
   - **Android** (release) -- same package + release keystore SHA-1
4. Add a Firebase Android app with the same package name to get `google-services.json`

## GitHub Actions (CI)

Set these in your repository settings:

### Variables (`vars.*`)

| Variable | Description |
|---|---|
| `GOOGLE_WEB_CLIENT_ID` | Google OAuth Web Client ID (not secret -- embedded in APK) |

### Secrets (`secrets.*`)

| Secret | Description | How to encode |
|---|---|---|
| `GOOGLE_SERVICES_JSON_BASE64` | Firebase config | `base64 -w0 .local_config/google-services.json` |
| `ANDROID_KEYSTORE_BASE64` | Release signing keystore | `base64 -w0 /path/to/release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password | |
| `ANDROID_KEY_ALIAS` | Key alias | |
| `ANDROID_KEY_PASSWORD` | Key password | |

Example workflow snippet:

```yaml
env:
  GOOGLE_WEB_CLIENT_ID: ${{ vars.GOOGLE_WEB_CLIENT_ID }}
  GOOGLE_SERVICES_JSON_BASE64: ${{ secrets.GOOGLE_SERVICES_JSON_BASE64 }}
  ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
  ANDROID_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
  ANDROID_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
```

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

See [LICENSE](LICENSE) for details.
