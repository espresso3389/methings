#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <locale>
#include <codecvt>
#include <cstdlib>
#include <unistd.h>

#define LOG_TAG "PythonBridgeNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using Py_Initialize_t = void (*)();
using Py_FinalizeEx_t = int (*)();
using PyRun_SimpleString_t = int (*)(const char *);
using Py_SetPythonHome_t = void (*)(wchar_t *);
using Py_SetProgramName_t = void (*)(wchar_t *);
using PyErr_Print_t = void (*)();
using PyErr_Fetch_t = void (*)(void **, void **, void **);
using PyErr_NormalizeException_t = void (*)(void **, void **, void **);
using PyObject_Str_t = void *(*)(void *);
using PyUnicode_AsUTF8_t = const char *(*)(void *);
using Py_DecRef_t = void (*)(void *);

static void *g_python_handle = nullptr;
static Py_Initialize_t g_Py_Initialize = nullptr;
static Py_FinalizeEx_t g_Py_FinalizeEx = nullptr;
static PyRun_SimpleString_t g_PyRun_SimpleString = nullptr;
static Py_SetPythonHome_t g_Py_SetPythonHome = nullptr;
static Py_SetProgramName_t g_Py_SetProgramName = nullptr;
static PyErr_Print_t g_PyErr_Print = nullptr;
static PyErr_Fetch_t g_PyErr_Fetch = nullptr;
static PyErr_NormalizeException_t g_PyErr_NormalizeException = nullptr;
static PyObject_Str_t g_PyObject_Str = nullptr;
static PyUnicode_AsUTF8_t g_PyUnicode_AsUTF8 = nullptr;
static Py_DecRef_t g_Py_DecRef = nullptr;
static bool g_python_initialized = false;
static std::wstring g_python_home_w;
static std::wstring g_program_name_w;

static bool load_python_symbols() {
    if (g_python_handle) {
        return true;
    }
    g_python_handle = dlopen("libpython3.11.so", RTLD_NOW | RTLD_GLOBAL);
    if (!g_python_handle) {
        LOGE("Failed to dlopen libpython3.11.so: %s", dlerror());
        return false;
    }

    g_Py_Initialize = reinterpret_cast<Py_Initialize_t>(dlsym(g_python_handle, "Py_Initialize"));
    g_Py_FinalizeEx = reinterpret_cast<Py_FinalizeEx_t>(dlsym(g_python_handle, "Py_FinalizeEx"));
    g_PyRun_SimpleString = reinterpret_cast<PyRun_SimpleString_t>(dlsym(g_python_handle, "PyRun_SimpleString"));
    g_Py_SetPythonHome = reinterpret_cast<Py_SetPythonHome_t>(dlsym(g_python_handle, "Py_SetPythonHome"));
    g_Py_SetProgramName = reinterpret_cast<Py_SetProgramName_t>(dlsym(g_python_handle, "Py_SetProgramName"));
    g_PyErr_Print = reinterpret_cast<PyErr_Print_t>(dlsym(g_python_handle, "PyErr_Print"));
    g_PyErr_Fetch = reinterpret_cast<PyErr_Fetch_t>(dlsym(g_python_handle, "PyErr_Fetch"));
    g_PyErr_NormalizeException = reinterpret_cast<PyErr_NormalizeException_t>(dlsym(g_python_handle, "PyErr_NormalizeException"));
    g_PyObject_Str = reinterpret_cast<PyObject_Str_t>(dlsym(g_python_handle, "PyObject_Str"));
    g_PyUnicode_AsUTF8 = reinterpret_cast<PyUnicode_AsUTF8_t>(dlsym(g_python_handle, "PyUnicode_AsUTF8"));
    g_Py_DecRef = reinterpret_cast<Py_DecRef_t>(dlsym(g_python_handle, "Py_DecRef"));

    if (!g_Py_Initialize || !g_PyRun_SimpleString) {
        LOGE("Missing required Python symbols");
        return false;
    }
    return true;
}

static void log_python_exception() {
    if (!g_PyErr_Fetch || !g_PyErr_NormalizeException || !g_PyObject_Str || !g_PyUnicode_AsUTF8) {
        LOGE("Python exception details unavailable (missing symbols)");
        return;
    }

    void *ptype = nullptr;
    void *pvalue = nullptr;
    void *ptrace = nullptr;
    g_PyErr_Fetch(&ptype, &pvalue, &ptrace);
    if (!ptype && !pvalue && !ptrace) {
        LOGE("Python exception not set");
        return;
    }
    g_PyErr_NormalizeException(&ptype, &pvalue, &ptrace);

    const char *type_str = "unknown";
    const char *value_str = "unknown";

    if (ptype) {
        void *type_obj = g_PyObject_Str(ptype);
        if (type_obj) {
            const char *tmp = g_PyUnicode_AsUTF8(type_obj);
            if (tmp) {
                type_str = tmp;
            }
            if (g_Py_DecRef) {
                g_Py_DecRef(type_obj);
            }
        }
    }

    if (pvalue) {
        void *value_obj = g_PyObject_Str(pvalue);
        if (value_obj) {
            const char *tmp = g_PyUnicode_AsUTF8(value_obj);
            if (tmp) {
                value_str = tmp;
            }
            if (g_Py_DecRef) {
                g_Py_DecRef(value_obj);
            }
        }
    }

    LOGE("Python exception: %s: %s", type_str, value_str);

    if (g_Py_DecRef) {
        if (ptype) {
            g_Py_DecRef(ptype);
        }
        if (pvalue) {
            g_Py_DecRef(pvalue);
        }
        if (ptrace) {
            g_Py_DecRef(ptrace);
        }
    }
}

