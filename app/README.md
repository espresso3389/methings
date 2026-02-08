# Android App Skeleton

This is a minimal native wrapper that hosts a WebView UI and a foreground service.

## Notes
- Package name: jp.espresso3389.methings (change as needed)
- WebView loads `file:///android_asset/www/index.html`
- Foreground service bootstraps Python-for-Android and starts local HTTP service
- Server assets are bundled under `app/src/main/assets/server`

## Next Steps
- Replace placeholder package name with your final application id.
- Bundle Python-for-Android runtime under `app/src/main/assets/pyenv`.
- Wire permission broker UI and audit logging.
