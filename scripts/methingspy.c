/*
 * methingspy - Standalone Python launcher for Android app sandbox.
 *
 * This binary dlopen's libpython3.11.so and invokes Py_BytesMain,
 * giving a fully-functional python3 CLI within SSH sessions and
 * from the Kotlin control-plane ProcessBuilder.
 *
 * Environment setup:
 *   METHINGS_PYENV  - path to pyenv directory (auto-detected if unset)
 *   METHINGS_NATIVELIB - path to native lib directory (auto-detected if unset)
 *
 * Build:
 *   See scripts/build_methingspy_android.sh
 */
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

typedef int (*Py_BytesMain_t)(int, char **);

/* Resolve the pyenv directory.
 * Priority: METHINGS_PYENV > $HOME/../pyenv > /proc/self/exe heuristic. */
static int resolve_pyenv(char *out, size_t out_len) {
    const char *env = getenv("METHINGS_PYENV");
    if (env && env[0]) {
        snprintf(out, out_len, "%s", env);
        return 0;
    }
    /* Derive from HOME: HOME is typically <filesDir>/user, pyenv is <filesDir>/pyenv */
    const char *home = getenv("HOME");
    if (home && home[0]) {
        char tmp[PATH_MAX];
        snprintf(tmp, sizeof(tmp), "%s", home);
        char *slash = strrchr(tmp, '/');
        if (slash) {
            *slash = '\0';
            snprintf(out, out_len, "%s/pyenv", tmp);
            if (access(out, F_OK) == 0) {
                return 0;
            }
        }
    }
    /* Derive from METHINGS_HOME (same logic) */
    const char *khome = getenv("METHINGS_HOME");
    if (khome && khome[0]) {
        char tmp[PATH_MAX];
        snprintf(tmp, sizeof(tmp), "%s", khome);
        char *slash = strrchr(tmp, '/');
        if (slash) {
            *slash = '\0';
            snprintf(out, out_len, "%s/pyenv", tmp);
            if (access(out, F_OK) == 0) {
                return 0;
            }
        }
    }
    /* Try to derive from /proc/self/exe */
    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n > 0) {
        self[n] = '\0';
        char *slash = strrchr(self, '/');
        if (slash) {
            *slash = '\0';
            char *parent_slash = strrchr(self, '/');
            if (parent_slash) {
                *parent_slash = '\0';
                snprintf(out, out_len, "%s/pyenv", self);
                if (access(out, F_OK) == 0) {
                    return 0;
                }
                snprintf(out, out_len, "%s/files/pyenv", self);
                if (access(out, F_OK) == 0) {
                    return 0;
                }
            }
        }
    }
    return -1;
}

/* Resolve the native library directory containing libpython3.11.so. */
static int resolve_nativelib(char *out, size_t out_len) {
    const char *env = getenv("METHINGS_NATIVELIB");
    if (env && env[0]) {
        snprintf(out, out_len, "%s", env);
        return 0;
    }
    /* On Android, nativeLibraryDir is typically at
     * /data/app/<hash>/<pkg>-<hash>/lib/<abi>/
     * We can find it by checking where libpython3.11.so is already loaded. */
    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n > 0) {
        self[n] = '\0';
        /* If this binary is libmethingspy.so in nativeLibDir, it's the same dir */
        char *slash = strrchr(self, '/');
        if (slash) {
            *slash = '\0';
            char probe[PATH_MAX];
            snprintf(probe, sizeof(probe), "%s/libpython3.11.so", self);
            if (access(probe, F_OK) == 0) {
                snprintf(out, out_len, "%s", self);
                return 0;
            }
        }
    }
    return -1;
}

