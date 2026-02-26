/*
 * methings_run - Multicall binary for me.things (BusyBox-style).
 *
 * Dispatches to the correct runtime based on argv[0] (symlink name) or argv[1]:
 *   python, python3   → execv libmethingspy.so
 *   pip, pip3         → execv libmethingspy.so -m pip ...
 *   node, node20      → execv libnode.so (with LD_LIBRARY_PATH)
 *   npm               → execv libnode.so npm-cli.js ...
 *   npx               → execv libnode.so npx-cli.js ...
 *   corepack          → execv libnode.so corepack.js ...
 *   bash              → execv libbash.so (with LD_LIBRARY_PATH for readline)
 *   jq                → execv libjq-cli.so (with LD_LIBRARY_PATH)
 *   rg                → execv librg.so (with LD_LIBRARY_PATH for pcre2)
 *   curl              → execv libcurl-cli.so
 *
 * Symlinks in binDir point here (which lives in nativeLibDir, so SELinux
 * allows execution).
 *
 * Environment:
 *   METHINGS_NATIVELIB  - nativeLibraryDir path
 *   METHINGS_NODE_ROOT  - node runtime root (contains lib/, usr/)
 *   HOME                - user home directory
 *
 * Build: see scripts/build_methingsrun_android.sh
 */
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Resolve native library directory. */
static int resolve_nativelib(char *out, size_t len) {
    const char *env = getenv("METHINGS_NATIVELIB");
    if (env && env[0]) {
        snprintf(out, len, "%s", env);
        return 0;
    }
    /* Fallback: dirname of /proc/self/exe (this binary lives in nativeLibDir) */
    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n > 0) {
        self[n] = '\0';
        char *slash = strrchr(self, '/');
        if (slash) {
            *slash = '\0';
            snprintf(out, len, "%s", self);
            return 0;
        }
    }
    return -1;
}

/* Resolve node runtime root directory. */
static int resolve_node_root(char *out, size_t len) {
    const char *env = getenv("METHINGS_NODE_ROOT");
    if (env && env[0]) {
        snprintf(out, len, "%s", env);
        return 0;
    }
    const char *home = getenv("HOME");
    if (home && home[0]) {
        char tmp[PATH_MAX];
        snprintf(tmp, sizeof(tmp), "%s", home);
        char *slash = strrchr(tmp, '/');
        if (slash) {
            *slash = '\0';
            snprintf(out, len, "%s/node", tmp);
            if (access(out, F_OK) == 0) return 0;
        }
    }
    return -1;
}

/* Prepend dir to LD_LIBRARY_PATH (or set it). */
static void prepend_ld_path(const char *dir, const char *extra) {
    char buf[PATH_MAX * 4];
    const char *existing = getenv("LD_LIBRARY_PATH");
    if (extra) {
        if (existing && existing[0])
            snprintf(buf, sizeof(buf), "%s:%s:%s", dir, extra, existing);
        else
            snprintf(buf, sizeof(buf), "%s:%s", dir, extra);
    } else {
        if (existing && existing[0])
            snprintf(buf, sizeof(buf), "%s:%s", dir, existing);
        else
            snprintf(buf, sizeof(buf), "%s", dir);
    }
    setenv("LD_LIBRARY_PATH", buf, 1);
}

/* Set NPM_CONFIG_* env vars from HOME. */
static void set_npm_env(void) {
    const char *home = getenv("HOME");
    if (!home || !home[0]) return;
    char buf[PATH_MAX];
    snprintf(buf, sizeof(buf), "%s/npm-prefix", home);
    setenv("NPM_CONFIG_PREFIX", buf, 0);
    snprintf(buf, sizeof(buf), "%s/npm-cache", home);
    setenv("NPM_CONFIG_CACHE", buf, 0);
}

/* Exec python via libmethingspy.so. */
static int do_python(int argc, char **argv, const char *nativelib) {
    char exe[PATH_MAX];
    snprintf(exe, sizeof(exe), "%s/libmethingspy.so", nativelib);
    argv[0] = "python3";
    execv(exe, argv);
    perror("methings_run: execv python");
    return 127;
}

