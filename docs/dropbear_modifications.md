# Dropbear Modifications (Android App Sandbox)

This project bundles Dropbear SSH server to provide on-device SSH access. Dropbear’s default server assumes a traditional Linux user database, full PTY/tty ownership control, and system-level permissions. On Android (non-root, app sandbox), those assumptions do not hold. We maintain a forked Dropbear submodule with the required source changes.

Below is a concise explanation of what we change and why.

## Summary of Changes

1) **Auth user fallback (no /etc/passwd)**
   - **Files patched:** `src/svr-auth.c`, `src/loginrec.c`
   - **Why:** Android app sandboxes do not have real system users or `/etc/passwd` entries for arbitrary usernames. Dropbear normally rejects logins if `getpwnam()` fails. We accept the username, and if there is no passwd entry:
     - Use the app’s effective UID/GID.
     - Provide a minimal default home and shell.
   - **Impact:** Users can authenticate with authorized keys even when the username is not a system account (e.g., `kawasaki`).

2) **Disable strict pubkey file permission checks**
   - **Files patched:** `src/svr-authpubkey.c`
   - **Why:** Dropbear expects `authorized_keys` and its parent directories to be owned by the target user and to have restrictive modes. In the Android app sandbox, the ownership and permission model is constrained and can fail these checks even when keys are secure. We bypass the strict permission checks and rely on the app’s private storage permissions instead.
   - **Impact:** Authorized keys stored in app-private storage are accepted.

3) **Avoid `setegid` / `seteuid`**
   - **Files patched:** `src/svr-authpubkey.c`
   - **Why:** Android’s app sandbox + seccomp policies can block `setegid()`/`seteuid()` in this context. Calls were causing child crashes (`SIGSYS`).
   - **Impact:** Dropbear no longer attempts to swap IDs when reading `authorized_keys`; it runs as the app user throughout.

4) **Relax login record user lookup**
   - **Files patched:** `src/loginrec.c`
   - **Why:** `login_init_entry()` calls `getpwnam()` and hard-exits if it fails, which it will for app sandbox users. We fall back to the current effective UID instead of exiting.
   - **Impact:** Avoids aborts during login tracking.

5) **Graceful PTY handling in Android**
   - **Files patched:** `src/sshpty.c`, `src/svr-chansession.c`
   - **Why:** PTY allocation and ownership operations assume access to `/dev/pts`, chown/chmod, and controlling terminal ops. These are not permitted in the app sandbox and cause errors like:
     - `ioctl(TIOCSCTTY): I/O error`
     - `open /dev/tty failed`
     - `chown/chmod ... Permission denied`
   - **Fixes:**
     - Add Android-specific PTMX path (best-effort).
     - If PTY allocation or setup fails, **reject the PTY request** (so the client stays in no-PTY mode).
     - Downgrade `pty_setowner` fatal errors to warnings.
     - Skip `pty_setowner` if there is no passwd entry.
   - **Impact:** Non-interactive SSH commands work reliably, and PTY requests are rejected cleanly.

6) **Line-mode shell input echoing (no-PTY)**
   - **Files patched:** `src/svr-chansession.c`
   - **Why:** When the SSH client runs without a PTY, input is not echoed by the terminal driver. We provide a minimal line-mode loop to keep the shell usable.
   - **Fixes:**
     - Echo typed characters.
     - Handle backspace/delete.
     - Emit CRLF on Enter.
   - **Impact:** The `methings>` prompt behaves more like a basic terminal even without PTY support.

7) **Per-connection no-auth prompt (notification allow/deny)**
   - **Files patched:** `src/svr-auth.c`
   - **Why:** Allow a “no-auth” login to proceed only after the phone user explicitly approves the connection.
   - **How it works:**
     - Dropbear writes a request file in `DROPBEAR_NOAUTH_PROMPT_DIR` with `id\tuser\taddr\ttimestamp`.
     - The Android app polls the prompt directory and shows a notification with Allow/Deny actions.
     - The app writes `allow` or `deny` to `<id>.resp`, which Dropbear waits for (timeout via `DROPBEAR_NOAUTH_PROMPT_TIMEOUT`).
   - **Impact:** Each no-auth connection requires a phone prompt; if no response arrives, the login is denied.

8) **Time-limited PIN SSH (biometric grant)**
   - **Files patched:** `src/svr-authpasswd.c`
   - **Why:** Allow `ssh-copy-id` or other password-based tools to work for a short, user-approved window.
   - **How it works:**
     - The app writes a short-lived file `DROPBEAR_PIN_FILE` containing `"<expires_epoch> <pin>"`.
     - Dropbear compares the supplied password to the PIN if still valid.
     - The PIN file is deleted after a successful login (single use).
     - Password auth is compiled in a **PIN-only** mode to avoid `crypt()` on Android.
   - **Impact:** Password auth is accepted only with the correct PIN during the active window.

9) **Disable agent forwarding**
   - **Files patched:** `localoptions.h`
   - **Why:** Reduces attack surface and avoids features that depend on broader OS integration.

## Where the Patches Live

All modifications live in the forked Dropbear submodule and are built by:

- `third_party/dropbear` (git submodule)
- `scripts/build_dropbear.sh`

The build script:
1. Uses the `third_party/dropbear` submodule sources.
2. Builds Dropbear for Android ABIs and installs binaries into:
   - `app/android/app/src/main/assets/bin/<abi>/`
   - `app/android/app/src/main/jniLibs/<abi>/`

## How to Update Dropbear (Dev Workflow)

When you need to change Dropbear modifications, edit the submodule and commit there:

1. `cd third_party/dropbear`
2. Make changes, then `git commit` and `git push` to the fork.
3. Update the submodule pointer in this repo:
   - `git add third_party/dropbear`
   - `git commit -m "dropbear: bump submodule"`

## Setup (Submodule Init)

If the submodule is missing, initialize it explicitly:

- `git submodule update --init --recursive third_party/dropbear`

## Behavior Tradeoffs

- **No true interactive TTY**: Interactive shells that require a PTY may not behave perfectly. Non-PTY exec commands work normally.
- **Security model**: We rely on app-private storage + user consent for SSH enablement rather than OS-level user/perm checks.
- **Username mismatch**: The SSH username does not map to a system user; it is treated as a label only.

## Rationale

The goal is to provide a usable, secure SSH entry point within Android’s app sandbox **without root**. The changes are conservative: they remove assumptions that do not apply in this environment and avoid crashing on normal SSH flows.

If you want to review or adjust any of these changes, inspect the fork history in
`third_party/dropbear` and the build script `scripts/build_dropbear.sh`.
