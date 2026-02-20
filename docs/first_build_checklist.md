# First Build Checklist

## 1) Sync server assets
- Ensure latest `server/app.py` and `server/storage/db.py` are copied into
  `app/android/app/src/main/assets/server/`
- (Gradle preBuild handles this automatically)

## 2) Build native USB libs
- Run `NDK_DIR=... ./scripts/build_libusb_android.sh`
- Run `NDK_DIR=... ./scripts/build_libuvc_android.sh`
- Verify `app/android/app/src/main/jniLibs/<ABI>/libusb1.0.so` and
  `app/android/app/src/main/jniLibs/<ABI>/libuvc.so` exist

## 3) Android build
- Open `app/android` in Android Studio
- Set your final applicationId if needed
- Build and install on a device (Android 14+)

## 4) Runtime smoke check (device)
- Launch the app
- Confirm the UI loads (from `files/www`)
- Toggle SSHD and verify status shows running
- Start the agent and send a test chat message
- Test SSH login (public-key, notification-based no-auth, or PIN auth)

## 5) Optional: Termux setup
- `run_js` (QuickJS) and `run_curl` (native HTTP) work out of the box — no Termux needed
- Install Termux only if you need `run_python` or `run_pip`
- Verify Termux status shows "ok" in Settings → Agent Service
