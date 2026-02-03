# First Build Checklist

## 1) Build Python-for-Android runtime
- Run `scripts/build_p4a.sh` in Linux/WSL
- Verify `app/android/app/src/main/assets/pyenv/bin/python` exists

## 2) Sync server assets
- Ensure latest `server/app.py` and `server/storage/db.py` are copied into
  `app/android/app/src/main/assets/server/`

## 3) Android build
- Open `app/android` in Android Studio
- Set your final applicationId if needed
- Build and install on a device (Android 14+)

## 4) Runtime smoke check (device)
- Launch the app
- Confirm status shows “Local service online”
- Click “Permissions → Approve” for a filesystem list and verify logs
- Run a shell command (ls/pwd/whoami)
- Configure OAuth provider and press Connect (Custom Tabs should open)

## 5) Optional local smoke test (desktop)
- Run `python server/app.py`
- Run `python scripts/smoke_test.py`