static std::wstring to_wide(const std::string &value) {
    std::wstring_convert<std::codecvt_utf8_utf16<wchar_t>> converter;
    return converter.from_bytes(value);
}

extern "C"
JNIEXPORT jint JNICALL
Java_jp_espresso3389_kugutz_service_PythonBridge_start(
        JNIEnv *env,
        jobject /* this */,
        jstring pythonHome,
        jstring serverDir,
        jstring keyFile,
        jstring nativeLibDir) {
    if (!load_python_symbols()) {
        return -1;
    }

    const char *python_home_c = env->GetStringUTFChars(pythonHome, nullptr);
    const char *server_dir_c = env->GetStringUTFChars(serverDir, nullptr);
    const char *key_file_c = keyFile ? env->GetStringUTFChars(keyFile, nullptr) : nullptr;
    const char *native_lib_dir_c = nativeLibDir ? env->GetStringUTFChars(nativeLibDir, nullptr) : nullptr;

    std::string python_home(python_home_c ? python_home_c : "");
    std::string server_dir(server_dir_c ? server_dir_c : "");
    std::string key_file(key_file_c ? key_file_c : "");
    std::string native_lib_dir(native_lib_dir_c ? native_lib_dir_c : "");

    if (python_home.empty() || server_dir.empty()) {
        LOGE("Python home or server dir is empty");
        return -2;
    }

    setenv("PYTHONHOME", python_home.c_str(), 1);

    std::string python_path = server_dir;
    python_path += ":";
    python_path += python_home + "/site-packages";
    python_path += ":";
    python_path += python_home + "/modules";
    python_path += ":";
    python_path += python_home + "/stdlib.zip";
    setenv("PYTHONPATH", python_path.c_str(), 1);
    LOGI("PYTHONHOME=%s", python_home.c_str());
    LOGI("PYTHONPATH=%s", python_path.c_str());
    LOGI("SERVER_DIR=%s", server_dir.c_str());

    // TLS trust store: prefer managed CA bundle in app-private storage, else fall back to certifi.
    // This is critical for pip/requests on Android where /etc/ssl/certs is absent.
    {
        std::string base = python_home;
        // python_home is typically <filesDir>/pyenv
        if (base.size() >= 6 && base.substr(base.size() - 6) == "/pyenv") {
            base = base.substr(0, base.size() - 6);
        }
        std::string managed = base + "/protected/ca/cacert.pem";
        std::string certifi = python_home + "/site-packages/certifi/cacert.pem";
        const char *chosen = nullptr;
        if (access(managed.c_str(), R_OK) == 0) {
            chosen = managed.c_str();
        } else if (access(certifi.c_str(), R_OK) == 0) {
            chosen = certifi.c_str();
        }
        if (chosen) {
            setenv("SSL_CERT_FILE", chosen, 1);
            setenv("PIP_CERT", chosen, 1);
            setenv("REQUESTS_CA_BUNDLE", chosen, 1);
            LOGI("SSL_CERT_FILE=%s", chosen);
        }
    }

    if (!key_file.empty()) {
        setenv("SQLCIPHER_KEY_FILE", key_file.c_str(), 1);
    }
    if (!native_lib_dir.empty()) {
        std::string dropbear_bin = native_lib_dir + "/libdropbear.so";
        std::string dropbearkey_bin = native_lib_dir + "/libdropbearkey.so";
        setenv("DROPBEAR_BIN", dropbear_bin.c_str(), 1);
        setenv("DROPBEARKEY_BIN", dropbearkey_bin.c_str(), 1);
        setenv("DROPBEAR_VERBOSE", "3", 1);
    }

    g_python_home_w = to_wide(python_home);
    if (g_Py_SetPythonHome) {
        g_Py_SetPythonHome(const_cast<wchar_t *>(g_python_home_w.c_str()));
    }
    if (g_Py_SetProgramName) {
        g_program_name_w = to_wide("android_python");
        g_Py_SetProgramName(const_cast<wchar_t *>(g_program_name_w.c_str()));
    }

    if (!g_python_initialized) {
        g_Py_Initialize();
        g_python_initialized = true;
    }

    std::string error_log = server_dir + "/python_startup.log";
    std::string code =
        "import os, runpy, sys, traceback\n"
        "server_dir = r'" + server_dir + "'\n"
        "error_log = r'" + error_log + "'\n"
        "try:\n"
        "    os.chdir(server_dir)\n"
        "    sys.path.insert(0, server_dir)\n"
        "    runpy.run_path(os.path.join(server_dir, 'worker.py'), run_name='__main__')\n"
        "except BaseException:\n"
        "    try:\n"
        "        with open(error_log, 'w', encoding='utf-8') as fh:\n"
        "            traceback.print_exc(file=fh)\n"
        "    except Exception:\n"
        "        pass\n"
        "    raise\n";

    int rc = g_PyRun_SimpleString(code.c_str());
    if (rc != 0) {
        LOGE("PyRun_SimpleString failed with code %d", rc);
        if (g_PyErr_Print) {
            g_PyErr_Print();
        }
    }

    if (keyFile) {
        env->ReleaseStringUTFChars(keyFile, key_file_c);
    }
    if (nativeLibDir) {
        env->ReleaseStringUTFChars(nativeLibDir, native_lib_dir_c);
    }
    env->ReleaseStringUTFChars(serverDir, server_dir_c);
    env->ReleaseStringUTFChars(pythonHome, python_home_c);

    return rc;
}

extern "C"
JNIEXPORT jint JNICALL
Java_jp_espresso3389_kugutz_service_PythonBridge_stop(
        JNIEnv * /* env */,
        jobject /* this */) {
    if (g_Py_FinalizeEx) {
        return g_Py_FinalizeEx();
    }
    return 0;
}
