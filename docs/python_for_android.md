# Build Python-for-Android Runtime (Draft)

This is a starter script outline. Adjust paths to your Android SDK/NDK and Python-for-Android setup.

## Prereqs
- Android SDK + NDK installed
- Python-for-Android available (p4a)

## Steps
1) Build runtime with p4a
2) Copy build output to `app/android/app/src/main/assets/pyenv`

## Example (Linux/macOS)
```
python -m pip install python-for-android
p4a apk --requirements=python3,fastapi,uvicorn,requests,pysqlcipher3 --arch=arm64-v8a --ndk-dir=$NDK --sdk-dir=$SDK
```

## Build Script Usage (Linux/WSL)
```
export SDK_DIR=/path/to/Android/Sdk
export NDK_DIR=/path/to/Android/Sdk/ndk/your-ndk-version
export ARCH=arm64-v8a
export ASSETS_PATH=/path/to/repo/app/android/app/src/main/assets/pyenv
./scripts/build_p4a.sh
```

## Tooling Note
- The build script uses uv/venv for Python tooling (no system pip).
## SQLCipher Note
- The script attempts to include `pysqlcipher3` by default.
- If SQLCipher headers are unavailable, it retries without `pysqlcipher3` and the app will fall back to standard sqlite.
- SQLCipher builds use custom p4a recipes in `scripts/recipes/`.

## Output Note
- The build copies the Python **bundle** (stdlib.zip + modules) to `assets/pyenv`.
- This is not a standalone `bin/python`. The Android runtime integration will need to load the bundle via a Python service, not spawn a system process.

## Windows Note
Python-for-Android is typically used on Linux. For Windows, use WSL or a Linux CI runner.

## Output Layout Expectation
Ensure `app/android/app/src/main/assets/pyenv/bin/python` exists.

## SQLCipher Notes
- If `SQLCIPHER_KEY_FILE` is present, the server attempts SQLCipher via `pysqlcipher3`.
- On Android, `SqlcipherKeyManager` generates a key, stores it encrypted with Android Keystore, and writes the plaintext key to `filesDir/secrets/sqlcipher.key` at runtime for Python to read.
