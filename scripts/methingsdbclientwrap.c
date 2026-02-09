#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Small exec wrapper for Dropbear dbclient.
 *
 * Why:
 * - scp's `-S` option requires a program path (no extra args), so we can't pass `-y`
 *   (auto-accept unknown host keys) directly.
 * - Executing scripts from app-private storage is often blocked on Android (noexec/SELinux),
 *   so this wrapper is built as a PIE binary and placed in nativeLibraryDir.
 */

int main(int argc, char **argv) {
    const char *nlib = getenv("METHINGS_NATIVELIB");
    char path[PATH_MAX];
    if (nlib && nlib[0]) {
        snprintf(path, sizeof(path), "%s/libdbclient.so", nlib);
    } else {
        /* Fallback: rely on PATH (unlikely). */
        snprintf(path, sizeof(path), "libdbclient.so");
    }

    /* Build argv = [dbclient, -y, -o BatchMode=yes, original args...] */
    char **nargv = (char **)calloc((size_t)argc + 5, sizeof(char *));
    if (!nargv) {
        fprintf(stderr, "alloc failed\n");
        return 1;
    }
    nargv[0] = path;
    nargv[1] = (char *)"-y";
    /* No TTY in the app shell, so don't ever block waiting for password input. */
    nargv[2] = (char *)"-o";
    nargv[3] = (char *)"BatchMode=yes";
    for (int i = 1; i < argc; i++) {
        nargv[i + 3] = argv[i];
    }
    nargv[argc + 3] = NULL;

    execv(path, nargv);
    fprintf(stderr, "%s: exec failed: %s\n", path, strerror(errno));
    return 127;
}
