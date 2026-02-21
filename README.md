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
| **Code Execution** | Built-in QuickJS JavaScript engine (`run_js` with async/await, fetch, WebSocket, file I/O), native HTTP client (`run_curl`), general shell (`run_shell`), persistent PTY sessions (`shell_session`), Termux filesystem access (`termux_fs`), Python/pip via optional Termux |
| **SSH** | SSH server (via Termux OpenSSH) with PIN/key/no-auth modes; SSH client for remote exec and SCP |
| **Browser** | Agent-controllable WebView with screenshot, JS injection, tap/scroll simulation |
| **Cloud** | API broker with automatic secret injection from encrypted vault |
| **me.me** | Encrypted device-to-device messaging over WiFi/BLE/WebRTC P2P/relay, file transfer, auto-discovery, device provisioning via OAuth sign-in |
| **me.sync** | Full device state migration via QR code (Nearby Connections or LAN) |
| **Notifications** | Android notifications, webhooks, Firebase Cloud Messaging |

## Architecture

The app owns the entire control plane and built-in agent runtime. Code execution (`run_js`) and HTTP requests (`run_curl`) work natively. Termux is optional — it provides a general-purpose Linux environment for Python and SSH.

```
Android App (Foreground Service)
 +-- Control Plane (always up)
 |    +-- Local HTTP API (127.0.0.1:33389)
 |    +-- WebView UI
 |    +-- Permission Broker (consent + audit)
 |    +-- Credential Vault (Android Keystore AES-GCM)
 |    +-- me.me Engine (BLE + WiFi + WebRTC P2P + relay transport)
 |    +-- SSH Server (via Termux OpenSSH)
 |
 +-- Agent Runtime (built-in)
 |    +-- LlmClient (OpenAI + Anthropic SSE streaming)
 |    +-- Tool Router (device APIs, filesystem, JS engine, native HTTP, shell, cloud)
 |    +-- JsRuntime (QuickJS — run_js with async/await, fetch, WebSocket, file I/O)
 |    +-- AgentStorage (SQLite)
 |    +-- Scheduler (daemon/periodic/one_time code execution)
 |
 +-- Termux (optional, on-demand)
      +-- General shell (run_shell, shell_session, termux_fs)
      +-- Python environment (run_python, run_pip)
      +-- Worker HTTP server (127.0.0.1:8776)
      +-- SSH server (OpenSSH)
```

Every device capability is exposed as an HTTP endpoint on `127.0.0.1:33389`. The built-in agent (or any local client) calls these endpoints to interact with hardware. All sensitive operations require user consent through the permission broker.

## Key Design Principles

- **Outcome-first agents** -- the agent delivers the requested artifact or state change, not an explanation
- **User consent required** -- every device/resource access goes through a permission broker with optional biometric enforcement
- **Crash isolation** -- Termux can die without taking down the app or the agent; restarted on demand
- **Offline by default** -- everything runs locally except explicit cloud API calls
- **Audit trail** -- all tool invocations and permission decisions are logged

## Device Provisioning (Sign-In)

Sign in with Google or GitHub to link your devices to a single account. Provisioning unlocks:

- **Device management** -- all your provisioned devices appear in each other's device list with "Linked" status, visible in the toolbar and me.me peers modal
- **Automatic mutual trust** -- provisioned siblings auto-approve each other unconditionally for me.me connections (no manual toggle or on-device identity verification needed)
- **Enhanced P2P networking** -- sign-in auto-configures the WebRTC signaling token and TURN server credentials, enabling P2P DataChannel connections across NATs without manual setup
- **Server-side provider selection** -- the app opens a sign-in page (hosted on the gateway) in CustomTabs where the user picks Google or GitHub; browser credentials are available so existing sessions work seamlessly

Flow: App opens CustomTabs → gateway sign-in page → OAuth provider → callback deep link back to app → app claims provision token → receives signaling token + sibling device list → auto-configures P2P.

## Security

- **Credential encryption** -- API keys and SSH keys encrypted with Android Keystore (AES-GCM); ciphertext stored in Room DB
- **Device provisioning** -- server-side OAuth (Google/GitHub) binds devices to a user account; provisioned siblings auto-approve each other for me.me connections
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

**Android:**
Gradle, Android SDK 34, NanoHTTPD, Room, CameraX, TFLite, AndroidX Credentials, Firebase Cloud Messaging, Stream WebRTC Android, quickjs-kt (in-process JS engine)

**On-device (optional, via Termux):**
Linux shell, Python, package management, OpenSSH

**Native (NDK):**
libusb, libuvc, custom C launchers

## Project Structure

```
app/                    Android project (app + native)
server/                 On-device server code and tools
user/                   User-modifiable defaults, docs, OpenAPI spec
scripts/                Build scripts (native libs, utilities)
docs/                   Design and debugging docs
third_party/            Git submodules (libusb, libuvc)
```

## Building

### Prerequisites

- Android SDK (API 34+)
- Android NDK 29.0.14206865
- Host tools for build scripts

### Steps

1. Initialize submodules:
   ```
   git submodule update --init --recursive
   ```

2. Set up local build config:
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

3. Build the APK (native libs are compiled automatically):
   ```
   cd app/android
   ./gradlew assembleDebug
   ```

The build process automatically handles:
- Copying `google-services.json` from `.local_config/` (or decoding from env var on CI)
- Loading build variables from `.local_config/local.env` (env vars take precedence)
- Syncing server code and user defaults into assets
- Compiling native libraries (libusb, libuvc)

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

1. Create a project in [Google Cloud Console](https://console.cloud.google.com/)
2. Configure **APIs & Services -> OAuth consent screen** (External)
3. Create OAuth Client IDs under **APIs & Services -> Credentials**:
   - **Web application** -- used by the gateway for server-side OAuth provisioning flow. Also set as `GOOGLE_WEB_CLIENT_ID` for the Android build.
   - **Android** (debug) -- package `jp.espresso3389.methings` + debug keystore SHA-1:
     ```
     keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA1
     ```
   - **Android** (release) -- same package + release keystore SHA-1
4. Add a Firebase Android app with the same package name to get `google-services.json`
5. Set the Web Client ID and secret as `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` on the gateway service (see [methings-notify-gateway](https://github.com/espresso3389/methings-notify-gateway))

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
