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
    }
}
