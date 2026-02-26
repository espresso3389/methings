#include <arpa/inet.h>
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

static const char *prompt = "methings> ";

static void print_error(const char *msg) {
    if (msg) {
        fprintf(stderr, "error: %s\n", msg);
    }
}

static int ensure_within_root(const char *root, const char *path) {
    size_t root_len = strlen(root);
    return strncmp(root, path, root_len) == 0 &&
           (path[root_len] == '/' || path[root_len] == '\0');
}

static int normalize_path(const char *root, const char *cwd, const char *input, char *out, size_t out_len) {
    char tmp[PATH_MAX];
    if (!input || input[0] == '\0') {
        return -1;
    }
    if (input[0] == '/') {
        snprintf(tmp, sizeof(tmp), "%s%s", root, input);
    } else {
        snprintf(tmp, sizeof(tmp), "%s/%s", cwd, input);
    }

    char *parts[PATH_MAX / 2];
    int count = 0;
    char buf[PATH_MAX];
    strncpy(buf, tmp, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = '\0';

    char *token = strtok(buf, "/");
    while (token) {
        if (strcmp(token, ".") == 0) {
        } else if (strcmp(token, "..") == 0) {
            if (count > 0) {
                count--;
            }
        } else {
            parts[count++] = token;
        }
        token = strtok(NULL, "/");
    }

    out[0] = '\0';
    strncat(out, "/", out_len - 1);
    for (int i = 0; i < count; i++) {
        if (strlen(out) + strlen(parts[i]) + 2 >= out_len) {
            return -1;
        }
        strcat(out, parts[i]);
        if (i != count - 1) {
            strcat(out, "/");
        }
    }

    if (!ensure_within_root(root, out)) {
        return -1;
    }
    return 0;
}

static int cmd_pwd(const char *cwd) {
    printf("%s\n", cwd);
    return 0;
}

static int cmd_whoami() {
    const char *user = getenv("USER");
    if (!user || user[0] == '\0') {
        user = "methings";
    }
    printf("%s\n", user);
    return 0;
}

static int cmd_ls(const char *path) {
    DIR *dir = opendir(path);
    if (!dir) {
        perror("ls");
        return 1;
    }
    struct dirent *ent;
    while ((ent = readdir(dir)) != NULL) {
        if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) {
            continue;
        }
        printf("%s\n", ent->d_name);
    }
    closedir(dir);
    return 0;
}

static int cmd_cat(const char *path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        perror("cat");
        return 1;
    }
    char buf[4096];
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        fwrite(buf, 1, (size_t)n, stdout);
    }
    close(fd);
    return 0;
}

static int cmd_echo(char **argv, int argc) {
    for (int i = 1; i < argc; i++) {
        if (i > 1) {
            printf(" ");
        }
        printf("%s", argv[i]);
    }
    printf("\n");
    return 0;
}

static int cmd_mkdir(const char *path) {
    if (mkdir(path, 0755) != 0) {
        perror("mkdir");
        return 1;
    }
    return 0;
}

static int cmd_touch(const char *path) {
    int fd = open(path, O_CREAT | O_RDWR, 0644);
    if (fd < 0) {
        perror("touch");
        return 1;
    }
    close(fd);
    return 0;
}

static int cmd_rm(const char *path, int recursive) {
    struct stat st;
    if (lstat(path, &st) != 0) {
        perror("rm");
        return 1;
    }
    if (S_ISDIR(st.st_mode)) {
        if (!recursive) {
            print_error("is a directory");
            return 1;
        }
        DIR *dir = opendir(path);
        if (!dir) {
            perror("rm");
            return 1;
        }
        struct dirent *ent;
        while ((ent = readdir(dir)) != NULL) {
            if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) {
                continue;
            }
            char child[PATH_MAX];
            snprintf(child, sizeof(child), "%s/%s", path, ent->d_name);
            cmd_rm(child, 1);
        }
        closedir(dir);
        if (rmdir(path) != 0) {
            perror("rmdir");
            return 1;
        }
    } else {
        if (unlink(path) != 0) {
            perror("rm");
            return 1;
        }
    }
    return 0;
}

