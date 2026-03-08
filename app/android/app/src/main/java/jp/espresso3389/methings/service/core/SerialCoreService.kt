package jp.espresso3389.methings.service.core

import android.hardware.usb.UsbDeviceConnection
import android.util.Base64
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import jp.espresso3389.methings.service.core.CoreApiUtils.has
import jp.espresso3389.methings.service.core.CoreApiUtils.optBoolean
import jp.espresso3389.methings.service.core.CoreApiUtils.optInt
import jp.espresso3389.methings.service.core.CoreApiUtils.optString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serial session state — mirrors the original LocalHttpServer.SerialSessionState
 * but is now owned by [SerialCoreService].
 */
data class SerialSessionState(
    val id: String,
    val usbHandle: String,
    val deviceName: String,
    val portIndex: Int,
    val baudRate: Int,
    val dataBits: Int,
    val stopBits: Int,
    val parity: Int,
    val connection: UsbDeviceConnection,
    val port: UsbSerialPort,
    val openedAtMs: Long,
    val lock: Any = Any(),
)

data class ReadSerialLinesResult(
    val lines: List<String>,
    val bytesRead: Int,
    val truncated: Boolean,
    val truncationReason: String?,
    val elapsedMs: Long,
)

/**
 * Serial port state and handlers extracted from LocalHttpServer.
 */
