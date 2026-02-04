#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ASSETS_DIR="$ROOT_DIR/app/android/app/src/main/assets/bin"
JNI_DIR="$ROOT_DIR/app/android/app/src/main/jniLibs"
WORK_DIR="$ROOT_DIR/.dropbear-build"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT/ndk" ]]; then
    ANDROID_NDK_HOME=$(ls -1d "$ANDROID_SDK_ROOT/ndk"/* 2>/dev/null | sort -V | tail -n 1)
    export ANDROID_NDK_HOME
  fi
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -d "$ANDROID_NDK_HOME" ]]; then
  echo "ANDROID_NDK_HOME not set and NDK not found under ANDROID_SDK_ROOT" >&2
  exit 1
fi

API_LEVEL=${DROPBEAR_ANDROID_API:-21}

mkdir -p "$WORK_DIR"

cd "$WORK_DIR"
if [[ ! -f dropbear.tar.bz2 ]]; then
  echo "Downloading dropbear..."
  curl -fsSL "https://matt.ucc.asn.au/dropbear/releases/" -o releases.html
  TARBALL=$(grep -oE 'dropbear-[0-9.]+\.tar\.bz2' releases.html | sort -V | tail -n 1 || true)
  if [[ -z "$TARBALL" ]]; then
    echo "Failed to discover dropbear tarball" >&2
    exit 1
  fi
  curl -fsSL "https://matt.ucc.asn.au/dropbear/releases/${TARBALL}" -o dropbear.tar.bz2
  echo "$TARBALL" > dropbear.version
fi

if [[ ! -d dropbear-src ]]; then
  mkdir -p dropbear-src
  tar -xjf dropbear.tar.bz2 -C dropbear-src --strip-components=1
fi

TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "NDK toolchain not found at $TOOLCHAIN" >&2
  exit 1
fi

write_localoptions() {
  cat > localoptions.h <<'OPT'
#define DROPBEAR_SVR_PASSWORD_AUTH 0
#define DROPBEAR_SVR_PAM_AUTH 0
#define DROPBEAR_SVR_PUBKEY_AUTH 1
#define DROPBEAR_SVR_MULTIUSER 1
#define DROPBEAR_CLI_PASSWORD_AUTH 0
#define DROPBEAR_SVR_AGENTFWD 0
#define DEBUG_TRACE 1
OPT
}

build_one() {
  local abi="$1"
  local triple="$2"
  local out_dir="$ASSETS_DIR/$abi"
  local jni_out="$JNI_DIR/$abi"
  local build_dir="$WORK_DIR/build-$abi"

  rm -rf "$build_dir"
  mkdir -p "$build_dir"
  cp -R dropbear-src/* "$build_dir/"
  pushd "$build_dir" >/dev/null
  python3 - <<'PY'
from pathlib import Path

auth_path = Path("src/svr-auth.c")
auth_text = auth_path.read_text()
old = """\t/* check that user exists */
\tif (!ses.authstate.pw_name) {
\t\tTRACE(("leave checkusername: user '%s' doesn't exist", username))
\t\tdropbear_log(LOG_WARNING,
\t\t\t\t"Login attempt for nonexistent user from %s",
\t\t\t\tsvr_ses.addrstring);
\t\tses.authstate.checkusername_failed = 1;
\t\treturn DROPBEAR_FAILURE;
\t}
"""
new = """\t/* check that user exists */
\tif (!ses.authstate.pw_name) {
\t\tuid_t euid = geteuid();
\t\tgid_t egid = getegid();
\t\tconst char *home = getenv("HOME");
\t\tTRACE(("checkusername: no passwd entry, using app uid"))
\t\tdropbear_log(LOG_WARNING,
\t\t\t\t"No passwd entry for user '%s', using app uid",
\t\t\t\tusername);
\t\tses.authstate.pw_uid = euid;
\t\tses.authstate.pw_gid = egid;
\t\tses.authstate.pw_name = m_strdup(username);
\t\tses.authstate.pw_dir = m_strdup(home ? home : "/");
\t\tses.authstate.pw_shell = m_strdup("/system/bin/sh");
\t\tses.authstate.pw_passwd = m_strdup("!!");
\t}
"""
if old not in auth_text:
    raise SystemExit("Failed to locate dropbear auth block for patching")
auth_text = auth_text.replace(old, new)
marker = 'shell is %s'
trace_idx = auth_text.find(marker)
shell_start = auth_text.rfind('TRACE((', 0, trace_idx)
shell_end = auth_text.find('goodshell:', shell_start)
if shell_start == -1 or shell_end == -1:
    raise SystemExit('Failed to locate dropbear shell validation block')
shell_repl = """\tTRACE((\"shell is %s\", ses.authstate.pw_shell))
\tgoto goodshell;

"""
auth_text = auth_text[:shell_start] + shell_repl + auth_text[shell_end:]
auth_path.write_text(auth_text)

pubkey_path = Path("src/svr-authpubkey.c")
pubkey_text = pubkey_path.read_text()
perms_start = pubkey_text.find('static int checkpubkeyperms()')
perms_end = pubkey_text.find('/* Checks that a file is owned by', perms_start)
if perms_start == -1 or perms_end == -1:
    raise SystemExit('Failed to locate dropbear pubkey perms block')
perms_repl = """static int checkpubkeyperms() {
\treturn DROPBEAR_SUCCESS;
}

"""
pubkey_text = pubkey_text[:perms_start] + perms_repl + pubkey_text[perms_end:]
set_block = """#if DROPBEAR_SVR_MULTIUSER
\t/* access the file as the authenticating user. */
\toriguid = getuid();
\toriggid = getgid();
\tif ((setegid(ses.authstate.pw_gid)) < 0 ||
\t\t(seteuid(ses.authstate.pw_uid)) < 0) {
\t\tdropbear_exit(\"Failed to set euid\");
\t}
#endif
"""
set_repl = """#if DROPBEAR_SVR_MULTIUSER
\t/* Avoid setegid/seteuid on Android app sandboxes */
\toriguid = getuid();
\toriggid = getgid();
#endif
"""
if set_block not in pubkey_text:
    raise SystemExit('Failed to locate dropbear setuid block')
pubkey_text = pubkey_text.replace(set_block, set_repl)
unset_block = """#if DROPBEAR_SVR_MULTIUSER
\tif ((seteuid(origuid)) < 0 ||
\t\t(setegid(origgid)) < 0) {
\t\tdropbear_exit(\"Failed to revert euid\");
\t}
#endif
"""
unset_repl = """#if DROPBEAR_SVR_MULTIUSER
\t(void)origuid;
\t(void)origgid;
#endif
"""
if unset_block not in pubkey_text:
    raise SystemExit('Failed to locate dropbear revert setuid block')
pubkey_text = pubkey_text.replace(unset_block, unset_repl)
pubkey_path.write_text(pubkey_text)

login_path = Path("src/loginrec.c")
login_text = login_path.read_text()
login_old = """\t\tpw = getpwnam(li->username);
\t\tif (pw == NULL)
\t\t\tdropbear_exit(\"login_init_entry: Cannot find user \\\"%s\\\"\",
\t\t\t\t\tli->username);
\t\tli->uid = pw->pw_uid;
"""
login_new = """\t\tpw = getpwnam(li->username);
\t\tif (pw == NULL) {
\t\t\tli->uid = geteuid();
\t\t} else {
\t\t\tli->uid = pw->pw_uid;
\t\t}
"""
if login_old not in login_text:
    raise SystemExit("Failed to locate loginrec user lookup block")
login_text = login_text.replace(login_old, login_new)
login_path.write_text(login_text)

chan_path = Path("src/svr-chansession.c")
chan_text = chan_path.read_text()
chan_old = """\tpw = getpwnam(ses.authstate.pw_name);
\tif (!pw)
\t\tdropbear_exit(\"getpwnam failed after succeeding previously\");
\tpty_setowner(pw, chansess->tty);
"""
chan_new = """\tpw = getpwnam(ses.authstate.pw_name);
\tif (pw) {
\t\tpty_setowner(pw, chansess->tty);
\t} else {
\t\tdropbear_log(LOG_WARNING, \"getpwnam failed, skipping pty_setowner\");
\t}
"""
if chan_old not in chan_text:
    raise SystemExit("Failed to locate chansession pty owner block")
chan_text = chan_text.replace(chan_old, chan_new)
pty_fail_old = """\tif (pty_allocate(&chansess->master, &chansess->slave, namebuf, 64) == 0) {
\t\tTRACE((\"leave sessionpty: failed to allocate pty\"))
\t\treturn DROPBEAR_FAILURE;
\t}
"""
pty_fail_new = """\tif (pty_allocate(&chansess->master, &chansess->slave, namebuf, 64) == 0) {
\t\tTRACE((\"leave sessionpty: failed to allocate pty\"))
#ifdef __ANDROID__
\t\tdropbear_log(LOG_WARNING, \"pty_allocate failed, falling back to no-pty\");
\t\tm_free(chansess->term);
\t\tchansess->term = NULL;
\t\treturn DROPBEAR_SUCCESS;
#else
\t\treturn DROPBEAR_FAILURE;
#endif
\t}
"""
if pty_fail_old not in chan_text:
    raise SystemExit("Failed to locate chansession pty allocate block")
chan_text = chan_text.replace(pty_fail_old, pty_fail_new)

pty_android_old = """\tchansess->term = buf_getstring(ses.payload, &termlen);
\tif (termlen > MAX_TERM_LEN) {
\t\t/* TODO send disconnect ? */
\t\tTRACE((\"leave sessionpty: term len too long\"))
\t\treturn DROPBEAR_FAILURE;
\t}
"""
pty_android_new = """\tchansess->term = buf_getstring(ses.payload, &termlen);
\tif (termlen > MAX_TERM_LEN) {
\t\t/* TODO send disconnect ? */
\t\tTRACE((\"leave sessionpty: term len too long\"))
\t\treturn DROPBEAR_FAILURE;
\t}
#ifdef __ANDROID__
\tdropbear_log(LOG_WARNING, \"pty requested but unsupported; falling back to no-pty\");
\tm_free(chansess->term);
\tchansess->term = NULL;
\treturn DROPBEAR_SUCCESS;
#endif
"""
if pty_android_old not in chan_text:
    raise SystemExit("Failed to locate chansession term block")
chan_text = chan_text.replace(pty_android_old, pty_android_new)
chan_path.write_text(chan_text)

pty_path = Path("src/sshpty.c")
pty_text = pty_path.read_text()
marker = "#else /* HAVE__GETPTY */"
marker_idx = pty_text.find(marker)
if marker_idx == -1:
    raise SystemExit("Failed to locate dropbear PTMX block for patching")
android_block = """#else /* HAVE__GETPTY */
#if defined(__ANDROID__)
\tint ptm;
\tchar *pts;

\tptm = open(\"/dev/ptmx\", O_RDWR | O_NOCTTY);
\tif (ptm < 0) {
\t\tdropbear_log(LOG_WARNING,
\t\t\t\t\"pty_allocate: /dev/ptmx: %.100s\", strerror(errno));
\t\treturn 0;
\t}
\tif (grantpt(ptm) < 0) {
\t\tdropbear_log(LOG_WARNING,
\t\t\t\t\"grantpt: %.100s\", strerror(errno));
\t\tclose(ptm);
\t\treturn 0;
\t}
\tif (unlockpt(ptm) < 0) {
\t\tdropbear_log(LOG_WARNING,
\t\t\t\t\"unlockpt: %.100s\", strerror(errno));
\t\tclose(ptm);
\t\treturn 0;
\t}
\tpts = ptsname(ptm);
\tif (pts == NULL) {
\t\tdropbear_log(LOG_WARNING,
\t\t\t\t\"Slave pty side name could not be obtained.\");
\t\tclose(ptm);
\t\treturn 0;
\t}
\tstrlcpy(namebuf, pts, namebuflen);
\t*ptyfd = ptm;

\t*ttyfd = open(namebuf, O_RDWR | O_NOCTTY);
\tif (*ttyfd < 0) {
\t\tdropbear_log(LOG_ERR,
\t\t\t\"error opening pts %.100s: %.100s\", namebuf, strerror(errno));
\t\tclose(*ptyfd);
\t\treturn 0;
\t}
\treturn 1;
"""
pty_text = pty_text[:marker_idx] + android_block + pty_text[marker_idx + len(marker):]
next_if = pty_text.find("#if defined(USE_DEV_PTMX)", marker_idx + len(android_block))
if next_if == -1:
    raise SystemExit("Failed to locate dropbear PTMX ifdef after marker")
pty_text = (
    pty_text[:next_if]
    + "#elif defined(USE_DEV_PTMX)"
    + pty_text[next_if + len("#if defined(USE_DEV_PTMX)"):]
)
pty_path.write_text(pty_text)

pty_owner_path = Path("src/sshpty.c")
pty_owner_text = pty_owner_path.read_text()
owner_old = """\t/* Determine the group to make the owner of the tty. */
\tgrp = getgrnam(\"tty\");
"""
owner_new = """\tif (!pw || !tty_name) {
\t\treturn;
\t}
\t/* Determine the group to make the owner of the tty. */
\tgrp = getgrnam(\"tty\");
"""
if owner_old not in pty_owner_text:
    raise SystemExit("Failed to locate pty_setowner header block")
pty_owner_text = pty_owner_text.replace(owner_old, owner_new, 1)
pty_owner_text = pty_owner_text.replace(
    "dropbear_exit(\"pty_setowner: stat(%.101s) failed: %.100s\",",
    "dropbear_log(LOG_WARNING, \"pty_setowner: stat(%.101s) failed: %.100s\",",
)
pty_owner_text = pty_owner_text.replace(
    "dropbear_exit(\"chown(%.100s, %u, %u) failed: %.100s\",",
    "dropbear_log(LOG_WARNING, \"chown(%.100s, %u, %u) failed: %.100s\",",
)
pty_owner_text = pty_owner_text.replace(
    "dropbear_exit(\"chmod(%.100s, 0%o) failed: %.100s\",",
    "dropbear_log(LOG_WARNING, \"chmod(%.100s, 0%o) failed: %.100s\",",
)
pty_owner_path.write_text(pty_owner_text)

exec_path = Path("src/svr-chansession.c")
exec_text = exec_path.read_text()
exec_old = """\tusershell = m_strdup(get_user_shell());
\trun_shell_command(chansess->cmd, ses.maxfd, usershell);
"""
exec_new = """#if defined(__ANDROID__)
\tif (chansess->cmd == NULL && chansess->term == NULL) {
\t\tconst char *prompt = "kugutz> ";
\t\tchar linebuf[1024];
\t\twhile (1) {
\t\t\tsize_t pos = 0;
\t\t\t(void)write(1, prompt, strlen(prompt));
\t\t\twhile (1) {
\t\t\t\tchar ch;
\t\t\t\tssize_t r = read(0, &ch, 1);
\t\t\t\tif (r <= 0) {
\t\t\t\t\treturn;
\t\t\t\t}
\t\t\t\tif (ch == '\\r') {
\t\t\t\t\tcontinue;
\t\t\t\t}
\t\t\t\tif (ch == '\\n') {
\t\t\t\t\tbreak;
\t\t\t\t}
\t\t\t\tif (pos + 1 < sizeof(linebuf)) {
\t\t\t\t\tlinebuf[pos++] = ch;
\t\t\t\t}
\t\t\t}
\t\t\tlinebuf[pos] = '\\0';
\t\t\tif (pos == 0) {
\t\t\t\tcontinue;
\t\t\t}
\t\t\tif (strcmp(linebuf, "exit") == 0 || strcmp(linebuf, "logout") == 0) {
\t\t\t\treturn;
\t\t\t}
\t\t\tif (strncmp(linebuf, "cd ", 3) == 0) {
\t\t\t\tif (chdir(linebuf + 3) < 0) {
\t\t\t\t\tdprintf(2, "cd: %s\\n", strerror(errno));
\t\t\t\t}
\t\t\t\tcontinue;
\t\t\t}
\t\t\tpid_t pid = fork();
\t\t\tif (pid == 0) {
\t\t\t\texecl("/system/bin/sh", "sh", "-c", linebuf, (char *)NULL);
\t\t\t\t_exit(127);
\t\t\t} else if (pid > 0) {
\t\t\t\tint status = 0;
\t\t\t\t(void)waitpid(pid, &status, 0);
\t\t\t} else {
\t\t\t\tdprintf(2, "fork failed: %s\\n", strerror(errno));
\t\t\t}
\t\t}
\t}
#endif
\tusershell = m_strdup(get_user_shell());
\trun_shell_command(chansess->cmd, ses.maxfd, usershell);
"""
if exec_old not in exec_text:
    raise SystemExit("Failed to locate execchild shell command block")
exec_text = exec_text.replace(exec_old, exec_new)
exec_path.write_text(exec_text)
PY

  export CC="$TOOLCHAIN/bin/${triple}${API_LEVEL}-clang"
  export AR="$TOOLCHAIN/bin/llvm-ar"
  export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
  export STRIP="$TOOLCHAIN/bin/llvm-strip"
  export CFLAGS="-fPIE"
  export LDFLAGS="-pie"

  write_localoptions

  ./configure \
    --host="$triple" \
    --disable-zlib \
    --disable-lastlog \
    --disable-utmp \
    --disable-utmpx \
    --disable-wtmp \
    --disable-wtmpx \
    --disable-pututline \
    --disable-pututxline \
    --disable-loginfunc \
    --disable-syslog

  make PROGRAMS="dropbear dropbearkey"

  local dropbear_bin="dropbear"
  if [[ ! -f "$dropbear_bin" && -f "dropbearmulti" ]]; then
    dropbear_bin="dropbearmulti"
  fi
  if [[ ! -f "$dropbear_bin" ]]; then
    echo "Dropbear binary not found after build" >&2
    exit 1
  fi

  if [[ ! -f "dropbearkey" ]]; then
    echo "dropbearkey binary not found after build" >&2
    exit 1
  fi

  mkdir -p "$out_dir"
  cp -f "$dropbear_bin" "$out_dir/dropbear"
  cp -f "dropbearkey" "$out_dir/dropbearkey"
  "$STRIP" "$out_dir/dropbear" || true
  "$STRIP" "$out_dir/dropbearkey" || true
  chmod 0755 "$out_dir/dropbear" "$out_dir/dropbearkey"

  mkdir -p "$jni_out"
  cp -f "$dropbear_bin" "$jni_out/libdropbear.so"
  cp -f "dropbearkey" "$jni_out/libdropbearkey.so"
  "$STRIP" "$jni_out/libdropbear.so" || true
  "$STRIP" "$jni_out/libdropbearkey.so" || true
  chmod 0755 "$jni_out/libdropbear.so" "$jni_out/libdropbearkey.so"

  popd >/dev/null
}

build_one arm64-v8a aarch64-linux-android
build_one armeabi-v7a armv7a-linux-androideabi
build_one x86 i686-linux-android
build_one x86_64 x86_64-linux-android

echo "Dropbear binaries installed to $ASSETS_DIR and $JNI_DIR"