/* Exec pip via libmethingspy.so -m pip. */
static int do_pip(int argc, char **argv, const char *nativelib) {
    char exe[PATH_MAX];
    snprintf(exe, sizeof(exe), "%s/libmethingspy.so", nativelib);
    /* Build new argv: python3 -m pip <original args...> */
    int new_argc = argc + 2;
    char **new_argv = malloc(sizeof(char *) * (new_argc + 1));
    if (!new_argv) { fprintf(stderr, "methings_run: malloc failed\n"); return 1; }
    new_argv[0] = "python3";
    new_argv[1] = "-m";
    new_argv[2] = "pip";
    for (int i = 1; i < argc; i++)
        new_argv[i + 2] = argv[i];
    new_argv[new_argc] = NULL;
    execv(exe, new_argv);
    perror("methings_run: execv pip");
    return 127;
}

/* Exec node via libnode.so. */
static int do_node(int argc, char **argv, const char *nativelib,
                   const char *node_root) {
    char node_lib[PATH_MAX];
    snprintf(node_lib, sizeof(node_lib), "%s/lib", node_root);
    prepend_ld_path(node_lib, nativelib);

    char exe[PATH_MAX];
    snprintf(exe, sizeof(exe), "%s/libnode.so", nativelib);
    argv[0] = "node";
    execv(exe, argv);
    perror("methings_run: execv node");
    return 127;
}

/* Exec npm via libnode.so + npm-cli.js. */
static int do_npm(int argc, char **argv, const char *nativelib,
                  const char *node_root) {
    char node_lib[PATH_MAX];
    snprintf(node_lib, sizeof(node_lib), "%s/lib", node_root);
    prepend_ld_path(node_lib, nativelib);
    set_npm_env();

    char exe[PATH_MAX];
    snprintf(exe, sizeof(exe), "%s/libnode.so", nativelib);
    char cli_js[PATH_MAX];
    snprintf(cli_js, sizeof(cli_js),
             "%s/usr/lib/node_modules/npm/bin/npm-cli.js", node_root);

    int new_argc = argc + 1;
    char **new_argv = malloc(sizeof(char *) * (new_argc + 1));
    if (!new_argv) { fprintf(stderr, "methings_run: malloc failed\n"); return 1; }
    new_argv[0] = "node";
    new_argv[1] = cli_js;
    for (int i = 1; i < argc; i++)
        new_argv[i + 1] = argv[i];
    new_argv[new_argc] = NULL;
    execv(exe, new_argv);
    perror("methings_run: execv npm");
    return 127;
}

/* Exec npx via libnode.so + npx-cli.js. */
static int do_npx(int argc, char **argv, const char *nativelib,
                  const char *node_root) {
    char node_lib[PATH_MAX];
    snprintf(node_lib, sizeof(node_lib), "%s/lib", node_root);
    prepend_ld_path(node_lib, nativelib);
    set_npm_env();

    char exe[PATH_MAX];
    snprintf(exe, sizeof(exe), "%s/libnode.so", nativelib);
    char cli_js[PATH_MAX];
    snprintf(cli_js, sizeof(cli_js),
             "%s/usr/lib/node_modules/npm/bin/npx-cli.js", node_root);

    int new_argc = argc + 1;
    char **new_argv = malloc(sizeof(char *) * (new_argc + 1));
    if (!new_argv) { fprintf(stderr, "methings_run: malloc failed\n"); return 1; }
    new_argv[0] = "node";
    new_argv[1] = cli_js;
    for (int i = 1; i < argc; i++)
        new_argv[i + 1] = argv[i];
    new_argv[new_argc] = NULL;
    execv(exe, new_argv);
    perror("methings_run: execv npx");
    return 127;
}