int main(int argc, char **argv) {
    char pyenv[PATH_MAX];
    char nativelib[PATH_MAX];
    char server_dir[PATH_MAX];
    char tmp[PATH_MAX * 4];

    if (resolve_pyenv(pyenv, sizeof(pyenv)) != 0) {
        fprintf(stderr, "methingspy: cannot find pyenv directory. Set METHINGS_PYENV.\n");
        return 1;
    }

    /* Set PYTHONHOME */
    setenv("PYTHONHOME", pyenv, 1);

    /* Build PYTHONPATH */
    /* server dir is sibling: <filesDir>/server */
    {
        char *p = strstr(pyenv, "/pyenv");
        if (p) {
            size_t prefix_len = (size_t)(p - pyenv);
            snprintf(server_dir, sizeof(server_dir), "%.*s/server", (int)prefix_len, pyenv);
        } else {
            server_dir[0] = '\0';
        }
    }

    if (server_dir[0]) {
        snprintf(tmp, sizeof(tmp), "%s:%s/site-packages:%s/modules:%s/stdlib.zip",
                 server_dir, pyenv, pyenv, pyenv);
    } else {
        snprintf(tmp, sizeof(tmp), "%s/site-packages:%s/modules:%s/stdlib.zip",
                 pyenv, pyenv, pyenv);
    }
    setenv("PYTHONPATH", tmp, 0); /* don't override if already set */

    /* Set SSL_CERT_FILE and PIP_CERT for pip/requests.
     * Priority:
     *  1) Managed bundle: <filesDir>/protected/ca/cacert.pem (if present)
     *  2) certifi's baked-in bundle in pyenv
     *
     * Don't override if already set by the caller. */
    {
        char cert_path[PATH_MAX];
        int set = 0;

        /* Try managed path derived from METHINGS_HOME/HOME (<filesDir>/user). */
        const char *khome = getenv("METHINGS_HOME");
        if (!khome || !khome[0]) {
            khome = getenv("HOME");
        }
        if (khome && khome[0]) {
            char base[PATH_MAX];
            snprintf(base, sizeof(base), "%s", khome);
            char *slash = strrchr(base, '/');
            if (slash) {
                *slash = '\0';
                snprintf(cert_path, sizeof(cert_path), "%s/protected/ca/cacert.pem", base);
                if (access(cert_path, R_OK) == 0) {
                    setenv("SSL_CERT_FILE", cert_path, 0);
                    setenv("PIP_CERT", cert_path, 0);
                    setenv("REQUESTS_CA_BUNDLE", cert_path, 0);
                    set = 1;
                }
            }
        }

        if (!set) {
            snprintf(cert_path, sizeof(cert_path), "%s/site-packages/certifi/cacert.pem", pyenv);
            if (access(cert_path, R_OK) == 0) {
                setenv("SSL_CERT_FILE", cert_path, 0);
                setenv("PIP_CERT", cert_path, 0);
                setenv("REQUESTS_CA_BUNDLE", cert_path, 0);
            }
        }
    }

    /* Wheelhouse: allow pip to resolve prebuilt wheels shipped with the app.
     *
     * The Android side sets METHINGS_WHEELHOUSE to one or more directories containing wheels
     * (e.g. an opencv-python shim wheel + its real payload).
     *
     * Don't override if already set by the caller. */
    {
        const char *wheelhouse = getenv("METHINGS_WHEELHOUSE");
        if (wheelhouse && wheelhouse[0]) {
            setenv("PIP_FIND_LINKS", wheelhouse, 0);
        }
    }

    /* Set LD_LIBRARY_PATH if we can find the native libs */
    if (resolve_nativelib(nativelib, sizeof(nativelib)) == 0) {
        const char *existing = getenv("LD_LIBRARY_PATH");
        if (existing && existing[0]) {
            snprintf(tmp, sizeof(tmp), "%s:%s", nativelib, existing);
        } else {
            snprintf(tmp, sizeof(tmp), "%s", nativelib);
        }
        setenv("LD_LIBRARY_PATH", tmp, 1);
    }

    /* Detect if invoked as pip/pip3 via argv[0] basename. */
    int is_pip = 0;
    {
        const char *base = strrchr(argv[0], '/');
        base = base ? base + 1 : argv[0];
        if (strcmp(base, "pip") == 0 || strcmp(base, "pip3") == 0) {
            is_pip = 1;
        }
    }

    /* Load libpython */
    void *handle = dlopen("libpython3.11.so", RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        fprintf(stderr, "methingspy: cannot load libpython3.11.so: %s\n", dlerror());
        return 1;
    }

    Py_BytesMain_t py_main = (Py_BytesMain_t)dlsym(handle, "Py_BytesMain");
    if (!py_main) {
        fprintf(stderr, "methingspy: Py_BytesMain not found: %s\n", dlerror());
        dlclose(handle);
        return 1;
    }

    if (is_pip) {
        /* Rewrite argv to: python3 -m pip <original args...> */
        int new_argc = argc + 2;
        char **new_argv = malloc(sizeof(char *) * (new_argc + 1));
        if (!new_argv) {
            fprintf(stderr, "methingspy: malloc failed\n");
            return 1;
        }
        new_argv[0] = "python3";
        new_argv[1] = "-m";
        new_argv[2] = "pip";
        for (int i = 1; i < argc; i++) {
            new_argv[i + 2] = argv[i];
        }
        new_argv[new_argc] = NULL;
        return py_main(new_argc, new_argv);
    }

    return py_main(argc, argv);
}
