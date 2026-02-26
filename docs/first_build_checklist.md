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

## 5) Verify embedded Python runtime
- `run_js` (QuickJS) and `run_curl` (native HTTP) work out of the box — no extra setup needed
- `run_python` and `run_pip` require the embedded Python worker (python-for-android p4a dist)
- Verify the worker starts successfully: Settings → Agent Service should show worker status "ok"
- If the p4a dist is missing or incomplete, rebuild with the p4a recipe and redeploy
