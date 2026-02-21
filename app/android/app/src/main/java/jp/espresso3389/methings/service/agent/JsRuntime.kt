package jp.espresso3389.methings.service.agent

import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent async JavaScript runtime built on quickjs-kt.
 *
 * Supports async/await via quickjs-kt's `asyncFunction` + coroutine integration:
 * - `fetch(url, options?)` — HTTP requests via [JsFetchClient]
 * - `connectWs(url)` — WebSocket via [JsWebSocketClient] with channel-based receive
 * - `delay(ms)` — coroutine delay
 * - `setTimeout/setInterval` — implemented via delay + Promises
 * - `readFile/writeFile/readBinaryFile/writeBinaryFile` — whole-file I/O under user root
 * - `openFile(path, mode)` — RandomAccessFile handle with read/write/seek/truncate/close
 * - `device_api(action, payload)` — sync device API bridge
 * - `console.log/warn/error/info` — captured to buffer
 */
class JsRuntime(
    private val userDir: File,
    private val sysDir: File,
    private val port: Int = 33389,
    private val deviceApiCallback: ((String, String) -> String)? = null,
) {
    private val jsDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JsRuntime").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(jsDispatcher + SupervisorJob())
    private var quickJs: QuickJs? = null
    private val consoleBuffer = StringBuilder()
    private val openWebSockets = ConcurrentHashMap<Int, JsWebSocketClient>()
    private val nextWsId = AtomicInteger(0)
    private val openFiles = ConcurrentHashMap<Int, RandomAccessFile>()
    private val nextFileId = AtomicInteger(0)

    suspend fun execute(code: String, timeoutMs: Long = 30_000): JsResult {
        return withContext(jsDispatcher) {
            consoleBuffer.setLength(0)
            try {
                val js = getOrCreateQuickJs()
                val rawResult = withTimeout(timeoutMs) {
                    js.evaluate<Any?>(code)
                }
                JsResult(
                    status = "ok",
                    result = stringify(rawResult),
                    consoleOutput = consoleBuffer.toString().trimEnd(),
                )
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                cleanupAfterError()
                JsResult(
                    status = "error",
                    result = "",
                    consoleOutput = consoleBuffer.toString().trimEnd(),
                    error = "execution_timeout: exceeded ${timeoutMs}ms",
                )
            } catch (e: QuickJsException) {
                cleanupWebSockets()
                JsResult(
                    status = "error",
                    result = "",
                    consoleOutput = consoleBuffer.toString().trimEnd(),
                    error = e.message ?: "QuickJS error",
                )
            } catch (e: Exception) {
                cleanupWebSockets()
                JsResult(
                    status = "error",
                    result = "",
                    consoleOutput = consoleBuffer.toString().trimEnd(),
                    error = e.message ?: "execution_failed",
                )
            } finally {
                cleanupWebSockets()
                cleanupFiles()
            }
        }
    }

    fun executeBlocking(code: String, timeoutMs: Long = 30_000): JsResult {
        return runBlocking { execute(code, timeoutMs) }
    }

    fun close() {
        cleanupWebSockets()
        cleanupFiles()
        try {
            quickJs?.close()
        } catch (_: Exception) {}
        quickJs = null
        jsDispatcher.close()
    }

    private suspend fun getOrCreateQuickJs(): QuickJs {
        quickJs?.let { return it }
        val js = QuickJs.create(jsDispatcher)
        js.memoryLimit = 32 * 1024 * 1024L
        js.maxStackSize = 512 * 1024L
        registerBindings(js)
        quickJs = js
        return js
    }

    private suspend fun registerBindings(js: QuickJs) {
        // --- console ---
        js.define("console") {
            function("log") { args ->
                consoleBuffer.appendLine(args.joinToString(" ") { stringify(it) })
            }
            function("warn") { args ->
                consoleBuffer.appendLine("[WARN] " + args.joinToString(" ") { stringify(it) })
            }
            function("error") { args ->
                consoleBuffer.appendLine("[ERROR] " + args.joinToString(" ") { stringify(it) })
            }
            function("info") { args ->
                consoleBuffer.appendLine("[INFO] " + args.joinToString(" ") { stringify(it) })
            }
        }

        // --- device_api bridge ---
        if (deviceApiCallback != null) {
            js.define("__bridge") {
                function("deviceApi") { args ->
                    val action = args.getOrNull(0)?.toString() ?: ""
                    val payloadJson = args.getOrNull(1)?.toString() ?: "{}"
                    deviceApiCallback.invoke(action, payloadJson)
                }
            }
            js.evaluate<Any?>("""
                globalThis.device_api = function(action, payload) {
                    var json = (typeof payload === 'object' && payload !== null)
                        ? JSON.stringify(payload) : (payload || '{}');
                    var raw = __bridge.deviceApi(action, json);
                    try { return JSON.parse(raw); } catch(e) { return raw; }
                };
            """.trimIndent())
        }

        // --- __async: fetch + delay ---
        js.define("__async") {
            asyncFunction("fetch") { args ->
                val url = args.getOrNull(0)?.toString() ?: ""
                val optionsJson = args.getOrNull(1)?.toString() ?: "{}"
                val options = try { JSONObject(optionsJson) } catch (_: Exception) { JSONObject() }
                val method = options.optString("method", "GET")
                val headersObj = options.optJSONObject("headers")
                val headersMap = if (headersObj != null) {
                    val m = mutableMapOf<String, String>()
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        m[key] = headersObj.optString(key, "")
                    }
                    m
                } else null
                val body = if (options.has("body")) options.optString("body", "") else null
                val timeoutMs = options.optInt("timeout", 30_000).coerceIn(1_000, 120_000)

                val result = JsFetchClient.fetch(url, method, headersMap, body, timeoutMs)

                // Return as a Map that quickjs-kt converts to a JS object
                val respHeaders = mutableMapOf<String, Any?>()
                result.headers.forEach { (k, v) -> respHeaders[k] = v }
                mapOf<String, Any?>(
                    "status" to result.status,
                    "ok" to result.ok,
                    "headers" to respHeaders,
                    "body" to result.body,
                )
            }

            asyncFunction("delay") { args ->
                val ms = when (val v = args.getOrNull(0)) {
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull() ?: 0L
                    else -> 0L
                }.coerceIn(0, 300_000)
                kotlinx.coroutines.delay(ms)
                null
            }
        }

        // --- __ws: WebSocket ---
        js.define("__ws") {
            asyncFunction("connect") { args ->
                val url = args.getOrNull(0)?.toString() ?: ""
                val wsId = nextWsId.getAndIncrement()
                val client = JsWebSocketClient(URI(url))
                openWebSockets[wsId] = client
                val ok = client.connect()
                if (!ok) {
                    openWebSockets.remove(wsId)
                    client.destroy()
                    throw Exception("WebSocket connection failed: ${client.error ?: url}")
                }
                wsId
            }

            asyncFunction("receive") { args ->
                val wsId = when (val v = args.getOrNull(0)) {
                    is Number -> v.toInt()
                    else -> -1
                }
                val client = openWebSockets[wsId]
                    ?: throw Exception("WebSocket $wsId not found or already closed")
                client.receive()
            }

            function("send") { args ->
                val wsId = when (val v = args.getOrNull(0)) {
                    is Number -> v.toInt()
                    else -> -1
                }
                val msg = args.getOrNull(1)?.toString() ?: ""
                val client = openWebSockets[wsId]
                    ?: return@function false
                client.send(msg)
            }

            asyncFunction("close") { args ->
                val wsId = when (val v = args.getOrNull(0)) {
                    is Number -> v.toInt()
                    else -> -1
                }
                val client = openWebSockets.remove(wsId)
                client?.close()
                null
            }

            function("isOpen") { args ->
                val wsId = when (val v = args.getOrNull(0)) {
                    is Number -> v.toInt()
                    else -> -1
                }
                openWebSockets[wsId]?.isOpen ?: false
            }
        }

        // --- __fs: file operations ---
        js.define("__fs") {
            asyncFunction("readFile") { args ->
                val path = args.getOrNull(0)?.toString() ?: ""
                withContext(Dispatchers.IO) {
                    val (baseDir, relPath) = resolveBase(path)
                    val target = resolveSecure(baseDir, relPath)
                        ?: throw Exception("path_outside_base: $path")
                    if (!target.exists()) throw Exception("not_found: $path")
                    if (!target.isFile) throw Exception("not_a_file: $path")
                    target.readText(Charsets.UTF_8)
                }
            }

            asyncFunction("readBinaryFile") { args ->
                val path = args.getOrNull(0)?.toString() ?: ""
                withContext(Dispatchers.IO) {
                    if (path.startsWith("\$sys/")) throw Exception("system_binary_read_unsupported")
                    val target = resolveSecure(userDir, path)
                        ?: throw Exception("path_outside_base: $path")
                    if (!target.exists()) throw Exception("not_found: $path")
                    if (!target.isFile) throw Exception("not_a_file: $path")
                    target.readBytes().asUByteArray() // → Uint8Array in JS
                }
            }

            asyncFunction("writeBinaryFile") { args ->
                val path = args.getOrNull(0)?.toString() ?: ""
                val data = args.getOrNull(1)
                withContext(Dispatchers.IO) {
                    if (path.startsWith("\$sys/")) throw Exception("system_write_not_allowed")
                    val target = resolveSecure(userDir, path)
                        ?: throw Exception("path_outside_base: $path")
                    target.parentFile?.mkdirs()
                    val bytes = when (data) {
                        is UByteArray -> data.asByteArray()
                        is ByteArray -> data
                        else -> throw Exception("writeBinaryFile requires Uint8Array or Int8Array data")
                    }
                    target.writeBytes(bytes)
                    bytes.size
                }
            }

            asyncFunction("writeFile") { args ->
                val path = args.getOrNull(0)?.toString() ?: ""
                val content = args.getOrNull(1)?.toString() ?: ""
                withContext(Dispatchers.IO) {
                    if (path.startsWith("\$sys/")) throw Exception("system_write_not_allowed")
                    val target = resolveSecure(userDir, path)
                        ?: throw Exception("path_outside_base: $path")
                    target.parentFile?.mkdirs()
                    target.writeText(content, Charsets.UTF_8)
                    content.toByteArray(Charsets.UTF_8).size
                }
            }

            asyncFunction("listDir") { args ->
                val path = args.getOrNull(0)?.toString() ?: "."
                withContext(Dispatchers.IO) {
                    val (baseDir, relPath) = resolveBase(path)
                    val target = resolveSecure(baseDir, relPath)
                        ?: throw Exception("path_outside_base: $path")
                    if (!target.exists()) throw Exception("not_found: $path")
                    if (!target.isDirectory) throw Exception("not_a_directory: $path")
                    val files = target.listFiles() ?: emptyArray()
                    files.sortedBy { it.name }.map { f ->
                        mapOf<String, Any?>(
                            "name" to f.name,
                            "type" to if (f.isDirectory) "dir" else "file",
                            "size" to f.length(),
                        )
                    }
                }
            }

            asyncFunction("mkdir") { args ->
                val path = args.getOrNull(0)?.toString() ?: ""
                withContext(Dispatchers.IO) {
                    if (path.startsWith("\$sys/")) throw Exception("system_write_not_allowed")
                    val target = resolveSecure(userDir, path)
                        ?: throw Exception("path_outside_base: $path")
                    target.mkdirs()
                    target.exists()
                }
            }

            asyncFunction("deleteFile") { args ->
                val path = args.getOrNull(0)?.toString() ?: ""
                withContext(Dispatchers.IO) {
                    if (path.startsWith("\$sys/")) throw Exception("system_write_not_allowed")
                    val target = resolveSecure(userDir, path)
                        ?: throw Exception("path_outside_base: $path")
                    if (!target.exists()) throw Exception("not_found: $path")
                    if (!target.isFile) throw Exception("not_a_file: $path")
                    target.delete()
                }
            }

            asyncFunction("rmdir") { args ->
                val path = args.getOrNull(0)?.toString() ?: ""
                val recursive = when (val v = args.getOrNull(1)) {
                    is Boolean -> v
                    else -> false
                }
                withContext(Dispatchers.IO) {
                    if (path.startsWith("\$sys/")) throw Exception("system_write_not_allowed")
                    val target = resolveSecure(userDir, path)
                        ?: throw Exception("path_outside_base: $path")
                    if (!target.exists()) throw Exception("not_found: $path")
                    if (!target.isDirectory) throw Exception("not_a_directory: $path")
                    if (recursive) target.deleteRecursively() else target.delete()
                }
            }
        }

        // --- __file: RandomAccessFile handle API ---
        js.define("__file") {
            asyncFunction("open") { args ->
                val path = args.getOrNull(0)?.toString() ?: ""
                val mode = args.getOrNull(1)?.toString() ?: "r"
                withContext(Dispatchers.IO) {
                    if (path.startsWith("\$sys/") && mode != "r") throw Exception("system_write_not_allowed")
                    val (baseDir, relPath) = resolveBase(path)
                    val target = resolveSecure(baseDir, relPath)
                        ?: throw Exception("path_outside_base: $path")
                    if (mode != "r") target.parentFile?.mkdirs()
                    // Map JS modes to RandomAccessFile modes
                    val rafMode = when (mode) {
                        "r" -> "r"
                        "w", "rw" -> "rw"
                        else -> throw Exception("invalid mode: $mode (use 'r', 'w', or 'rw')")
                    }
                    val raf = RandomAccessFile(target, rafMode)
                    val fileId = nextFileId.getAndIncrement()
                    openFiles[fileId] = raf
                    fileId
                }
            }

            function("size") { args ->
                val fileId = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                val raf = openFiles[fileId]
                    ?: throw Exception("file handle $fileId not found or already closed")
                raf.length()
            }

            function("position") { args ->
                val fileId = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                val raf = openFiles[fileId]
                    ?: throw Exception("file handle $fileId not found or already closed")
                raf.filePointer
            }

            asyncFunction("read") { args ->
                val fileId = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                val length = (args.getOrNull(1) as? Number)?.toInt()
                    ?: throw Exception("read requires length argument")
                val raf = openFiles[fileId]
                    ?: throw Exception("file handle $fileId not found or already closed")
                withContext(Dispatchers.IO) {
                    val buf = ByteArray(length.coerceIn(1, 8_388_608))
                    val bytesRead = raf.read(buf)
                    if (bytesRead <= 0) {
                        UByteArray(0) // EOF
                    } else if (bytesRead < buf.size) {
                        buf.copyOf(bytesRead).asUByteArray()
                    } else {
                        buf.asUByteArray()
                    }
                }
            }

            asyncFunction("seek") { args ->
                val fileId = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                val pos = (args.getOrNull(1) as? Number)?.toLong()
                    ?: throw Exception("seek requires position argument")
                val raf = openFiles[fileId]
                    ?: throw Exception("file handle $fileId not found or already closed")
                withContext(Dispatchers.IO) {
                    raf.seek(pos)
                    null
                }
            }

            asyncFunction("write") { args ->
                val fileId = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                val data = args.getOrNull(1)
                val raf = openFiles[fileId]
                    ?: throw Exception("file handle $fileId not found or already closed")
                withContext(Dispatchers.IO) {
                    val bytes = when (data) {
                        is UByteArray -> data.asByteArray()
                        is ByteArray -> data
                        else -> throw Exception("write requires Uint8Array or Int8Array data")
                    }
                    raf.write(bytes)
                    bytes.size
                }
            }

            asyncFunction("truncate") { args ->
                val fileId = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                val size = (args.getOrNull(1) as? Number)?.toLong()
                    ?: throw Exception("truncate requires size argument")
                val raf = openFiles[fileId]
                    ?: throw Exception("file handle $fileId not found or already closed")
                withContext(Dispatchers.IO) {
                    raf.setLength(size)
                    null
                }
            }

            asyncFunction("close") { args ->
                val fileId = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                val raf = openFiles.remove(fileId)
                    ?: throw Exception("file handle $fileId not found or already closed")
                withContext(Dispatchers.IO) {
                    raf.close()
                    null
                }
            }
        }

        // --- JS bootstrap: global wrappers ---
        js.evaluate<Any?>(JS_BOOTSTRAP)
    }

    private fun resolveBase(path: String): Pair<File, String> {
        return if (path.startsWith("\$sys/")) {
            sysDir to path.substring(5)
        } else {
            userDir to path
        }
    }

    private fun resolveSecure(baseDir: File, relativePath: String): File? {
        val resolved = File(baseDir, relativePath).canonicalFile
        return if (resolved.absolutePath.startsWith(baseDir.canonicalPath)) resolved else null
    }

    private fun cleanupWebSockets() {
        for ((_, client) in openWebSockets) {
            try { client.destroy() } catch (_: Exception) {}
        }
        openWebSockets.clear()
    }

    private fun cleanupFiles() {
        for ((_, raf) in openFiles) {
            try { raf.close() } catch (_: Exception) {}
        }
        openFiles.clear()
    }

    private fun cleanupAfterError() {
        cleanupWebSockets()
        cleanupFiles()
        try {
            quickJs?.close()
        } catch (_: Exception) {}
        quickJs = null
    }

    companion object {
        private const val TAG = "JsRuntime"

        private fun stringify(value: Any?): String {
            return when (value) {
                null -> "undefined"
                is String -> value
                is Boolean, is Number -> value.toString()
                is Map<*, *> -> JSONObject(value as Map<String, Any?>).toString()
                is List<*> -> JSONArray(value).toString()
                is Array<*> -> JSONArray(value.toList()).toString()
                else -> value.toString()
            }
        }

        private val JS_BOOTSTRAP = """
            // --- fetch ---
            globalThis.fetch = function(url, options) {
                var optStr = '{}';
                if (options && typeof options === 'object') {
                    optStr = JSON.stringify(options);
                }
                return __async.fetch(url, optStr);
            };

            // --- delay ---
            globalThis.delay = function(ms) {
                return __async.delay(ms || 0);
            };

            // --- WebSocket ---
            globalThis.connectWs = function(url) {
                return __ws.connect(url).then(function(wsId) {
                    return {
                        receive: function() { return __ws.receive(wsId); },
                        send: function(msg) { return __ws.send(wsId, msg); },
                        close: function() { return __ws.close(wsId); },
                        get isOpen() { return __ws.isOpen(wsId); }
                    };
                });
            };

            // --- setTimeout / setInterval / clearTimeout / clearInterval ---
            var _nextId = 1;
            var _timers = {};
            globalThis.setTimeout = function(fn, ms) {
                var id = _nextId++;
                _timers[id] = true;
                __async.delay(ms || 0).then(function() {
                    if (_timers[id]) { delete _timers[id]; fn(); }
                });
                return id;
            };
            globalThis.clearTimeout = function(id) {
                delete _timers[id];
            };
            globalThis.setInterval = function(fn, ms) {
                var id = _nextId++;
                _timers[id] = true;
                function tick() {
                    __async.delay(ms || 0).then(function() {
                        if (_timers[id]) { fn(); tick(); }
                    });
                }
                tick();
                return id;
            };
            globalThis.clearInterval = function(id) {
                delete _timers[id];
            };

            // --- File operations ---
            globalThis.readFile = function(path) {
                return __fs.readFile(path);
            };
            globalThis.readBinaryFile = function(path) {
                return __fs.readBinaryFile(path);
            };
            globalThis.writeBinaryFile = function(path, data) {
                return __fs.writeBinaryFile(path, data);
            };
            globalThis.writeFile = function(path, content) {
                return __fs.writeFile(path, content);
            };
            globalThis.listDir = function(path) {
                return __fs.listDir(path || '.');
            };
            globalThis.mkdir = function(path) {
                return __fs.mkdir(path);
            };
            globalThis.deleteFile = function(path) {
                return __fs.deleteFile(path);
            };
            globalThis.rmdir = function(path, recursive) {
                return __fs.rmdir(path, recursive || false);
            };

            // --- File handle (RandomAccessFile) ---
            globalThis.openFile = function(path, mode) {
                return __file.open(path, mode || 'r').then(function(fid) {
                    return {
                        get size() { return __file.size(fid); },
                        get position() { return __file.position(fid); },
                        read: function(length) { return __file.read(fid, length); },
                        seek: function(pos) { return __file.seek(fid, pos); },
                        write: function(data) { return __file.write(fid, data); },
                        truncate: function(size) { return __file.truncate(fid, size); },
                        close: function() { return __file.close(fid); }
                    };
                });
            };
        """.trimIndent()
    }
}