static int cmd_cp(const char *src, const char *dst) {
    int in = open(src, O_RDONLY);
    if (in < 0) {
        perror("cp");
        return 1;
    }
    int out = open(dst, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (out < 0) {
        perror("cp");
        close(in);
        return 1;
    }
    char buf[4096];
    ssize_t n;
    while ((n = read(in, buf, sizeof(buf))) > 0) {
        if (write(out, buf, (size_t)n) != n) {
            perror("cp");
            close(in);
            close(out);
            return 1;
        }
    }
    close(in);
    close(out);
    return 0;
}

static int cmd_mv(const char *src, const char *dst) {
    if (rename(src, dst) != 0) {
        perror("mv");
        return 1;
    }
    return 0;
}

static void print_help() {
    printf("Supported commands:\n");
    printf("  pwd, ls, cat, echo, mkdir, rm, cp, mv, touch, cd, whoami, python, pip, uv, curl, help, exit\n");
}

static int split_line(char *line, char **argv, int max_args) {
    int argc = 0;
    char *token = strtok(line, " \t\r\n");
    while (token && argc < max_args) {
        argv[argc++] = token;
        token = strtok(NULL, " \t\r\n");
    }
    return argc;
}

static void trim_trailing_ws(char *s) {
    size_t n = strlen(s);
    while (n > 0 && isspace((unsigned char)s[n - 1])) {
        s[n - 1] = '\0';
        n--;
    }
}

static void extract_raw_args(const char *line, char *out, size_t out_len) {
    if (!line || !out || out_len == 0) {
        return;
    }
    const char *p = line;
    while (*p && isspace((unsigned char)*p)) {
        p++;
    }
    while (*p && !isspace((unsigned char)*p)) {
        p++;
    }
    while (*p && isspace((unsigned char)*p)) {
        p++;
    }
    snprintf(out, out_len, "%s", p);
    trim_trailing_ws(out);
}

static int json_escape(const char *src, char *dst, size_t dst_len) {
    size_t j = 0;
    if (!src || !dst || dst_len == 0) {
        return -1;
    }
    for (size_t i = 0; src[i] != '\0'; i++) {
        unsigned char c = (unsigned char)src[i];
        const char *rep = NULL;
        char hex[7];
        switch (c) {
            case '\\': rep = "\\\\"; break;
            case '\"': rep = "\\\""; break;
            case '\b': rep = "\\b"; break;
            case '\f': rep = "\\f"; break;
            case '\n': rep = "\\n"; break;
            case '\r': rep = "\\r"; break;
            case '\t': rep = "\\t"; break;
            default:
                if (c < 0x20) {
                    snprintf(hex, sizeof(hex), "\\u%04x", c);
                    rep = hex;
                }
                break;
        }
        if (rep) {
            size_t rlen = strlen(rep);
            if (j + rlen >= dst_len) {
                return -1;
            }
            memcpy(dst + j, rep, rlen);
            j += rlen;
        } else {
            if (j + 1 >= dst_len) {
                return -1;
            }
            dst[j++] = (char)c;
        }
    }
    dst[j] = '\0';
    return 0;
}

static int http_post_json(const char *host, int port, const char *path, const char *json, char *out, size_t out_len) {
    struct sockaddr_in addr;
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        return -1;
    }
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    if (inet_pton(AF_INET, host, &addr.sin_addr) != 1) {
        close(sock);
        return -1;
    }
    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(sock);
        return -1;
    }
    char header[1024];
    int body_len = (int)strlen(json);
    int header_len = snprintf(
        header,
        sizeof(header),
        "POST %s HTTP/1.1\r\nHost: %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
        path,
        host,
        body_len
    );
    if (write(sock, header, (size_t)header_len) < 0 || write(sock, json, (size_t)body_len) < 0) {
        close(sock);
        return -1;
    }
    ssize_t n;
    size_t total = 0;
    while ((n = read(sock, out + total, out_len - total - 1)) > 0) {
        total += (size_t)n;
        if (total >= out_len - 1) {
            break;
        }
    }
    out[total] = '\0';
    close(sock);
    return 0;
}

