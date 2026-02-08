# Python/Pip on SSH Sessions

This document describes how `python3` and `pip` work within methings SSH sessions on Android, and the technical challenges that were solved.

## Overview

Users can SSH into the device and run Python directly:

```
$ ssh methings@device -p 2222 "python3 --version"
Python 3.11.5

$ ssh methings@device -p 2222 "pip install rich"
Successfully installed rich-14.3.2 ...

$ ssh methings@device -p 2222 "python3 -c 'import json; print(json.dumps({\"ok\": True}))'"
{"ok": true}
```

All four aliases work: `python3`, `python`, `pip`, `pip3`.

## Architecture

```
SSH client
  |
  v
Dropbear SSH (port 2222, nativeLibDir)
  |
  |  1. Saves env vars before clearenv()
  |  2. Restores env vars after clearenv()
  |  3. Prepends shell function preamble to commands
  |
  v
/system/bin/sh -c "python3(){ .../libmethingspy.so \"$@\"; }; ... ; <user command>"
  |
  v
libmethingspy.so (nativeLibDir, correct SELinux context)
  |  - dlopen("libpython3.11.so")
  |  - dlsym("Py_BytesMain")
  |  - Auto-detects pyenv, sets PYTHONHOME/PYTHONPATH/SSL certs
  |  - Detects pip mode from argv[0]
  |
  v
Python 3.11.5 (p4a runtime in <filesDir>/pyenv/)
```

## Key Components

### libmethingspy.so (`scripts/methingspy.c`)

