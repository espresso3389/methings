# Dropbear Modifications (Android App Sandbox)

This project bundles Dropbear SSH server to provide on-device SSH access. Dropbear’s default server assumes a traditional Linux user database, full PTY/tty ownership control, and system-level permissions. On Android (non-root, app sandbox), those assumptions do not hold. We apply a small set of source patches at build time to make Dropbear usable inside the app’s private sandbox.

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
   - **Impact:** The `kugutz>` prompt behaves more like a basic terminal even without PTY support.

7) **Time-limited no-auth SSH (biometric grant)**
   - **Files patched:** `src/svr-auth.c`
   - **Why:** Support a short, user-approved window where SSH “none” auth succeeds without keys or passwords, gated by an explicit biometric confirmation in the app.
   - **How it works:**
     - The app writes a short-lived expiry timestamp to a file (seconds since epoch).
     - Dropbear checks `DROPBEAR_NOAUTH_FILE` on each `none` auth request.
   - **Impact:** No-auth login is allowed only within the configured window (default 10s).

8) **Per-connection no-auth prompt (notification allow/deny)**
   - **Files patched:** `src/svr-auth.c`
   - **Why:** Allow a “no-auth” login to proceed only after the phone user explicitly approves the connection.
   - **How it works:**
     - Dropbear writes a request file in `DROPBEAR_NOAUTH_PROMPT_DIR` with `id\tuser\taddr\ttimestamp`.
     - The Android app polls `/ssh/noauth/requests` and shows a notification with Allow/Deny actions.
     - The app writes `allow` or `deny` to `<id>.resp`, which Dropbear waits for (timeout via `DROPBEAR_NOAUTH_PROMPT_TIMEOUT`).
   - **Impact:** Each no-auth connection requires a phone prompt; if no response arrives, the login is denied.

9) **Time-limited PIN SSH (biometric grant)**
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

All modifications are applied during Dropbear build using:

- `scripts/dropbear.patch`
- `scripts/build_dropbear.sh`

The build script:
1. Downloads the latest Dropbear tarball.
2. Applies `scripts/dropbear.patch` with `patch -p1`.
3. Builds Dropbear for Android ABIs and installs binaries into:
   - `app/android/app/src/main/assets/bin/<abi>/`
   - `app/android/app/src/main/jniLibs/<abi>/`

## How to Update the Patch (Dev Workflow)

When you need to change Dropbear modifications, generate a fresh patch file from a clean source tree. This keeps the build script simple and the patch reviewable.

Recommended flow (single working tree):
1. Use `.dropbear-build/src` as the only working tree (the build script uses it too).
2. Initialize a git baseline there:
   - `git init`
   - `git add .`
   - `git commit -m "orig"`
3. Apply your edits in `.dropbear-build/src`.
4. Generate the patch:
   - `git -C .dropbear-build/src diff --patch > /home/kawasaki/work/kugut/scripts/dropbear.patch`
5. Reset the working tree back to the baseline (first commit) so builds use clean+patch:
   - `git -C .dropbear-build/src reset --hard $(git -C .dropbear-build/src rev-list --max-parents=0 HEAD | tail -n 1)`
   - `git -C .dropbear-build/src clean -fd`

Shortcut:
- `scripts/finalize_dropbear_patch.sh` (generates the patch, then resets and cleans)

After that, the build script will apply the patch automatically during builds.

## Behavior Tradeoffs

- **No true interactive TTY**: Interactive shells that require a PTY may not behave perfectly. Non-PTY exec commands work normally.
- **Security model**: We rely on app-private storage + user consent for SSH enablement rather than OS-level user/perm checks.
- **Username mismatch**: The SSH username does not map to a system user; it is treated as a label only.

## Rationale

The goal is to provide a usable, secure SSH entry point within Android’s app sandbox **without root**. The changes are conservative: they remove assumptions that do not apply in this environment and avoid crashing on normal SSH flows.

If you want to review or adjust any of these patches, check `scripts/build_dropbear.sh` and search for the patch blocks.