static int cmd_python_or_pip(const char *cmd, const char *raw_args, const char *cwd) {
    if (!cmd || cmd[0] == '\0') {
        return 1;
    }
    char cmd_esc[256];
    char args_esc[4096];
    char cwd_esc[PATH_MAX * 2];
    if (json_escape(cmd, cmd_esc, sizeof(cmd_esc)) != 0 ||
        json_escape(raw_args ? raw_args : "", args_esc, sizeof(args_esc)) != 0 ||
        json_escape(cwd ? cwd : "", cwd_esc, sizeof(cwd_esc)) != 0) {
        print_error("command too long");
        return 1;
    }
    char json[9216];
    snprintf(json, sizeof(json),
             "{ \"cmd\": \"%s\", \"args\": \"%s\", \"cwd\": \"%s\" }",
             cmd_esc, args_esc, cwd_esc);
    char resp[8192];
    if (http_post_json("127.0.0.1", 33389, "/shell/exec", json, resp, sizeof(resp)) != 0) {
        print_error("failed to reach local shell service");
        return 1;
    }
    char *body = strstr(resp, "\r\n\r\n");
    if (!body) {
        print_error("invalid response");
        return 1;
    }
    body += 4;
    printf("%s", body);
    size_t body_len = strlen(body);
    if (body_len == 0 || body[body_len - 1] != '\n') {
        printf("\n");
    }
    return 0;
}

static int run_shell_command(const char *cmd) {
    if (!cmd) {
        return 2;
    }
    // Use methings-sh (shebang-aware wrapper) so that scripts in filesDir
    // (blocked by SELinux app_data_file) are run through their interpreter.
    // We exec libmethingsrun.so directly (in nativeLibDir = apk_data_file)
    // with argv[0]="methings-sh" so the multicall dispatch picks it up.
    // We can't exec the symlink in binDir because it has app_data_file context.
    const char *nativelib = getenv("METHINGS_NATIVELIB");
    if (nativelib && nativelib[0]) {
        char exe[PATH_MAX];
        snprintf(exe, sizeof(exe), "%s/libmethingsrun.so", nativelib);
        execl(exe, "methings-sh", "-c", cmd, (char *)NULL);
        // If exec failed, fall through to plain sh.
    }
    execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
    perror("exec sh");
    return 127;
}