/* Exec corepack via libnode.so + corepack.js. */
static int do_corepack(int argc, char **argv, const char *nativelib,
                       const char *node_root) {
    char node_lib[PATH_MAX];
    snprintf(node_lib, sizeof(node_lib), "%s/lib", node_root);
    prepend_ld_path(node_lib, nativelib);

    char exe[PATH_MAX];
    snprintf(exe, sizeof(exe), "%s/libnode.so", nativelib);
    char cli_js[PATH_MAX];
    snprintf(cli_js, sizeof(cli_js),
             "%s/usr/lib/node_modules/corepack/dist/corepack.js", node_root);

    int new_argc = argc + 1;
    char **new_argv = malloc(sizeof(char *) * (new_argc + 1));
    if (!new_argv) { fprintf(stderr, "methings_run: malloc failed\n"); return 1; }
    new_argv[0] = "node";
    new_argv[1] = cli_js;
    for (int i = 1; i < argc; i++)
        new_argv[i + 1] = argv[i];
    new_argv[new_argc] = NULL;
    execv(exe, new_argv);
    perror("methings_run: execv corepack");
    return 127;
}

/* Resolve termux-tools lib directory ($HOME/../termux-tools/lib). */
static int resolve_termux_tools_lib(char *out, size_t len) {
    const char *env = getenv("METHINGS_TERMUX_TOOLS_LIB");
    if (env && env[0]) {
        snprintf(out, len, "%s", env);
        return 0;
    }
    const char *home = getenv("HOME");
    if (home && home[0]) {
        char tmp[PATH_MAX];
        snprintf(tmp, sizeof(tmp), "%s", home);
        char *slash = strrchr(tmp, '/');
        if (slash) {
            *slash = '\0';
            snprintf(out, len, "%s/termux-tools/lib", tmp);
            if (access(out, F_OK) == 0) return 0;
        }
    }
    return -1;
}

/* Exec a Termux-sourced tool (bash, jq, rg). Needs LD_LIBRARY_PATH for deps. */
static int do_termux_tool(int argc, char **argv, const char *nativelib,
                          const char *so_name, const char *argv0_name) {
    char tools_lib[PATH_MAX];
    if (resolve_termux_tools_lib(tools_lib, sizeof(tools_lib)) == 0)
        prepend_ld_path(tools_lib, nativelib);
    else
        prepend_ld_path(nativelib, NULL);

    char exe[PATH_MAX];
    snprintf(exe, sizeof(exe), "%s/%s", nativelib, so_name);
    argv[0] = (char *)argv0_name;
    execv(exe, argv);
    fprintf(stderr, "methings_run: execv %s: %s\n", argv0_name, strerror(errno));
    return 127;
}

/* Exec curl via libcurl-cli.so. Sets CURL_CA_BUNDLE if not already set. */
static int do_curl(int argc, char **argv, const char *nativelib) {
    /* Provide CA bundle path so TLS verification works out of the box.
     * CaBundleManager maintains cacert.pem at $HOME/../protected/ca/cacert.pem.
     * SSH sessions already set SSL_CERT_FILE, but direct invocations may not. */
    if (!getenv("CURL_CA_BUNDLE") && !getenv("SSL_CERT_FILE")) {
        const char *home = getenv("HOME");
        if (home && home[0]) {
            char ca[PATH_MAX];
            snprintf(ca, sizeof(ca), "%s", home);
            char *slash = strrchr(ca, '/');
            if (slash) {
                *slash = '\0';
                char ca_path[PATH_MAX];
                snprintf(ca_path, sizeof(ca_path),
                         "%s/protected/ca/cacert.pem", ca);
                if (access(ca_path, R_OK) == 0)
                    setenv("CURL_CA_BUNDLE", ca_path, 0);
            }
        }
    }
    char exe[PATH_MAX];
    snprintf(exe, sizeof(exe), "%s/libcurl-cli.so", nativelib);
    argv[0] = "curl";
    execv(exe, argv);
    perror("methings_run: execv curl");
    return 127;
}

/* Dispatch a command name to the appropriate handler.
 * Returns -1 if the command is not recognized. */
