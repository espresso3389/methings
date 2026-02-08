# Wheelhouse And Binary Wheels (Android)

## Goal
Make "normal pip" usage work on-device by preferring prebuilt wheels and avoiding
source builds on Android.

Key ideas:
- Offline-first: ship wheels inside the APK and install from a local wheelhouse.
- Explicit network: if we ever download wheels at runtime, it must be user-initiated
  and permission-gated.
- Native libs live in `jniLibs/` (Android packaging). Python packages should either:
  - be pure-Python, or
  - be shipped as wheels built for our Android ABI + CPython version.

## What Works Without Special Handling
- Pure-Python packages (example: `pyusb`) install normally if pip can reach an index,
  or if you pre-download the wheel/sdist into the wheelhouse.

## What Needs Infrastructure
Packages with native code (CPython extensions) generally cannot be built from sdist
on-device. For these, we need prebuilt wheels for:
- Our Python version (currently CPython 3.11)
- Our ABI (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)

Example: `opencv-python` typically has no Android wheels on PyPI, so "pip install"
will fail unless we ship our own wheel(s) or a facade wheel for dependency resolution.

## Wheelhouse Layout (Packaged In APK)
Wheels are bundled under:
- `app/android/app/src/main/assets/wheels/common/`
  - pure-Python, `py3-none-any` wheels
- `app/android/app/src/main/assets/wheels/<abi>/`
  - ABI-specific wheels (and/or Python-version-specific wheels)

At app/runtime install, we extract to app-private storage:
- `<filesDir>/wheelhouse/<abi>/bundled/` (extracted from APK assets)
- `<filesDir>/wheelhouse/<abi>/user/` (user-managed wheel cache; preserved across app updates)

The app sets pip discovery variables for subprocesses and SSH sessions:
- `KUGUTZ_WHEELHOUSE="<filesDir>/wheelhouse/<abi>/bundled <filesDir>/wheelhouse/<abi>/user"`
- `PIP_FIND_LINKS="<filesDir>/wheelhouse/<abi>/bundled <filesDir>/wheelhouse/<abi>/user"`

This makes pip able to resolve bundled wheels without internet access.

## Recommended Pip Modes
1. Offline install from our wheelhouse only:
```bash
pip install --no-index <package>
```

2. When network is allowed, prefer binary wheels to avoid sdists:
```bash
pip install --prefer-binary <package>
```

3. If you want to force "wheel-only" (fail if no wheel is available):
```bash
pip install --only-binary=:all: <package>
```

## Adding New Binary Wheels
For a new package `foo` that includes native code:
1. Build or obtain wheels for each target ABI + Python version.
2. Put them into:
   - `app/android/app/src/main/assets/wheels/<abi>/` if ABI-specific, or
   - `app/android/app/src/main/assets/wheels/common/` if universal.
3. Rebuild the app so the wheelhouse assets are packaged.
4. Confirm extraction/install by wiping app storage and launching once.

Notes:
- If you are shipping wheels for private/native integrations, prefer keeping them
  small and avoid bundling duplicate shared libraries that already exist in `jniLibs/`.
- If a package depends on shared libs, ensure the runtime sets `LD_LIBRARY_PATH`
  to the Android `nativeLibraryDir` (methings does this for managed subprocesses).

## Facade Packages (When Needed)
Facade wheels are only for names that pip needs to resolve but that do not exist
as real packages on any index (example in this repo: `opencv-python`).

For standard ecosystem packages (`pyusb`, `pyuvc`, `opencv-python`), prefer shipping
real wheels for those names rather than a facade.

Note:
- `pyuvc` is currently not on PyPI; methings ships a small facade wheel so `pip install pyuvc`
  resolves offline from the bundled wheelhouse.
- `pupil-labs-uvc` is a common distribution name for libuvc bindings; methings also ships a facade
  wheel so `pip install pupil-labs-uvc` resolves offline from the bundled wheelhouse.

Facade wheel builder:
- `scripts/build_facade_wheels.py`
- outputs to `app/android/app/src/main/assets/wheels/common/`

## Local Pip APIs
The Kotlin control plane provides helper endpoints for managing wheels and installing packages:
- `GET /pip/status`
- `POST /pip/download` (downloads wheels to the user wheel cache; permission-gated)
- `POST /pip/install` (offline by default; can optionally use network; permission-gated)

## Current Implementation Pointers
- Wheelhouse extraction: `app/android/app/src/main/java/.../service/AssetExtractor.kt`
- Bundled-wheel bootstrap install (runs via embedded Python worker): `app/android/app/src/main/java/.../service/PythonRuntimeManager.kt`
- Env propagation for SSH: `app/android/app/src/main/java/.../service/SshdManager.kt`