int main(int argc, char **argv) {
    if (argc >= 3 && (strcmp(argv[1], "-c") == 0 || strcmp(argv[1], "-lc") == 0)) {
        return run_shell_command(argv[2]);
    }
    const char *root_env = getenv("METHINGS_HOME");
    char root[PATH_MAX];
    if (!root_env || root_env[0] == '\0') {
        if (!getcwd(root, sizeof(root))) {
            print_error("cannot determine root");
            return 1;
        }
    } else {
        strncpy(root, root_env, sizeof(root) - 1);
        root[sizeof(root) - 1] = '\0';
    }

    char cwd[PATH_MAX];
    strncpy(cwd, root, sizeof(cwd) - 1);
    cwd[sizeof(cwd) - 1] = '\0';

    char line[1024];
    char line_raw[1024];
    char *argsv[64];

    while (1) {
        fputs(prompt, stdout);
        fflush(stdout);
        if (!fgets(line, sizeof(line), stdin)) {
            break;
        }
        strncpy(line_raw, line, sizeof(line_raw) - 1);
        line_raw[sizeof(line_raw) - 1] = '\0';
        int argcount = split_line(line, argsv, 64);
        if (argcount == 0) {
            continue;
        }
        const char *cmd = argsv[0];

        if (strcmp(cmd, "exit") == 0) {
            break;
        } else if (strcmp(cmd, "help") == 0) {
            print_help();
        } else if (strcmp(cmd, "pwd") == 0) {
            cmd_pwd(cwd);
        } else if (strcmp(cmd, "whoami") == 0) {
            cmd_whoami();
        } else if (strcmp(cmd, "ls") == 0) {
            const char *arg = (argcount > 1) ? argsv[1] : ".";
            char resolved[PATH_MAX];
            if (normalize_path(root, cwd, arg, resolved, sizeof(resolved)) != 0) {
                print_error("invalid path");
                continue;
            }
            cmd_ls(resolved);
        } else if (strcmp(cmd, "cat") == 0) {
            if (argcount < 2) {
                print_error("missing file");
                continue;
            }
            char resolved[PATH_MAX];
            if (normalize_path(root, cwd, argsv[1], resolved, sizeof(resolved)) != 0) {
                print_error("invalid path");
                continue;
            }
            cmd_cat(resolved);
        } else if (strcmp(cmd, "echo") == 0) {
            cmd_echo(argsv, argcount);
        } else if (strcmp(cmd, "mkdir") == 0) {
            if (argcount < 2) {
                print_error("missing path");
                continue;
            }
            char resolved[PATH_MAX];
            if (normalize_path(root, cwd, argsv[1], resolved, sizeof(resolved)) != 0) {
                print_error("invalid path");
                continue;
            }
            cmd_mkdir(resolved);
        } else if (strcmp(cmd, "touch") == 0) {
            if (argcount < 2) {
                print_error("missing path");
                continue;
            }
            char resolved[PATH_MAX];
            if (normalize_path(root, cwd, argsv[1], resolved, sizeof(resolved)) != 0) {
                print_error("invalid path");
                continue;
            }
            cmd_touch(resolved);
        } else if (strcmp(cmd, "rm") == 0) {
            int recursive = 0;
            int argi = 1;
            if (argcount > 1 && strcmp(argsv[1], "-r") == 0) {
                recursive = 1;
                argi = 2;
            }
            if (argcount <= argi) {
                print_error("missing path");
                continue;
            }
            char resolved[PATH_MAX];
            if (normalize_path(root, cwd, argsv[argi], resolved, sizeof(resolved)) != 0) {
                print_error("invalid path");
                continue;
            }
            cmd_rm(resolved, recursive);
        } else if (strcmp(cmd, "cp") == 0) {
            if (argcount < 3) {
                print_error("missing src/dst");
                continue;
            }
            char src[PATH_MAX];
            char dst[PATH_MAX];
            if (normalize_path(root, cwd, argsv[1], src, sizeof(src)) != 0 ||
                normalize_path(root, cwd, argsv[2], dst, sizeof(dst)) != 0) {
                print_error("invalid path");
                continue;
            }
            cmd_cp(src, dst);
        } else if (strcmp(cmd, "mv") == 0) {
            if (argcount < 3) {
                print_error("missing src/dst");
                continue;
            }
            char src[PATH_MAX];
            char dst[PATH_MAX];
            if (normalize_path(root, cwd, argsv[1], src, sizeof(src)) != 0 ||
                normalize_path(root, cwd, argsv[2], dst, sizeof(dst)) != 0) {
                print_error("invalid path");
                continue;
            }
            cmd_mv(src, dst);
        } else if (strcmp(cmd, "cd") == 0) {
            const char *arg = (argcount > 1) ? argsv[1] : "/";
            char resolved[PATH_MAX];
            if (normalize_path(root, cwd, arg, resolved, sizeof(resolved)) != 0) {
                print_error("invalid path");
                continue;
            }
            strncpy(cwd, resolved, sizeof(cwd) - 1);
            cwd[sizeof(cwd) - 1] = '\0';
        } else if (strcmp(cmd, "python") == 0 || strcmp(cmd, "pip") == 0 || strcmp(cmd, "uv") == 0 || strcmp(cmd, "curl") == 0) {
            char raw_args[1024] = {0};
            extract_raw_args(line_raw, raw_args, sizeof(raw_args));
            cmd_python_or_pip(cmd, raw_args, cwd);
        } else {
            print_error("command not supported");
        }
    }

    return 0;
}