static int dispatch(const char *cmd, int argc, char **argv) {
    char nativelib[PATH_MAX];
    if (resolve_nativelib(nativelib, sizeof(nativelib)) != 0) {
        fprintf(stderr, "methings_run: cannot resolve nativeLibDir. "
                        "Set METHINGS_NATIVELIB.\n");
        return 1;
    }

    if (strcmp(cmd, "python") == 0 || strcmp(cmd, "python3") == 0) {
        return do_python(argc, argv, nativelib);
    }
    if (strcmp(cmd, "pip") == 0 || strcmp(cmd, "pip3") == 0) {
        return do_pip(argc, argv, nativelib);
    }
    if (strcmp(cmd, "curl") == 0) {
        return do_curl(argc, argv, nativelib);
    }
    if (strcmp(cmd, "bash") == 0) {
        return do_termux_tool(argc, argv, nativelib, "libbash.so", "bash");
    }
    if (strcmp(cmd, "jq") == 0) {
        return do_termux_tool(argc, argv, nativelib, "libjq-cli.so", "jq");
    }
    if (strcmp(cmd, "rg") == 0) {
        return do_termux_tool(argc, argv, nativelib, "librg.so", "rg");
    }

    /* Node-based commands need node_root */
    char node_root[PATH_MAX];
    if (strcmp(cmd, "node") == 0 || strcmp(cmd, "node20") == 0) {
        if (resolve_node_root(node_root, sizeof(node_root)) != 0) {
            fprintf(stderr, "methings_run: cannot resolve node root. "
                            "Set METHINGS_NODE_ROOT.\n");
            return 1;
        }
        return do_node(argc, argv, nativelib, node_root);
    }
    if (strcmp(cmd, "npm") == 0) {
        if (resolve_node_root(node_root, sizeof(node_root)) != 0) {
            fprintf(stderr, "methings_run: cannot resolve node root. "
                            "Set METHINGS_NODE_ROOT.\n");
            return 1;
        }
        return do_npm(argc, argv, nativelib, node_root);
    }
    if (strcmp(cmd, "npx") == 0) {
        if (resolve_node_root(node_root, sizeof(node_root)) != 0) {
            fprintf(stderr, "methings_run: cannot resolve node root. "
                            "Set METHINGS_NODE_ROOT.\n");
            return 1;
        }
        return do_npx(argc, argv, nativelib, node_root);
    }
    if (strcmp(cmd, "corepack") == 0) {
        if (resolve_node_root(node_root, sizeof(node_root)) != 0) {
            fprintf(stderr, "methings_run: cannot resolve node root. "
                            "Set METHINGS_NODE_ROOT.\n");
            return 1;
        }
        return do_corepack(argc, argv, nativelib, node_root);
    }

    return -1; /* not recognized */
}

static void usage(void) {
    fprintf(stderr,
        "Usage: methings_run <command> [args...]\n"
        "       <command> [args...]   (via symlink)\n"
        "\n"
        "Commands: python python3 pip pip3 node node20 npm npx corepack\n"
        "          curl bash jq rg\n");
}

int main(int argc, char **argv) {
    /* 1) Check basename(argv[0]) for symlink invocation */
    const char *base = strrchr(argv[0], '/');
    base = base ? base + 1 : argv[0];

    if (strcmp(base, "methings_run") != 0 &&
        strcmp(base, "libmethingsrun.so") != 0) {
        int rc = dispatch(base, argc, argv);
        if (rc != -1) return rc;
    }

    /* 2) Direct invocation: argv[1] is the command name */
    if (argc < 2) {
        usage();
        return 127;
    }

    const char *cmd = argv[1];
    /* Shift argv: argv[1] becomes argv[0] */
    int rc = dispatch(cmd, argc - 1, argv + 1);
    if (rc == -1) {
        fprintf(stderr, "methings_run: unknown command '%s'\n", cmd);
        usage();
        return 127;
    }
    return rc;
}