A standalone Python launcher compiled as a native library (named `lib*.so` to satisfy Android's APK packaging constraint). It:

- Uses `dlopen`/`dlsym` to load `libpython3.11.so` and call `Py_BytesMain`
- Auto-detects the pyenv directory from `METHINGS_PYENV`, `$HOME/../pyenv`, or `/proc/self/exe`
- Sets `PYTHONHOME`, `PYTHONPATH`, `LD_LIBRARY_PATH`, `SSL_CERT_FILE`, `PIP_CERT`
- Detects when invoked as `pip`/`pip3` (via `argv[0]` basename) and automatically injects `-m pip` arguments

Build: `scripts/build_methingspy_android.sh`

### Dropbear Modifications (`third_party/dropbear/src/svr-chansession.c`)

Two modifications to `execchild()`:

**1. Environment variable passthrough**: Dropbear calls `clearenv()` before setting up the child session environment, which wipes all inherited env vars. We save methings-specific variables with `m_strdup()` before `clearenv()` and restore them via `addnewvar()` after:

- `PATH`, `METHINGS_HOME`, `METHINGS_PYENV`, `METHINGS_NATIVELIB`
- `LD_LIBRARY_PATH`, `PYTHONHOME`, `PYTHONPATH`
- `SSL_CERT_FILE`, `PIP_CERT`

**2. Shell function injection**: Since we can't create executables named `python3` in any writable location (see SELinux section below), we prepend shell function definitions to every command:

```c
snprintf(methings_preamble, plen,
    "python3(){ %s/libmethingspy.so \"$@\"; }; "
    "python(){ python3 \"$@\"; }; "
    "pip(){ %s/libmethingspy.so -m pip \"$@\"; }; "
    "pip3(){ pip \"$@\"; }; ",
    nlib, nlib);
```

This applies to both non-interactive commands (`ssh host "cmd"`) and the inline Android mini-shell.

### SshdManager.kt

Sets all Python-related environment variables on the Dropbear `ProcessBuilder`, which Dropbear then passes through to child sessions:

- `METHINGS_PYENV`, `METHINGS_NATIVELIB`, `LD_LIBRARY_PATH`
- `PYTHONHOME`, `PYTHONPATH`
- `SSL_CERT_FILE`, `PIP_CERT` (pointing to `certifi/cacert.pem`)
- `PATH` includes `nativeLibraryDir`

### PythonRuntimeInstaller.kt

Extracts the p4a Python runtime from APK assets to `<filesDir>/pyenv/`. Ensures the `bin/` directory exists for other tools.

### Wheelhouse (optional)

To keep `pip` dependency resolution working for packages that require Android-native wheels (e.g. `opencv-python`),
the app can ship a local "wheelhouse" inside APK assets:

- `app/android/app/src/main/assets/wheels/common/*.whl` (pure-Python / universal wheels)
- `app/android/app/src/main/assets/wheels/<abi>/*.whl` (ABI-specific wheels)

At runtime this is extracted to:

- `<filesDir>/wheelhouse/<abi>/`

Bundled wheels from the APK are extracted into:

- `<filesDir>/wheelhouse/<abi>/bundled/`

User-downloaded wheels (cache) are stored in:

- `<filesDir>/wheelhouse/<abi>/user/`

Bundled extraction resets only the `bundled/` directory on app update; the `user/` cache is preserved.

and exported via env vars for both SSH sessions and `shell_exec`:

- `PIP_FIND_LINKS="<filesDir>/wheelhouse/<abi>/bundled <filesDir>/wheelhouse/<abi>/user"`
- `METHINGS_WHEELHOUSE="<filesDir>/wheelhouse/<abi>/bundled <filesDir>/wheelhouse/<abi>/user"`

In current implementation these env vars contain a whitespace-separated list of directories:

- `.../wheelhouse/<abi>/bundled .../wheelhouse/<abi>/user`

Note: in this repo the wheelhouse contents are generally treated as build artifacts and are gitignored.
The Android build runs `scripts/build_facade_wheels.py` to generate small facade wheels before packaging.

## Technical Challenges

### SELinux app_data_file Execution Restriction

Android SELinux policy assigns `app_data_file` context to all files in the app's private data directory (`/data/data/<pkg>/`). This context **blocks execution** of any file - binaries, shell scripts, and even symlinks that point to executable targets.

Only files in `nativeLibraryDir` (`/data/app/.../<pkg>-.../lib/<abi>/`) have the correct SELinux context (`apk_data_file`) to execute.

**Failed approaches:**
- Copying `libmethingspy.so` to `files/bin/python3` - blocked by `app_data_file`
- Shell wrapper scripts in `files/bin/` - also blocked
- Symlinks from `files/bin/python3` to `nativeLibDir/libmethingspy.so` - SELinux blocks traversal across contexts

**Working solution:** Shell function injection in Dropbear, mapping command names to the native library binary directly in `nativeLibraryDir`.

### Android lib*.so Naming Constraint

Android's `PackageManager` only extracts native libraries matching the pattern `lib*.so` from the APK into `nativeLibraryDir`. Files with other names are silently ignored. This is why the Python launcher is named `libmethingspy.so` rather than `python3`.

### Dropbear clearenv() Wipes Inherited Environment

Dropbear's `execchild()` calls `clearenv()` to sanitize the child process environment, then sets only standard variables (USER, HOME, SHELL, PATH). Any environment variables set on the Dropbear process (via Kotlin `ProcessBuilder`) are lost.

The fix saves variables with `m_strdup()` **before** `clearenv()` and restores them after. A previous attempt that called `getenv()` after `clearenv()` failed because the variables were already wiped.

### SSL Certificates on Android

Android doesn't have `/etc/ssl/certs/ca-certificates.crt`, which is the default path both Python's `ssl` module and pip's vendored certifi look for.

- Python's `ssl` module respects `SSL_CERT_FILE` env var
- pip's vendored `certifi.where()` returns the hardcoded `/etc/ssl/certs/` path (p4a modification), ignoring `SSL_CERT_FILE`
- pip respects its own `PIP_CERT` env var

We use a **managed CA bundle** stored under app-private storage:

- `<filesDir>/protected/ca/cacert.pem`

This file is initially **seeded** from `<pyenv>/site-packages/certifi/cacert.pem` (so pip works offline), then **periodically refreshed** by the Android side (WorkManager) by downloading the Mozilla-derived `cacert.pem` bundle from curl.se when the network is available.

For SSH sessions, Dropbear inherits:

- `SSL_CERT_FILE`, `PIP_CERT`, `REQUESTS_CA_BUNDLE` -> `<filesDir>/protected/ca/cacert.pem`

For the embedded Python worker, the native bridge sets the same env vars before launching `worker.py`.

### pip Build Isolation (Known Limitation)

Packages requiring C compilation (e.g., `pyyaml`) fail because pip's build isolation creates a subprocess with a fresh `PYTHONPATH` that doesn't include the custom p4a module layout (`stdlib.zip`, `modules/`). The subprocess can't find `encodings` and crashes.

**Pure-Python wheel packages install fine.** This covers the majority of useful packages (requests, httpx, rich, etc.).

## Build

### Prerequisites

- Android SDK + NDK (version 29+)
- Python-for-Android runtime assets in `app/android/app/src/main/assets/pyenv/`

### Build Scripts

```bash
# Build standalone Python launcher
./scripts/build_methingspy_android.sh

# Build Dropbear with methings patches
./scripts/build_dropbear.sh

# Full APK build (includes all native builds)
cd app/android && ./gradlew clean assembleDebug
```

The Gradle build (`preBuild`) automatically runs all native build scripts including `build_methingspy_android.sh` and `build_dropbear.sh`.
