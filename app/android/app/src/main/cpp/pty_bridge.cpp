#include <jni.h>
#include <android/log.h>
#include <pty.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <signal.h>
#include <errno.h>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>

#define LOG_TAG "PtyBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char *JNI_CLASS = "jp/espresso3389/methings/service/agent/PtyBridge";

// nativeCreateSession(shell, cwd, envArray, rows, cols) -> int[] {pid, masterFd}
static jintArray nativeCreateSession(JNIEnv *env, jobject, jstring jShell, jstring jCwd,
                                     jobjectArray jEnvArray, jint rows, jint cols) {
    const char *shell = env->GetStringUTFChars(jShell, nullptr);
    const char *cwd = jCwd ? env->GetStringUTFChars(jCwd, nullptr) : nullptr;

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);

    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &ws);

    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        if (shell) env->ReleaseStringUTFChars(jShell, shell);
        if (cwd) env->ReleaseStringUTFChars(jCwd, cwd);
        return nullptr;
    }

    if (pid == 0) {
        // Child process
        setsid();

        if (cwd && cwd[0] != '\0') {
            if (chdir(cwd) != 0) {
                // Ignore chdir failure; proceed with current dir
            }
        }

        // Set environment variables from array
        if (jEnvArray) {
            int len = env->GetArrayLength(jEnvArray);
            for (int i = 0; i < len; i++) {
                auto jStr = (jstring) env->GetObjectArrayElement(jEnvArray, i);
                if (jStr) {
                    const char *s = env->GetStringUTFChars(jStr, nullptr);
                    if (s) {
                        putenv(strdup(s));
                        env->ReleaseStringUTFChars(jStr, s);
                    }
                    env->DeleteLocalRef(jStr);
                }
            }
        }

        const char *args[] = {shell, "-l", nullptr};
        execv(shell, const_cast<char *const *>(args));
        _exit(127);
    }

    // Parent process
    if (shell) env->ReleaseStringUTFChars(jShell, shell);
    if (cwd) env->ReleaseStringUTFChars(jCwd, cwd);

    // Set master fd to non-blocking for nativeRead
    int flags = fcntl(master, F_GETFL);
    if (flags >= 0) {
        fcntl(master, F_SETFL, flags | O_NONBLOCK);
    }

    LOGI("PTY session created: pid=%d, masterFd=%d", pid, master);

    jintArray result = env->NewIntArray(2);
    jint buf[2] = {static_cast<jint>(pid), master};
    env->SetIntArrayRegion(result, 0, 2, buf);
    return result;
}

// nativeResize(masterFd, pid, rows, cols)
static void nativeResize(JNIEnv *, jobject, jint masterFd, jint pid, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    ws.ws_col = static_cast<unsigned short>(cols > 0 ? cols : 80);
    if (ioctl(masterFd, TIOCSWINSZ, &ws) == 0 && pid > 0) {
        kill(pid, SIGWINCH);
    }
}

// nativeRead(masterFd, bufSize) -> byte[] or null
static jbyteArray nativeRead(JNIEnv *env, jobject, jint masterFd, jint bufSize) {
    int size = bufSize > 0 ? bufSize : 4096;
    if (size > 65536) size = 65536;

    auto *buf = new unsigned char[size];
    ssize_t n = read(masterFd, buf, size);

    if (n <= 0) {
        delete[] buf;
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(n));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(n), reinterpret_cast<jbyte *>(buf));
    delete[] buf;
    return result;
}

// nativeWrite(masterFd, data) -> int (bytes written or -1)
static jint nativeWrite(JNIEnv *env, jobject, jint masterFd, jbyteArray jData) {
    jsize len = env->GetArrayLength(jData);
    jbyte *data = env->GetByteArrayElements(jData, nullptr);
    ssize_t written = write(masterFd, data, len);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
    return static_cast<jint>(written);
}

// nativeClose(masterFd)
static void nativeClose(JNIEnv *, jobject, jint masterFd) {
    close(masterFd);
}

// nativeKill(pid, signal)
static void nativeKill(JNIEnv *, jobject, jint pid, jint sig) {
    if (pid > 0) {
        kill(-pid, sig); // Kill process group
    }
}

// nativeWaitpid(pid, noHang) -> int (exit status, or -1 if still running when noHang)
static jint nativeWaitpid(JNIEnv *, jobject, jint pid, jboolean noHang) {
    int status = 0;
    int options = noHang ? WNOHANG : 0;
    pid_t result = waitpid(pid, &status, options);
    if (result == 0) {
        return -1; // Still running
    }
    if (result < 0) {
        return -2; // Error
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return -2;
}

static const JNINativeMethod methods[] = {
    {"nativeCreateSession", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;II)[I",
     (void *) nativeCreateSession},
    {"nativeResize", "(IIII)V", (void *) nativeResize},
    {"nativeRead", "(II)[B", (void *) nativeRead},
    {"nativeWrite", "(I[B)I", (void *) nativeWrite},
    {"nativeClose", "(I)V", (void *) nativeClose},
    {"nativeKill", "(II)V", (void *) nativeKill},
    {"nativeWaitpid", "(IZ)I", (void *) nativeWaitpid},
};

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass(JNI_CLASS);
    if (!cls) {
        LOGE("PtyBridge class not found");
        return JNI_ERR;
    }
    if (env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0])) < 0) {
        LOGE("RegisterNatives failed");
        return JNI_ERR;
    }
    LOGI("PtyBridge JNI registered");
    return JNI_VERSION_1_6;
}
