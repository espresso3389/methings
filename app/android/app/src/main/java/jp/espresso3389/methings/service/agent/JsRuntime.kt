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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
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
 * - `connectTcp(host, port, options?)` — TCP client handle with read/write/close
 * - `listenTcp(host, port, options?)` — TCP server handle with accept/close
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
    private val tcpSockets = ConcurrentHashMap<Int, Socket>()
    private val nextTcpSocketId = AtomicInteger(0)
    private val tcpServers = ConcurrentHashMap<Int, ServerSocket>()
    private val nextTcpServerId = AtomicInteger(0)
    private val cvBridge = JsCvBridge(userDir)

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
                cleanupTcp()
                cvBridge.cleanup()
            }
        }
    }

    fun executeBlocking(code: String, timeoutMs: Long = 30_000): JsResult {
        return runBlocking { execute(code, timeoutMs) }
    }

    fun close() {
        cleanupWebSockets()
        cleanupFiles()
        cleanupTcp()
        cvBridge.cleanup()
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

            asyncFunction("readText") { args ->
                val socketId = (args.getOrNull(0) as? Number)?.toInt()
                    ?: throw Exception("readText requires socket id")
                val maxBytes = ((args.getOrNull(1) as? Number)?.toInt() ?: 4096).coerceIn(1, 1_048_576)
                val timeoutMs = ((args.getOrNull(2) as? Number)?.toInt() ?: 30_000).coerceIn(0, 120_000)
                val socket = tcpSockets[socketId]
                    ?: throw Exception("tcp socket $socketId not found or already closed")
                withContext(Dispatchers.IO) {
                    try {
                        socket.soTimeout = timeoutMs
                        val buf = ByteArray(maxBytes)
                        val n = socket.getInputStream().read(buf)
                        if (n < 0) {
                            mapOf<String, Any?>("status" to "eof")
                        } else {
                            mapOf<String, Any?>(
                                "status" to "ok",
                                "text" to String(buf, 0, n, Charsets.UTF_8),
                                "bytes" to n
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                        mapOf<String, Any?>("status" to "timeout")
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

        // --- __tcp: TCP client/server ---
        js.define("__tcp") {
            asyncFunction("connect") { args ->
                val host = args.getOrNull(0)?.toString() ?: ""
                val port = (args.getOrNull(1) as? Number)?.toInt()
                    ?: throw Exception("connectTcp requires numeric port")
                val optionsJson = args.getOrNull(2)?.toString() ?: "{}"
                val options = try { JSONObject(optionsJson) } catch (_: Exception) { JSONObject() }
                val timeoutMs = options.optInt("timeout_ms", 10_000).coerceIn(500, 120_000)
                val noDelay = options.optBoolean("no_delay", true)
                val keepAlive = options.optBoolean("keep_alive", true)
                val socket = withContext(Dispatchers.IO) {
                    Socket().apply {
                        tcpNoDelay = noDelay
                        this.keepAlive = keepAlive
                        connect(InetSocketAddress(host, port), timeoutMs)
                    }
                }
                val socketId = nextTcpSocketId.getAndIncrement()
                tcpSockets[socketId] = socket
                socketId
            }

            asyncFunction("listen") { args ->
                val host = args.getOrNull(0)?.toString() ?: "127.0.0.1"
                val port = (args.getOrNull(1) as? Number)?.toInt()
                    ?: throw Exception("listenTcp requires numeric port")
                val optionsJson = args.getOrNull(2)?.toString() ?: "{}"
                val options = try { JSONObject(optionsJson) } catch (_: Exception) { JSONObject() }
                val backlog = options.optInt("backlog", 50).coerceIn(1, 512)
                val reuseAddr = options.optBoolean("reuse_address", true)
                val server = withContext(Dispatchers.IO) {
                    ServerSocket().apply {
                        this.reuseAddress = reuseAddr
                        bind(InetSocketAddress(host, port), backlog)
                    }
                }
                val serverId = nextTcpServerId.getAndIncrement()
                tcpServers[serverId] = server
                mapOf<String, Any?>(
                    "serverId" to serverId,
                    "boundHost" to (server.inetAddress?.hostAddress ?: host),
                    "boundPort" to server.localPort
                )
            }

            asyncFunction("accept") { args ->
                val serverId = (args.getOrNull(0) as? Number)?.toInt()
                    ?: throw Exception("accept requires server id")
                val timeoutMs = ((args.getOrNull(1) as? Number)?.toInt() ?: 0).coerceIn(0, 120_000)
                val server = tcpServers[serverId]
                    ?: throw Exception("tcp server $serverId not found or already closed")
                withContext(Dispatchers.IO) {
                    try {
                        server.soTimeout = timeoutMs
                        val client = server.accept()
                        val socketId = nextTcpSocketId.getAndIncrement()
                        tcpSockets[socketId] = client
                        mapOf<String, Any?>(
                            "status" to "ok",
                            "socketId" to socketId,
                            "remoteHost" to (client.inetAddress?.hostAddress ?: ""),
                            "remotePort" to client.port
                        )
                    } catch (_: SocketTimeoutException) {
                        mapOf<String, Any?>("status" to "timeout")
                    }
                }
            }

            asyncFunction("read") { args ->
                val socketId = (args.getOrNull(0) as? Number)?.toInt()
                    ?: throw Exception("read requires socket id")
                val maxBytes = ((args.getOrNull(1) as? Number)?.toInt() ?: 4096).coerceIn(1, 1_048_576)
                val timeoutMs = ((args.getOrNull(2) as? Number)?.toInt() ?: 30_000).coerceIn(0, 120_000)
                val socket = tcpSockets[socketId]
                    ?: throw Exception("tcp socket $socketId not found or already closed")
                withContext(Dispatchers.IO) {
                    try {
                        socket.soTimeout = timeoutMs
                        val buf = ByteArray(maxBytes)
                        val n = socket.getInputStream().read(buf)
                        if (n < 0) {
                            mapOf<String, Any?>("status" to "eof")
                        } else {
                            mapOf<String, Any?>(
                                "status" to "ok",
                                "data" to buf.copyOf(n).asUByteArray()
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                        mapOf<String, Any?>("status" to "timeout")
                    }
                }
            }

            asyncFunction("write") { args ->
                val socketId = (args.getOrNull(0) as? Number)?.toInt()
                    ?: throw Exception("write requires socket id")
                val socket = tcpSockets[socketId]
                    ?: throw Exception("tcp socket $socketId not found or already closed")
                val data = args.getOrNull(1)
                withContext(Dispatchers.IO) {
                    val bytes = when (data) {
                        is UByteArray -> data.asByteArray()
                        is ByteArray -> data
                        is String -> data.toByteArray(Charsets.UTF_8)
                        else -> throw Exception("write requires string, Uint8Array, or Int8Array data")
                    }
                    val out = socket.getOutputStream()
                    out.write(bytes)
                    out.flush()
                    bytes.size
                }
            }

            asyncFunction("closeSocket") { args ->
                val socketId = (args.getOrNull(0) as? Number)?.toInt()
                    ?: throw Exception("closeSocket requires socket id")
                val socket = tcpSockets.remove(socketId)
                    ?: return@asyncFunction null
                withContext(Dispatchers.IO) {
                    try { socket.close() } catch (_: Exception) {}
                    null
                }
            }

            asyncFunction("closeServer") { args ->
                val serverId = (args.getOrNull(0) as? Number)?.toInt()
                    ?: throw Exception("closeServer requires server id")
                val server = tcpServers.remove(serverId)
                    ?: return@asyncFunction null
                withContext(Dispatchers.IO) {
                    try { server.close() } catch (_: Exception) {}
                    null
                }
            }

            function("isSocketOpen") { args ->
                val socketId = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                val s = tcpSockets[socketId] ?: return@function false
                s.isConnected && !s.isClosed
            }

            function("isServerOpen") { args ->
                val serverId = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                val s = tcpServers[serverId] ?: return@function false
                !s.isClosed
            }
        }

        // --- __cv: OpenCV image processing ---
        cvBridge.registerBindings(js)

        // --- JS bootstrap: global wrappers ---
        js.evaluate<Any?>(JS_BOOTSTRAP)
        js.evaluate<Any?>(JS_CV_BOOTSTRAP)
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

    private fun cleanupTcp() {
        for ((_, s) in tcpSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        tcpSockets.clear()
        for ((_, s) in tcpServers) {
            try { s.close() } catch (_: Exception) {}
        }
        tcpServers.clear()
    }

    private fun cleanupAfterError() {
        cleanupWebSockets()
        cleanupFiles()
        cleanupTcp()
        cvBridge.cleanup()
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

            // --- TCP client/server ---
            globalThis.connectTcp = function(host, port, options) {
                var optStr = '{}';
                if (options && typeof options === 'object') {
                    optStr = JSON.stringify(options);
                }
                return __tcp.connect(String(host || '127.0.0.1'), Number(port), optStr).then(function(sockId) {
                    return {
                        read: function(maxBytes, timeoutMs) {
                            return __tcp.read(sockId, Number(maxBytes || 4096), Number(timeoutMs || 30000));
                        },
                        readText: function(maxBytes, timeoutMs) {
                            return __tcp.readText(sockId, Number(maxBytes || 4096), Number(timeoutMs || 30000));
                        },
                        write: function(data) { return __tcp.write(sockId, data); },
                        close: function() { return __tcp.closeSocket(sockId); },
                        get isOpen() { return __tcp.isSocketOpen(sockId); }
                    };
                });
            };

            globalThis.listenTcp = function(host, port, options) {
                var optStr = '{}';
                if (options && typeof options === 'object') {
                    optStr = JSON.stringify(options);
                }
                return __tcp.listen(String(host || '127.0.0.1'), Number(port), optStr).then(function(info) {
                    var serverId = info.serverId;
                    return {
                        host: info.boundHost,
                        port: info.boundPort,
                        accept: function(timeoutMs) {
                            return __tcp.accept(serverId, Number(timeoutMs || 0)).then(function(r) {
                                if (!r || r.status !== 'ok') return null;
                                var sockId = r.socketId;
                                return {
                                    remoteHost: r.remoteHost,
                                    remotePort: r.remotePort,
                                    read: function(maxBytes, timeoutMs2) {
                                        return __tcp.read(sockId, Number(maxBytes || 4096), Number(timeoutMs2 || 30000));
                                    },
                                    readText: function(maxBytes, timeoutMs2) {
                                        return __tcp.readText(sockId, Number(maxBytes || 4096), Number(timeoutMs2 || 30000));
                                    },
                                    write: function(data) { return __tcp.write(sockId, data); },
                                    close: function() { return __tcp.closeSocket(sockId); },
                                    get isOpen() { return __tcp.isSocketOpen(sockId); }
                                };
                            });
                        },
                        close: function() { return __tcp.closeServer(serverId); },
                        get isOpen() { return __tcp.isServerOpen(serverId); }
                    };
                });
            };
        """.trimIndent()

        private val JS_CV_BOOTSTRAP = """
            globalThis.cv = (function() {
                function Mat(handle) { this._h = handle; }
                Mat.prototype.release = function() { __cv.release(this._h); };
                Mat.prototype.clone = function() { return __cv.clone(this._h).then(wrapMat); };
                Object.defineProperty(Mat.prototype, 'info', {
                    get: function() { return __cv.info(this._h); }
                });
                Object.defineProperty(Mat.prototype, 'rows', {
                    get: function() { return __cv.info(this._h).rows; }
                });
                Object.defineProperty(Mat.prototype, 'cols', {
                    get: function() { return __cv.info(this._h).cols; }
                });
                Object.defineProperty(Mat.prototype, 'channels', {
                    get: function() { return __cv.info(this._h).channels; }
                });
                Mat.prototype.toBytes = function() { return __cv.matToBytes(this._h); };
                Mat.prototype.toBase64 = function(ext) { return __cv.matToBase64(this._h, ext || '.jpg'); };

                function wrapMat(id) { return new Mat(id); }
                function h(mat) { return mat._h; }

                return {
                    Mat: Mat,

                    // ── Color conversion codes ──
                    COLOR_BGR2GRAY: 6,
                    COLOR_BGR2RGB: 4,
                    COLOR_RGB2BGR: 4,
                    COLOR_BGR2HSV: 40,
                    COLOR_HSV2BGR: 54,
                    COLOR_BGR2HLS: 52,
                    COLOR_BGR2Lab: 44,
                    COLOR_BGR2YCrCb: 36,
                    COLOR_GRAY2BGR: 8,
                    COLOR_RGBA2BGR: 3,
                    COLOR_BGR2RGBA: 2,

                    // ── Interpolation ──
                    INTER_NEAREST: 0,
                    INTER_LINEAR: 1,
                    INTER_CUBIC: 2,
                    INTER_AREA: 3,
                    INTER_LANCZOS4: 4,

                    // ── Threshold types ──
                    THRESH_BINARY: 0,
                    THRESH_BINARY_INV: 1,
                    THRESH_TRUNC: 2,
                    THRESH_TOZERO: 3,
                    THRESH_TOZERO_INV: 4,
                    THRESH_OTSU: 8,
                    THRESH_TRIANGLE: 16,

                    // ── Morphology ──
                    MORPH_ERODE: 0,
                    MORPH_DILATE: 1,
                    MORPH_OPEN: 2,
                    MORPH_CLOSE: 3,
                    MORPH_GRADIENT: 4,
                    MORPH_TOPHAT: 5,
                    MORPH_BLACKHAT: 6,
                    MORPH_RECT: 0,
                    MORPH_CROSS: 1,
                    MORPH_ELLIPSE: 2,

                    // ── Contour modes ──
                    RETR_EXTERNAL: 0,
                    RETR_LIST: 1,
                    RETR_CCOMP: 2,
                    RETR_TREE: 3,
                    CHAIN_APPROX_NONE: 1,
                    CHAIN_APPROX_SIMPLE: 2,

                    // ── Template matching ──
                    TM_SQDIFF: 0,
                    TM_SQDIFF_NORMED: 1,
                    TM_CCORR: 2,
                    TM_CCORR_NORMED: 3,
                    TM_CCOEFF: 4,
                    TM_CCOEFF_NORMED: 5,

                    // ── Rotation codes ──
                    ROTATE_90_CLOCKWISE: 0,
                    ROTATE_180: 1,
                    ROTATE_90_COUNTERCLOCKWISE: 2,

                    // ── Font faces ──
                    FONT_HERSHEY_SIMPLEX: 0,
                    FONT_HERSHEY_PLAIN: 1,
                    FONT_HERSHEY_DUPLEX: 2,
                    FONT_HERSHEY_COMPLEX: 3,
                    FONT_HERSHEY_TRIPLEX: 4,
                    FONT_ITALIC: 16,

                    // ── Read flags ──
                    IMREAD_UNCHANGED: -1,
                    IMREAD_GRAYSCALE: 0,
                    IMREAD_COLOR: 1,

                    // ── CvType ──
                    CV_8UC1: 0,
                    CV_8UC3: 16,
                    CV_8UC4: 24,
                    CV_16SC1: 3,
                    CV_32FC1: 5,
                    CV_64FC1: 6,

                    // ── Adaptive threshold ──
                    ADAPTIVE_THRESH_MEAN_C: 0,
                    ADAPTIVE_THRESH_GAUSSIAN_C: 1,

                    // ── Image I/O (async) ──
                    imread: function(path, flags) {
                        return __cv.imread(path, flags === undefined ? 1 : flags).then(wrapMat);
                    },
                    imwrite: function(mat, path) {
                        return __cv.imwrite(h(mat), path);
                    },
                    imdecode: function(data, flags) {
                        return __cv.imdecode(data, flags === undefined ? 1 : flags).then(wrapMat);
                    },
                    imencode: function(mat, ext) {
                        return __cv.imencode(h(mat), ext || '.jpg');
                    },

                    // ── Mat creation (sync) ──
                    zeros: function(rows, cols, type) {
                        return wrapMat(__cv.zeros(rows, cols, type === undefined ? 16 : type));
                    },
                    ones: function(rows, cols, type) {
                        return wrapMat(__cv.ones(rows, cols, type === undefined ? 16 : type));
                    },

                    // ── Mat data transfer (async) ──
                    fromBytes: function(data, rows, cols, type) {
                        return __cv.matFromBytes(data, rows, cols, type === undefined ? 16 : type).then(wrapMat);
                    },
                    fromBase64: function(b64, flags) {
                        return __cv.matFromBase64(b64, flags === undefined ? 1 : flags).then(wrapMat);
                    },

                    // ── Color (async) ──
                    cvtColor: function(mat, code) {
                        return __cv.cvtColor(h(mat), code).then(wrapMat);
                    },

                    // ── Geometric transforms (async) ──
                    resize: function(mat, w, hh, interp) {
                        return __cv.resize(h(mat), w, hh, interp === undefined ? 1 : interp).then(wrapMat);
                    },
                    crop: function(mat, x, y, w, hh) {
                        return __cv.crop(h(mat), x, y, w, hh).then(wrapMat);
                    },
                    rotate: function(mat, code) {
                        return __cv.rotate(h(mat), code).then(wrapMat);
                    },
                    flip: function(mat, code) {
                        return __cv.flip(h(mat), code).then(wrapMat);
                    },
                    warpAffine: function(mat, matrix, dstW, dstH) {
                        return __cv.warpAffine(h(mat), h(matrix), dstW, dstH).then(wrapMat);
                    },
                    warpPerspective: function(mat, matrix, dstW, dstH) {
                        return __cv.warpPerspective(h(mat), h(matrix), dstW, dstH).then(wrapMat);
                    },
                    getRotationMatrix2D: function(cx, cy, angle, scale) {
                        return __cv.getRotationMatrix2D(cx, cy, angle, scale === undefined ? 1.0 : scale).then(wrapMat);
                    },
                    getPerspectiveTransform: function(src, dst) {
                        return __cv.getPerspectiveTransform(
                            src[0][0], src[0][1], src[1][0], src[1][1],
                            src[2][0], src[2][1], src[3][0], src[3][1],
                            dst[0][0], dst[0][1], dst[1][0], dst[1][1],
                            dst[2][0], dst[2][1], dst[3][0], dst[3][1]
                        ).then(wrapMat);
                    },

                    // ── Filtering (async) ──
                    blur: function(mat, kw, kh) {
                        return __cv.blur(h(mat), kw, kh === undefined ? kw : kh).then(wrapMat);
                    },
                    GaussianBlur: function(mat, kw, kh, sigmaX) {
                        return __cv.gaussianBlur(h(mat), kw, kh === undefined ? kw : kh, sigmaX || 0).then(wrapMat);
                    },
                    medianBlur: function(mat, ksize) {
                        return __cv.medianBlur(h(mat), ksize).then(wrapMat);
                    },
                    bilateralFilter: function(mat, d, sigmaColor, sigmaSpace) {
                        return __cv.bilateralFilter(h(mat), d || 9, sigmaColor || 75, sigmaSpace || 75).then(wrapMat);
                    },

                    // ── Edge detection (async) ──
                    Canny: function(mat, t1, t2, apertureSize) {
                        return __cv.canny(h(mat), t1, t2, apertureSize || 3).then(wrapMat);
                    },
                    Laplacian: function(mat, ddepth, ksize) {
                        return __cv.laplacian(h(mat), ddepth === undefined ? 3 : ddepth, ksize || 1).then(wrapMat);
                    },
                    Sobel: function(mat, ddepth, dx, dy, ksize) {
                        return __cv.sobel(h(mat), ddepth === undefined ? 3 : ddepth, dx || 1, dy || 0, ksize || 3).then(wrapMat);
                    },

                    // ── Thresholding (async) ──
                    threshold: function(mat, thresh, maxval, type) {
                        return __cv.threshold(h(mat), thresh || 127, maxval || 255, type || 0).then(function(r) {
                            return { mat: wrapMat(r.matId), threshold: r.threshold };
                        });
                    },
                    adaptiveThreshold: function(mat, maxval, adaptiveMethod, threshType, blockSize, C) {
                        return __cv.adaptiveThreshold(h(mat), maxval || 255, adaptiveMethod || 1, threshType || 0, blockSize || 11, C || 2).then(wrapMat);
                    },

                    // ── Morphology (async) ──
                    erode: function(mat, kw, kh, iterations) {
                        return __cv.erode(h(mat), kw || 3, kh === undefined ? (kw || 3) : kh, iterations || 1).then(wrapMat);
                    },
                    dilate: function(mat, kw, kh, iterations) {
                        return __cv.dilate(h(mat), kw || 3, kh === undefined ? (kw || 3) : kh, iterations || 1).then(wrapMat);
                    },
                    morphologyEx: function(mat, op, kw, kh, shape, iterations) {
                        return __cv.morphologyEx(h(mat), op, kw || 3, kh === undefined ? (kw || 3) : kh, shape || 0, iterations || 1).then(wrapMat);
                    },

                    // ── Contours (async) ──
                    findContours: function(mat, mode, method) {
                        return __cv.findContours(h(mat), mode === undefined ? 0 : mode, method === undefined ? 2 : method);
                    },
                    drawContours: function(mat, contours, idx, r, g, b, thickness) {
                        return __cv.drawContours(h(mat), contours, idx === undefined ? -1 : idx, r || 0, g === undefined ? 255 : g, b || 0, thickness || 2).then(function() { return mat; });
                    },

                    // ── Drawing (async, mutates in-place) ──
                    rectangle: function(mat, x, y, w, hh, r, g, b, thickness) {
                        return __cv.rectangle(h(mat), x, y, w, hh, r || 0, g === undefined ? 255 : g, b || 0, thickness || 2).then(function() { return mat; });
                    },
                    circle: function(mat, cx, cy, radius, r, g, b, thickness) {
                        return __cv.circle(h(mat), cx, cy, radius, r || 0, g === undefined ? 255 : g, b || 0, thickness || 2).then(function() { return mat; });
                    },
                    line: function(mat, x1, y1, x2, y2, r, g, b, thickness) {
                        return __cv.line(h(mat), x1, y1, x2, y2, r || 0, g === undefined ? 255 : g, b || 0, thickness || 2).then(function() { return mat; });
                    },
                    putText: function(mat, text, x, y, fontFace, fontScale, r, g, b, thickness) {
                        return __cv.putText(h(mat), text, x, y, fontFace || 0, fontScale || 1, r === undefined ? 255 : r, g === undefined ? 255 : g, b === undefined ? 255 : b, thickness || 1).then(function() { return mat; });
                    },

                    // ── Features (async) ──
                    goodFeaturesToTrack: function(mat, maxCorners, qualityLevel, minDistance) {
                        return __cv.goodFeaturesToTrack(h(mat), maxCorners || 100, qualityLevel || 0.01, minDistance || 10);
                    },
                    matchTemplate: function(mat, tmpl, method) {
                        return __cv.matchTemplate(h(mat), h(tmpl), method === undefined ? 5 : method).then(function(r) {
                            return { mat: wrapMat(r.matId), minVal: r.minVal, maxVal: r.maxVal, minLoc: r.minLoc, maxLoc: r.maxLoc };
                        });
                    },
                    detectORB: function(mat, maxFeatures) {
                        return __cv.detectORB(h(mat), maxFeatures || 500).then(function(r) {
                            return { keypoints: r.keypoints, descriptors: r.descriptorsMatId >= 0 ? wrapMat(r.descriptorsMatId) : null };
                        });
                    },

                    // ── Histograms (async) ──
                    calcHist: function(mat, channel, bins) {
                        return __cv.calcHist(h(mat), channel || 0, bins || 256);
                    },
                    equalizeHist: function(mat) {
                        return __cv.equalizeHist(h(mat)).then(wrapMat);
                    },

                    // ── Arithmetic (async) ──
                    convertTo: function(mat, rtype, alpha, beta) {
                        return __cv.convertTo(h(mat), rtype, alpha === undefined ? 1 : alpha, beta || 0).then(wrapMat);
                    },
                    absdiff: function(mat1, mat2) {
                        return __cv.absdiff(h(mat1), h(mat2)).then(wrapMat);
                    },
                    addWeighted: function(mat1, alpha, mat2, beta, gamma) {
                        return __cv.addWeighted(h(mat1), alpha, h(mat2), beta, gamma || 0).then(wrapMat);
                    },
                    bitwiseAnd: function(mat1, mat2) {
                        return __cv.bitwiseAnd(h(mat1), h(mat2)).then(wrapMat);
                    },
                    bitwiseOr: function(mat1, mat2) {
                        return __cv.bitwiseOr(h(mat1), h(mat2)).then(wrapMat);
                    },
                    bitwiseNot: function(mat) {
                        return __cv.bitwiseNot(h(mat)).then(wrapMat);
                    },
                    inRange: function(mat, lower, upper) {
                        return __cv.inRange(h(mat), lower[0], lower[1], lower[2], upper[0], upper[1], upper[2]).then(wrapMat);
                    },

                    // ── Denoising (async) ──
                    fastNlMeansDenoising: function(mat, hParam, templateWindowSize, searchWindowSize) {
                        return __cv.fastNlMeansDenoising(h(mat), hParam || 3, templateWindowSize || 7, searchWindowSize || 21).then(wrapMat);
                    },
                    fastNlMeansDenoisingColored: function(mat, hParam, hColor, templateWindowSize, searchWindowSize) {
                        return __cv.fastNlMeansDenoisingColored(h(mat), hParam || 3, hColor || 3, templateWindowSize || 7, searchWindowSize || 21).then(wrapMat);
                    },
                };
            })();
        """.trimIndent()
    }
}