class SerialCoreService(
    private val usb: UsbCoreService,
    private val permission: PermissionCoreService,
) {
    private companion object {
        const val TAG = "SerialCoreService"
    }

    val serialSessions = ConcurrentHashMap<String, SerialSessionState>()

    /** Callback to close WebSocket for a serial handle. Set by LocalHttpServer. */
    var onSessionClosed: ((serialHandle: String) -> Unit)? = null

    /** Callback to check WS connectivity. Set by LocalHttpServer. */
    var isWsConnected: ((serialHandle: String) -> Boolean)? = null

    init {
        // Wire USB close → serial close
        usb.closeSerialSessionsForUsbHandle = { handle -> closeSessionsForUsbHandle(handle) }
    }

    // ---- Public API -----------------------------------------------------------

    fun wsContract(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        return CoreApiUtils.ok(
            "ws_path_template" to "/ws/serial/{serial_handle}",
            "query" to mapOf(
                "permission_id" to "optional",
                "identity" to "optional",
                "read_timeout_ms" to 200,
                "max_read_bytes" to 4096,
                "write_timeout_ms" to 2000,
            ),
            "inbound_binary" to "raw serial bytes to write",
            "inbound_json" to mapOf(
                "type" to "serial_config | serial_lines",
                "note" to "JSON text frames to reconfigure or set line states",
            ),
            "outbound_binary" to "raw serial bytes read",
        )
    }

    fun status(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "USB serial status")
        if (perm is PermissionResult.Pending) return perm.response

        val items = serialSessions.values.sortedBy { it.id }.map { sessionToMap(it) }
        return CoreApiUtils.ok("count" to items.size, "items" to items)
    }

    fun open(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "Open USB serial session")
        if (perm is PermissionResult.Pending) return perm.response

        val usbHandle = params.optString("handle").trim()
        if (usbHandle.isBlank()) return CoreApiUtils.error("handle_required")
        val dev = usb.usbDevicesByHandle[usbHandle] ?: return CoreApiUtils.error("device_not_found", 404)
        if (!usb.usbConnections.containsKey(usbHandle)) return CoreApiUtils.error("handle_not_found", 404)

        if (!runCatching { usb.usbManager.hasPermission(dev) }.getOrDefault(false)) {
            return CoreApiUtils.error("usb_permission_required", 403)
        }

        val portIndex = params.optInt("port_index", 0)
        if (portIndex < 0) return CoreApiUtils.error("invalid_port_index")
        val baudRate = params.optInt("baud_rate", 115200).coerceIn(300, 3_500_000)
        val dataBits = params.optInt("data_bits", UsbSerialPort.DATABITS_8)
        val stopBits = params.optInt("stop_bits", UsbSerialPort.STOPBITS_1)
        val parity2 = params.optInt("parity", UsbSerialPort.PARITY_NONE)
        val dtr = if (params.has("dtr")) params.optBoolean("dtr") else null
        val rts = if (params.has("rts")) params.optBoolean("rts") else null

        val serialConn = usb.usbManager.openDevice(dev)
            ?: return CoreApiUtils.error("serial_open_failed", 500)
        return try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(dev)
            if (driver == null) {
                runCatching { serialConn.close() }
                return CoreApiUtils.error("serial_driver_not_found")
            }
            val ports = driver.ports
            if (portIndex >= ports.size) {
                runCatching { serialConn.close() }
                return CoreApiUtils.error("serial_port_not_found", 400, mapOf("available_ports" to ports.size))
            }
            val port = ports[portIndex]
            port.open(serialConn)
            port.setParameters(baudRate, dataBits, stopBits, parity2)
            if (dtr != null) runCatching { port.setDTR(dtr) }
            if (rts != null) runCatching { port.setRTS(rts) }

            val serialHandle = UUID.randomUUID().toString()
            val st = SerialSessionState(
                id = serialHandle,
                usbHandle = usbHandle,
                deviceName = dev.deviceName,
                portIndex = portIndex,
                baudRate = baudRate,
                dataBits = dataBits,
                stopBits = stopBits,
                parity = parity2,
                connection = serialConn,
                port = port,
                openedAtMs = System.currentTimeMillis(),
            )
            serialSessions[serialHandle] = st
            CoreApiUtils.ok("session" to sessionToMap(st))
        } catch (ex: Exception) {
            runCatching { serialConn.close() }
            CoreApiUtils.error("serial_open_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    fun listPorts(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "List USB serial ports")
        if (perm is PermissionResult.Pending) return perm.response

        val usbHandle = params.optString("handle").trim()
        if (usbHandle.isBlank()) return CoreApiUtils.error("handle_required")
        val dev = usb.usbDevicesByHandle[usbHandle] ?: return CoreApiUtils.error("device_not_found", 404)
        if (!usb.usbConnections.containsKey(usbHandle)) return CoreApiUtils.error("handle_not_found", 404)

        if (!runCatching { usb.usbManager.hasPermission(dev) }.getOrDefault(false)) {
            return CoreApiUtils.error("usb_permission_required", 403)
        }

        val driver = UsbSerialProber.getDefaultProber().probeDevice(dev)
            ?: return CoreApiUtils.error("serial_driver_not_found")

        val ports = driver.ports.mapIndexed { idx, port ->
            mapOf(
                "port_index" to idx,
                "port_number" to (runCatching { port.portNumber }.getOrNull() ?: idx),
                "driver_class" to port.javaClass.simpleName,
            )
        }
        return CoreApiUtils.ok(
            "handle" to usbHandle,
            "device_name" to dev.deviceName,
            "bridge_hint" to usb.guessUsbSerialBridge(dev),
            "driver" to driver.javaClass.simpleName,
            "port_count" to ports.size,
            "ports" to ports,
        )
    }

    fun close(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "Close USB serial session")
        if (perm is PermissionResult.Pending) return perm.response

        val serialHandle = params.optString("serial_handle").trim()
        if (serialHandle.isBlank()) return CoreApiUtils.error("serial_handle_required")
        val st = serialSessions.remove(serialHandle)
        if (st != null) closeSessionInternal(st)
        return CoreApiUtils.ok("closed" to (st != null))
    }

    fun read(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "Read from USB serial session")
        if (perm is PermissionResult.Pending) return perm.response

        val serialHandle = params.optString("serial_handle").trim()
        if (serialHandle.isBlank()) return CoreApiUtils.error("serial_handle_required")
        val st = serialSessions[serialHandle] ?: return CoreApiUtils.error("serial_handle_not_found", 404)
        val maxBytes = params.optInt("max_bytes", 4096).coerceIn(1, 1024 * 1024)
        val timeoutMs = params.optInt("timeout_ms", 200).coerceIn(0, 60_000)

        val buf = ByteArray(maxBytes)
        return try {
            val n = synchronized(st.lock) { st.port.read(buf, timeoutMs) }
            val out = if (n > 0) buf.copyOfRange(0, n.coerceIn(0, buf.size)) else ByteArray(0)
            CoreApiUtils.ok(
                "serial_handle" to serialHandle,
                "bytes_read" to out.size,
                "data" to out.asUByteArray(),
            )
        } catch (ex: Exception) {
            CoreApiUtils.error("serial_read_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    fun write(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "Write to USB serial session")
        if (perm is PermissionResult.Pending) return perm.response

        val serialHandle = params.optString("serial_handle").trim()
        if (serialHandle.isBlank()) return CoreApiUtils.error("serial_handle_required")
        val st = serialSessions[serialHandle] ?: return CoreApiUtils.error("serial_handle_not_found", 404)
        val timeoutMs = params.optInt("timeout_ms", 2000).coerceIn(0, 60_000)
        val dataB64 = params.optString("data_b64").trim()
        if (dataB64.isBlank()) return CoreApiUtils.error("data_b64_required")
        val bytes = try {
            Base64.decode(dataB64, Base64.DEFAULT)
        } catch (_: Exception) {
            return CoreApiUtils.error("invalid_data_b64")
        }
        if (bytes.isEmpty()) return CoreApiUtils.error("data_empty")

        return try {
            val n = synchronized(st.lock) { st.port.write(bytes, timeoutMs) }
            CoreApiUtils.ok("serial_handle" to serialHandle, "bytes_written" to n)
        } catch (ex: Exception) {
            CoreApiUtils.error("serial_write_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    fun lines(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "Set USB serial line state")
        if (perm is PermissionResult.Pending) return perm.response

        val serialHandle = params.optString("serial_handle").trim()
        if (serialHandle.isBlank()) return CoreApiUtils.error("serial_handle_required")
        val st = serialSessions[serialHandle] ?: return CoreApiUtils.error("serial_handle_not_found", 404)
        val dtr = if (params.has("dtr")) params.optBoolean("dtr") else null
        val rts = if (params.has("rts")) params.optBoolean("rts") else null
        if (dtr == null && rts == null) return CoreApiUtils.error("line_state_required")

        return try {
            synchronized(st.lock) {
                if (dtr != null) st.port.setDTR(dtr)
                if (rts != null) st.port.setRTS(rts)
            }
            CoreApiUtils.ok(
                "serial_handle" to serialHandle,
                "dtr" to dtr,
                "rts" to rts,
            )
        } catch (ex: Exception) {
            CoreApiUtils.error("serial_lines_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    fun exchange(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "Serial exchange (send+receive lines)")
        if (perm is PermissionResult.Pending) return perm.response

        val serialHandle = params.optString("serial_handle").trim()
        if (serialHandle.isBlank()) return CoreApiUtils.error("serial_handle_required")
        val st = serialSessions[serialHandle] ?: return CoreApiUtils.error("serial_handle_not_found", 404)

        val maxLines = params.optInt("max_lines", 50).coerceIn(1, 5000)
        val idleTimeoutMs = params.optInt("idle_timeout_ms", 500).coerceIn(20, 60_000)
        val totalTimeoutMs = params.optInt("total_timeout_ms", 10000).coerceIn(100, 120_000)
        val stripEcho = params.optBoolean("strip_echo", true)
        val writeTimeoutMs = params.optInt("write_timeout_ms", 2000).coerceIn(100, 60_000)

        val sendB64 = params.optString("send_b64").trim()
        val sendText = params.optString("send").let { if (it == "null") "" else it }
        val sendBytes: ByteArray? = when {
            sendB64.isNotBlank() -> try {
                Base64.decode(sendB64, Base64.DEFAULT)
            } catch (_: Exception) {
                return CoreApiUtils.error("invalid_send_b64")
            }
            sendText.isNotEmpty() -> sendText.toByteArray(Charsets.UTF_8)
            else -> null
        }

        return try {
            val result = synchronized(st.lock) {
                if (sendBytes != null && sendBytes.isNotEmpty()) {
                    writeSerialAll(st, sendBytes, writeTimeoutMs)
                }
                readSerialLines(st, maxLines, idleTimeoutMs, totalTimeoutMs)
            }

            var resultLines = result.lines
            if (stripEcho && sendBytes != null) {
                val sentStr = String(sendBytes, Charsets.UTF_8).trimEnd('\r', '\n')
                if (sentStr.isNotBlank()) {
                    val sentLines = sentStr.split('\n').map { it.trimEnd('\r') }
                    var strip = 0
                    for (i in sentLines.indices) {
                        if (i < resultLines.size && resultLines[i].trimEnd() == sentLines[i].trimEnd()) {
                            strip++
                        } else break
                    }
                    if (strip > 0) resultLines = resultLines.drop(strip)
                }
            }

            CoreApiUtils.ok(
                "serial_handle" to serialHandle,
                "lines" to resultLines,
                "line_count" to resultLines.size,
                "bytes_read" to result.bytesRead,
                "truncated" to result.truncated,
                "truncation_reason" to result.truncationReason,
                "elapsed_ms" to result.elapsedMs,
            )
        } catch (ex: Exception) {
            CoreApiUtils.error("serial_exchange_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    // ---- Internal helpers (also used by McuCoreService) -------------------------

    fun sessionToMap(st: SerialSessionState): Map<String, Any?> {
        return mapOf(
            "serial_handle" to st.id,
            "usb_handle" to st.usbHandle,
            "device_name" to st.deviceName,
            "port_index" to st.portIndex,
            "baud_rate" to st.baudRate,
            "data_bits" to st.dataBits,
            "stop_bits" to st.stopBits,
            "parity" to st.parity,
            "opened_at_ms" to st.openedAtMs,
            "ws_path" to "/ws/serial/${st.id}",
            "ws_connected" to (isWsConnected?.invoke(st.id) ?: false),
            "driver" to "usb-serial-for-android",
        )
    }

    fun closeSessionInternal(st: SerialSessionState) {
        onSessionClosed?.invoke(st.id)
        runCatching { st.port.close() }
        runCatching { st.connection.close() }
    }

    fun closeSessionsForUsbHandle(usbHandle: String): Int {
        val targets = serialSessions.values.filter { it.usbHandle == usbHandle }
        for (st in targets) {
            serialSessions.remove(st.id)
            closeSessionInternal(st)
        }
        return targets.size
    }

    fun drainSerialInput(st: SerialSessionState, perReadTimeoutMs: Int, maxRounds: Int) {
        val buf = ByteArray(1024)
        repeat(maxRounds.coerceAtLeast(1)) {
            val n = try {
                st.port.read(buf, perReadTimeoutMs.coerceIn(0, 5000))
            } catch (_: Exception) { 0 }
            if (n <= 0) return
        }
    }

    fun writeSerialAll(st: SerialSessionState, data: ByteArray, timeoutMs: Int) {
        if (data.isEmpty()) return
        var offset = 0
        while (offset < data.size) {
            val chunk = data.copyOfRange(offset, data.size)
            st.port.write(chunk, timeoutMs)
            offset = data.size
        }
    }

    fun readSerialLines(
        st: SerialSessionState,
        maxLines: Int,
        idleTimeoutMs: Int,
        totalTimeoutMs: Int,
    ): ReadSerialLinesResult {
        val lines = mutableListOf<String>()
        val buf = ByteArray(4096)
        val textBuf = StringBuilder()
        var bytesRead = 0
        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + totalTimeoutMs.toLong()
        var lastByteAt = startedAt
        var truncated = false
        var truncationReason: String? = null
        val perReadTimeout = 100

        while (lines.size < maxLines) {
            val now = System.currentTimeMillis()
            if (now >= deadline) { truncated = true; truncationReason = "total_timeout"; break }
            if (now - lastByteAt >= idleTimeoutMs) break
            val remainTotal = (deadline - now).coerceAtLeast(1L).toInt()
            val remainIdle = (idleTimeoutMs - (now - lastByteAt).toInt()).coerceAtLeast(1)
            val timeout = minOf(perReadTimeout, remainTotal, remainIdle)
            if (timeout <= 0) break

            val n = try { st.port.read(buf, timeout) } catch (_: Exception) { 0 }
            if (n > 0) {
                lastByteAt = System.currentTimeMillis()
                bytesRead += n
                textBuf.append(String(buf, 0, n, Charsets.UTF_8))
                while (lines.size < maxLines) {
                    val idx = textBuf.indexOf('\n')
                    if (idx < 0) break
                    lines.add(textBuf.substring(0, idx).trimEnd('\r'))
                    textBuf.delete(0, idx + 1)
                }
                if (lines.size >= maxLines) { truncated = true; truncationReason = "max_lines" }
            }
        }
        if (!truncated && textBuf.isNotEmpty() && lines.size < maxLines) {
            lines.add(textBuf.toString().trimEnd('\r'))
        }
        return ReadSerialLinesResult(
            lines = lines, bytesRead = bytesRead, truncated = truncated,
            truncationReason = truncationReason, elapsedMs = System.currentTimeMillis() - startedAt,
        )
    }
}
