package jp.espresso3389.methings.service.core

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Base64
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import jp.espresso3389.methings.service.core.CoreApiUtils.has
import jp.espresso3389.methings.service.core.CoreApiUtils.optBoolean
import jp.espresso3389.methings.service.core.CoreApiUtils.optInt
import jp.espresso3389.methings.service.core.CoreApiUtils.optLong
import jp.espresso3389.methings.service.core.CoreApiUtils.optMap
import jp.espresso3389.methings.service.core.CoreApiUtils.optString
import jp.espresso3389.methings.service.mcu.esp.EspFlashException
import jp.espresso3389.methings.service.mcu.esp.EspFlashStageException
import jp.espresso3389.methings.service.mcu.esp.EspSerialSession
import jp.espresso3389.methings.service.mcu.esp.EspSyncException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

// ---- Internal data classes --------------------------------------------------

private data class EspSerialSelection(
    val interfaceObj: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
)

private data class McuFlashSegment(
    val relPath: String,
    val offset: Int,
    val bytes: ByteArray,
)

private data class MicroPythonSessionLease(
    val serial: SerialSessionState,
    val ephemeral: Boolean,
    val model: String,
    val handle: String?,
)

private data class MicroPythonRawExecResult(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val raw: ByteArray,
)

/**
 * File-system access callbacks — provided by LocalHttpServer.
 * Keeps MCU service decoupled from the path resolution scheme.
 */
interface McuFileResolver {
    /** Read bytes from a relative path. Returns (bytes, displayPath). Throws on error. */
    fun readPathBytes(path: String): Pair<ByteArray, String>
    /** Resolve a relative path to its parent directory File (for flash plan). Returns null if invalid. */
    fun resolveParentDir(path: String): File?
    /** The user root directory. */
    fun userRoot(): File
}

/**
 * MCU operations extracted from LocalHttpServer.
 * Handles ESP32 probing, flashing, serial diagnostics, and MicroPython REPL.
 */
class McuCoreService(
    private val context: Context,
    private val usb: UsbCoreService,
    private val serial: SerialCoreService,
    private val permission: PermissionCoreService,
    private val fileResolver: McuFileResolver,
) {
    private companion object {
        const val TAG = "McuCoreService"
    }

    // ---- Public API -----------------------------------------------------------

    fun models(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        return CoreApiUtils.ok(
            "models" to listOf(
                mapOf(
                    "id" to "esp32",
                    "family" to "esp32",
                    "protocol" to "espressif-rom-serial",
                    "status" to "supported",
                    "notes" to "Initial model support. Flash pipeline will be added incrementally.",
                )
            )
        )
    }

    fun probe(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val model = params.optString("model").trim().lowercase(Locale.US)
        if (model.isBlank()) return CoreApiUtils.error("model_required")
        if (model != "esp32") return CoreApiUtils.error("unsupported_model", 400,
            mapOf("model" to model, "supported_models" to listOf("esp32")))

        val name = params.optString("name").trim()
        val vid = params.optInt("vendor_id", -1)
        val pid = params.optInt("product_id", -1)
        val timeoutMs = params.optLong("permission_timeout_ms", 0L).coerceIn(0L, 120_000L)
        val dev = usb.findUsbDevice(name, vid, pid) ?: return CoreApiUtils.error("usb_device_not_found", 404)

        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb",
            "MCU probe: model=$model vid=${dev.vendorId} pid=${dev.productId} name=${dev.deviceName}")
        if (perm is PermissionResult.Pending) return perm.response

        if (!usb.ensureUsbPermission(dev, timeoutMs)) {
            return CoreApiUtils.error("usb_permission_required", 403,
                mapOf("name" to dev.deviceName, "vendor_id" to dev.vendorId, "product_id" to dev.productId))
        }

        val serialPort = findSerialBulkPort(dev)
        val bridge = usb.guessUsbSerialBridge(dev)
        return CoreApiUtils.ok(
            "model" to model,
            "device" to usb.usbDeviceToMap(dev),
            "bridge_hint" to bridge,
            "serial_port" to serialPort,
            "ready_for_serial_protocol" to (serialPort != null),
        )
    }

    fun flash(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val model = params.optString("model").trim().lowercase(Locale.US)
        if (model.isBlank()) return CoreApiUtils.error("model_required")
        if (model != "esp32") return CoreApiUtils.error("unsupported_model", 400,
            mapOf("model" to model, "supported_models" to listOf("esp32")))

        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usb.usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val dev = usb.usbDevicesByHandle[handle] ?: return CoreApiUtils.error("device_not_found", 404)

        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb",
            "MCU flash: model=$model handle=$handle")
        if (perm is PermissionResult.Pending) return perm.response

        val imagePath = params.optString("image_path").trim()
        val timeoutMs = params.optInt("timeout_ms", 2000).coerceIn(100, 60_000)
        val reboot = params.optBoolean("reboot", true)
        val autoEnterBootloader = params.optBoolean("auto_enter_bootloader", true)
        val debug = params.optBoolean("debug", false)

        val payload = JSONObject(params.filterValues { it != null }.mapValues { (_, v) ->
            when (v) { is Map<*, *> -> JSONObject(v as Map<String, Any?>); is List<*> -> JSONArray(v); else -> v }
        })

        val selection = pickEspSerialSelection(dev, payload)
            ?: return CoreApiUtils.error("serial_port_not_found")

        val segments = try {
            buildMcuFlashSegments(payload, fallbackPath = imagePath)
        } catch (ex: IllegalArgumentException) {
            return CoreApiUtils.error(ex.message ?: "invalid_segments")
        } catch (ex: Exception) {
            return CoreApiUtils.error("segment_prepare_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
        if (segments.isEmpty()) return CoreApiUtils.error("segments_required")

        val t0 = System.currentTimeMillis()
        return try {
            val bridge = usb.guessUsbSerialBridge(dev)
            val sessionCtx = EspSerialSession(
                usbManager = usb.usbManager, dev = dev, conn = conn,
                inEp = selection.inEndpoint, outEp = selection.outEndpoint,
                timeoutMs = timeoutMs, bridgeHint = bridge,
                interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!sessionCtx.usesUsbSerial()) {
                    val force = params.optBoolean("force_claim", true)
                    if (!conn.claimInterface(selection.interfaceObj, force)) {
                        return CoreApiUtils.error("claim_interface_failed", 500)
                    }
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                sessionCtx.configureSerial()
                sessionCtx.flushInput()
                var syncDebug: JSONArray? = null
                val flashDebug: JSONArray? = if (debug) JSONArray() else null
                var postSyncProbe: JSONObject? = null
                var spiAttachDebug: JSONObject? = null
                if (autoEnterBootloader) {
                    syncDebug = sessionCtx.syncWithAutoResetProfiles(bridge, selection.interfaceObj.id, debug = debug)
                } else {
                    val syncStart = System.currentTimeMillis()
                    try {
                        sessionCtx.sync()
                        if (debug) {
                            syncDebug = JSONArray().put(JSONObject()
                                .put("profile", "direct_sync").put("ok", true)
                                .put("elapsed_ms", (System.currentTimeMillis() - syncStart).coerceAtLeast(0L)))
                        }
                    } catch (ex: Exception) {
                        if (debug) {
                            throw EspSyncException(ex.message ?: "esp_response_timeout", JSONArray().put(JSONObject()
                                .put("profile", "direct_sync").put("ok", false)
                                .put("error", ex.message ?: "unknown_error")
                                .put("elapsed_ms", (System.currentTimeMillis() - syncStart).coerceAtLeast(0L))))
                        }
                        throw ex
                    }
                }
                if (debug) {
                    val probeStart = System.currentTimeMillis()
                    postSyncProbe = try {
                        val magic = sessionCtx.readChipDetectMagic()
                        JSONObject().put("ok", true).put("register", "0x40001000")
                            .put("value", String.format(Locale.US, "0x%08x", magic))
                            .put("elapsed_ms", (System.currentTimeMillis() - probeStart).coerceAtLeast(0L))
                    } catch (ex: Exception) {
                        JSONObject().put("ok", false).put("register", "0x40001000")
                            .put("error", ex.message ?: "unknown_error")
                            .put("elapsed_ms", (System.currentTimeMillis() - probeStart).coerceAtLeast(0L))
                    }
                }
                val spiAttachStart = System.currentTimeMillis()
                try {
                    val attach = sessionCtx.attachEsp32SpiFlash()
                    if (debug) {
                        spiAttachDebug = attach.put("ok", true)
                            .put("elapsed_ms", (System.currentTimeMillis() - spiAttachStart).coerceAtLeast(0L))
                    }
                } catch (ex: Exception) {
                    if (debug) {
                        spiAttachDebug = JSONObject().put("ok", false).put("error", ex.message ?: "unknown_error")
                            .put("elapsed_ms", (System.currentTimeMillis() - spiAttachStart).coerceAtLeast(0L))
                        throw EspFlashException(ex.message ?: "spi_attach_failed", JSONObject()
                            .put("detail", ex.message ?: "spi_attach_failed")
                            .put("failed_segment_index", 0)
                            .put("failed_segment_path", segments.firstOrNull()?.relPath ?: JSONObject.NULL)
                            .put("failed_stage", "spi_attach")
                            .put("sync_debug", syncDebug ?: JSONArray())
                            .put("post_sync_probe", postSyncProbe ?: JSONObject.NULL)
                            .put("spi_attach_debug", spiAttachDebug)
                            .put("flash_debug", flashDebug ?: JSONArray()))
                    }
                    throw ex
                }
                var totalBlocks = 0
                val written = mutableListOf<Map<String, Any?>>()
                for ((idx, seg) in segments.withIndex()) {
                    val isLast = idx == segments.lastIndex
                    val segStart = System.currentTimeMillis()
                    val r = try {
                        sessionCtx.flashImage(seg.bytes, seg.offset, reboot = reboot && isLast)
                    } catch (ex: EspFlashStageException) {
                        if (debug) {
                            flashDebug?.put(JSONObject()
                                .put("segment_index", idx).put("path", seg.relPath).put("offset", seg.offset)
                                .put("ok", false).put("stage", ex.stage)
                                .put("block_index", ex.blockIndex).put("blocks_written", ex.blocksWritten)
                                .put("elapsed_ms", (System.currentTimeMillis() - segStart).coerceAtLeast(0L))
                                .put("error", ex.message ?: "unknown_error"))
                            throw EspFlashException(ex.message ?: ex.stage, JSONObject()
                                .put("detail", ex.message ?: ex.stage)
                                .put("failed_segment_index", idx).put("failed_segment_path", seg.relPath)
                                .put("failed_stage", ex.stage)
                                .put("sync_debug", syncDebug ?: JSONArray())
                                .put("post_sync_probe", postSyncProbe ?: JSONObject.NULL)
                                .put("spi_attach_debug", spiAttachDebug ?: JSONObject.NULL)
                                .put("flash_debug", flashDebug ?: JSONArray()))
                        }
                        throw ex
                    }
                    totalBlocks += r.blocksWritten
                    written.add(mapOf("path" to seg.relPath, "offset" to seg.offset,
                        "size" to seg.bytes.size, "md5" to md5Hex(seg.bytes),
                        "blocks_written" to r.blocksWritten, "block_size" to r.blockSize))
                    if (debug) {
                        flashDebug?.put(JSONObject()
                            .put("segment_index", idx).put("path", seg.relPath).put("offset", seg.offset)
                            .put("ok", true).put("stage", "flash_data_done")
                            .put("blocks_written", r.blocksWritten)
                            .put("elapsed_ms", (System.currentTimeMillis() - segStart).coerceAtLeast(0L)))
                    }
                }
                val elapsed = (System.currentTimeMillis() - t0).coerceAtLeast(0L)
                val result = mutableMapOf<String, Any?>(
                    "status" to "ok", "model" to model, "handle" to handle,
                    "segment_count" to segments.size, "segments" to written,
                    "interface_id" to selection.interfaceObj.id,
                    "in_endpoint_address" to selection.inEndpoint.address,
                    "out_endpoint_address" to selection.outEndpoint.address,
                    "blocks_written_total" to totalBlocks, "elapsed_ms" to elapsed,
                    "transport" to if (sessionCtx.usesUsbSerial()) "usb-serial-for-android" else "usb-bulk",
                )
                if (debug) {
                    result["sync_debug"] = syncDebug?.let { CoreApiUtils.fromJsonPayload(JSONObject().put("_", it))["_"] }
                    result["post_sync_probe"] = postSyncProbe?.let { CoreApiUtils.fromJsonPayload(it) }
                    result["spi_attach_debug"] = spiAttachDebug?.let { CoreApiUtils.fromJsonPayload(it) }
                    result["flash_debug"] = flashDebug?.let { CoreApiUtils.fromJsonPayload(JSONObject().put("_", it))["_"] }
                }
                result
            } finally {
                sessionCtx.close()
            }
        } catch (ex: EspSyncException) {
            CoreApiUtils.error("mcu_flash_failed", 500, mapOf(
                "detail" to (ex.message ?: ""),
                "sync_debug" to CoreApiUtils.fromJsonPayload(JSONObject().put("_", ex.attempts))["_"],
            ))
        } catch (ex: EspFlashException) {
            CoreApiUtils.error("mcu_flash_failed", 500,
                CoreApiUtils.fromJsonPayload(ex.detailPayload))
        } catch (ex: Exception) {
            CoreApiUtils.error("mcu_flash_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    fun flashPlan(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val planPath = params.optString("plan_path").trim()
        if (planPath.isBlank()) return CoreApiUtils.error("plan_path_required")

        val planBytes: ByteArray
        try {
            planBytes = fileResolver.readPathBytes(planPath).first
        } catch (ex: IllegalArgumentException) {
            return when (ex.message ?: "") {
                "path_outside_worker_home" -> CoreApiUtils.error("path_outside_worker_home")
                "worker_unavailable" -> CoreApiUtils.error("worker_unavailable", 503)
                "file_too_large" -> CoreApiUtils.error("plan_too_large")
                else -> CoreApiUtils.error("plan_not_found", 404)
            }
        }

        val planJson = try {
            JSONObject(planBytes.toString(Charsets.UTF_8))
        } catch (ex: Exception) {
            return CoreApiUtils.error("invalid_plan_json", 400, mapOf("detail" to (ex.message ?: "")))
        }
        val flashFiles = planJson.optJSONObject("flash_files")
            ?: return CoreApiUtils.error("plan_flash_files_required")

        val chipFromPlan = planJson.optJSONObject("extra_esptool_args")
            ?.optString("chip", "")?.trim()?.lowercase(Locale.US) ?: ""
        val model = params.optString("model", chipFromPlan.ifBlank { "esp32" }).trim().lowercase(Locale.US)
        if (model != "esp32") return CoreApiUtils.error("unsupported_model", 400,
            mapOf("model" to model, "supported_models" to listOf("esp32")))

        val userRoot = fileResolver.userRoot().canonicalFile
        val baseDir = fileResolver.resolveParentDir(planPath) ?: userRoot
        val sortedOffsets = flashFiles.keys().asSequence().toList().sortedBy { parseOffsetToInt(it) }
        if (sortedOffsets.isEmpty()) return CoreApiUtils.error("plan_flash_files_empty")

        val segments = mutableListOf<Map<String, Any?>>()
        val missing = mutableListOf<Map<String, Any?>>()
        for (offKey in sortedOffsets) {
            val offset = parseOffsetToInt(offKey)
                ?: return CoreApiUtils.error("invalid_offset", 400, mapOf("offset" to offKey))
            val raw = flashFiles.opt(offKey)
            val relPath = (if (raw is String) raw else flashFiles.optString(offKey, "")).trim()
            if (relPath.isBlank()) return CoreApiUtils.error("empty_path_for_offset", 400, mapOf("offset" to offKey))
            val file = File(baseDir, relPath).canonicalFile
            if (!file.path.startsWith(userRoot.path)) {
                return CoreApiUtils.error("path_outside_user_dir", 400, mapOf("path" to relPath))
            }
            if (file.exists() && file.isFile) {
                segments.add(mapOf("offset" to offset, "offset_hex" to String.format("0x%x", offset),
                    "path" to relPath, "size" to file.length(), "exists" to true))
            } else {
                missing.add(mapOf("offset" to offset, "offset_hex" to String.format("0x%x", offset),
                    "path" to relPath, "exists" to false))
            }
        }
        return CoreApiUtils.ok("model" to model, "segment_count" to segments.size,
            "segments" to segments, "missing" to missing, "ready" to missing.isEmpty())
    }

    fun diagSerial(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val model = params.optString("model", "esp32").trim().lowercase(Locale.US)
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usb.usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val dev = usb.usbDevicesByHandle[handle] ?: return CoreApiUtils.error("device_not_found", 404)

        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb",
            "MCU diag serial: handle=$handle")
        if (perm is PermissionResult.Pending) return perm.response

        val payload = toJsonObject(params)
        val selection = pickEspSerialSelection(dev, payload) ?: return CoreApiUtils.error("serial_port_not_found")
        val bridge = usb.guessUsbSerialBridge(dev)
        val timeoutMs = params.optInt("timeout_ms", 2000).coerceIn(100, 60_000)
        val sniffMs = params.optInt("sniff_ms", 300).coerceIn(50, 10_000)
        val enterBootloader = params.optBoolean("enter_bootloader", true)
        val baudRate = params.optInt("baud_rate", 115200).coerceIn(300, 3_500_000)

        return try {
            val session = EspSerialSession(
                usbManager = usb.usbManager, dev = dev, conn = conn,
                inEp = selection.inEndpoint, outEp = selection.outEndpoint,
                timeoutMs = timeoutMs, bridgeHint = bridge, interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!session.usesUsbSerial()) {
                    conn.claimInterface(selection.interfaceObj, true)
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                session.configureSerial()
                session.flushInput()
                val baseline = session.sniffRaw(sniffMs)
                if (enterBootloader) {
                    session.enterBootloaderIfSupported(bridge, selection.interfaceObj.id)
                    session.settleAfterBootloaderReset()
                }
                session.sendSyncProbe()
                val reply = session.sniffRaw(sniffMs)
                val frames = session.collectSlipFrames(400, 8)
                CoreApiUtils.ok(
                    "model" to model, "handle" to handle,
                    "bridge_hint" to bridge,
                    "interface_id" to selection.interfaceObj.id,
                    "baseline" to baseline.asUByteArray(),
                    "baseline_ascii" to bytesToAsciiPreview(baseline),
                    "baseline_length" to baseline.size,
                    "reply" to reply.asUByteArray(),
                    "reply_ascii" to bytesToAsciiPreview(reply),
                    "reply_length" to reply.size,
                    "slip_frames_found" to frames.length(),
                )
            } finally {
                session.close()
            }
        } catch (ex: Exception) {
            CoreApiUtils.error("mcu_diag_serial_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    fun serialLines(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usb.usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val dev = usb.usbDevicesByHandle[handle] ?: return CoreApiUtils.error("device_not_found", 404)

        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb",
            "MCU serial lines: handle=$handle")
        if (perm is PermissionResult.Pending) return perm.response

        val payload = toJsonObject(params)
        val selection = pickEspSerialSelection(dev, payload) ?: return CoreApiUtils.error("serial_port_not_found")
        val bridge = usb.guessUsbSerialBridge(dev)
        val timeoutMs = params.optInt("timeout_ms", 2000).coerceIn(100, 60_000)

        return try {
            val session = EspSerialSession(
                usbManager = usb.usbManager, dev = dev, conn = conn,
                inEp = selection.inEndpoint, outEp = selection.outEndpoint,
                timeoutMs = timeoutMs, bridgeHint = bridge, interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!session.usesUsbSerial()) {
                    conn.claimInterface(selection.interfaceObj, true)
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                session.configureSerial()

                val sequence = params.optString("sequence").trim().lowercase(Locale.US)
                @Suppress("UNCHECKED_CAST")
                val steps = (params["steps"] as? List<Map<String, Any?>>)
                val scriptPayload = toJsonObject(params).optJSONArray("script")

                if (scriptPayload != null && scriptPayload.length() > 0) {
                    for (i in 0 until scriptPayload.length()) {
                        val step = scriptPayload.optJSONObject(i) ?: continue
                        val stepDtr = if (step.has("dtr")) step.optBoolean("dtr") else null
                        val stepRts = if (step.has("rts")) step.optBoolean("rts") else null
                        val stepSleep = step.optInt("sleep_ms", 0).coerceIn(0, 5000)
                        session.setModemLines(stepDtr, stepRts)
                        if (stepSleep > 0) Thread.sleep(stepSleep.toLong())
                    }
                } else if (sequence.isNotBlank()) {
                    when (sequence) {
                        "enter_bootloader", "download", "bootloader" -> {
                            session.enterBootloaderIfSupported(bridge, selection.interfaceObj.id)
                            session.settleAfterBootloaderReset()
                        }
                        "enter_bootloader_inverted", "download_inverted", "bootloader_inverted" -> {
                            session.enterBootloaderInvertedIfSupported(bridge, selection.interfaceObj.id)
                            session.settleAfterBootloaderReset()
                        }
                        "run", "normal" -> {
                            session.setModemLines(dtr = false, rts = false)
                            Thread.sleep(40)
                        }
                        else -> return CoreApiUtils.error("invalid_sequence", 400)
                    }
                } else if (steps != null) {
                    for (step in steps) {
                        val dtr = if (step.has("dtr")) step.optBoolean("dtr") else null
                        val rts = if (step.has("rts")) step.optBoolean("rts") else null
                        val delayMs = step.optInt("delay_ms", 50).coerceIn(0, 5000).toLong()
                        session.setModemLines(dtr, rts)
                        if (delayMs > 0) Thread.sleep(delayMs)
                    }
                } else {
                    val dtr = if (params.has("dtr")) params.optBoolean("dtr") else null
                    val rts = if (params.has("rts")) params.optBoolean("rts") else null
                    session.setModemLines(dtr, rts)
                }
                CoreApiUtils.ok("handle" to handle, "bridge_hint" to bridge,
                    "interface_id" to selection.interfaceObj.id)
            } finally {
                session.close()
            }
        } catch (ex: Exception) {
            CoreApiUtils.error("mcu_serial_lines_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    fun reset(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usb.usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val dev = usb.usbDevicesByHandle[handle] ?: return CoreApiUtils.error("device_not_found", 404)

        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb",
            "MCU reset: handle=$handle")
        if (perm is PermissionResult.Pending) return perm.response

        val payload = toJsonObject(params)
        val selection = pickEspSerialSelection(dev, payload) ?: return CoreApiUtils.error("serial_port_not_found")
        val bridge = usb.guessUsbSerialBridge(dev)
        val mode = params.optString("mode", "reboot").trim().ifBlank { "reboot" }
        val timeoutMs = params.optInt("timeout_ms", 2000).coerceIn(100, 60_000)
        val captureLines = params.optBoolean("capture_lines", false)
        val captureMaxLines = params.optInt("capture_max_lines", 50).coerceIn(1, 500)
        val captureIdleMs = params.optInt("capture_idle_ms", 500).coerceIn(20, 30_000)
        val captureTimeoutMs = params.optInt("capture_timeout_ms", 5000).coerceIn(100, 60_000)

        return try {
            val session = EspSerialSession(
                usbManager = usb.usbManager, dev = dev, conn = conn,
                inEp = selection.inEndpoint, outEp = selection.outEndpoint,
                timeoutMs = timeoutMs, bridgeHint = bridge, interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!session.usesUsbSerial()) {
                    conn.claimInterface(selection.interfaceObj, true)
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                session.configureSerial()
                when (mode) {
                    "bootloader" -> {
                        session.enterBootloaderIfSupported(bridge, selection.interfaceObj.id)
                        session.settleAfterBootloaderReset()
                    }
                    "bootloader_inverted" -> {
                        session.enterBootloaderInvertedIfSupported(bridge, selection.interfaceObj.id)
                        session.settleAfterBootloaderReset()
                    }
                    "run", "normal" -> {
                        session.setModemLines(dtr = false, rts = false)
                        Thread.sleep(40)
                    }
                    else -> session.rebootToRunIfSupported(bridge, selection.interfaceObj.id) // "reboot"
                }
                val result = mutableMapOf<String, Any?>(
                    "status" to "ok", "handle" to handle, "mode" to mode,
                    "bridge_hint" to bridge, "interface_id" to selection.interfaceObj.id,
                )
                if (captureLines) {
                    Thread.sleep(100)
                    try {
                        val baudRate = params.optInt("baud_rate", 115200).coerceIn(300, 3_500_000)
                        val serialConn = usb.usbManager.openDevice(dev) ?: throw Exception("usb_open_failed")
                        try {
                            val driver = UsbSerialProber.getDefaultProber().probeDevice(dev)
                                ?: throw Exception("serial_driver_not_found")
                            val port = driver.ports.firstOrNull() ?: throw Exception("serial_port_not_found")
                            port.open(serialConn)
                            port.setParameters(baudRate, UsbSerialPort.DATABITS_8,
                                UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                            val tmpSt = SerialSessionState(
                                id = "", usbHandle = handle, deviceName = dev.deviceName,
                                portIndex = 0, baudRate = baudRate, dataBits = UsbSerialPort.DATABITS_8,
                                stopBits = UsbSerialPort.STOPBITS_1, parity = UsbSerialPort.PARITY_NONE,
                                connection = serialConn, port = port, openedAtMs = System.currentTimeMillis(),
                            )
                            serial.drainSerialInput(tmpSt, 50, 5)
                            val lr = serial.readSerialLines(tmpSt, captureMaxLines, captureIdleMs, captureTimeoutMs)
                            result["lines"] = lr.lines
                            result["line_count"] = lr.lines.size
                            result["bytes_read"] = lr.bytesRead
                            result["truncated"] = lr.truncated
                            result["truncation_reason"] = lr.truncationReason
                            result["capture_elapsed_ms"] = lr.elapsedMs
                            runCatching { port.close() }
                        } finally {
                            runCatching { serialConn.close() }
                        }
                    } catch (ex: Exception) {
                        result["capture_error"] = ex.message ?: "capture_failed"
                    }
                }
                result
            } finally {
                session.close()
            }
        } catch (ex: Exception) {
            CoreApiUtils.error("mcu_reset_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    fun serialMonitor(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usb.usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val dev = usb.usbDevicesByHandle[handle] ?: return CoreApiUtils.error("device_not_found", 404)

        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb",
            "MCU serial monitor: handle=$handle")
        if (perm is PermissionResult.Pending) return perm.response

        val payload = toJsonObject(params)
        val selection = pickEspSerialSelection(dev, payload) ?: return CoreApiUtils.error("serial_port_not_found")
        val bridge = usb.guessUsbSerialBridge(dev)
        val timeoutMs = params.optInt("timeout_ms", 2000).coerceIn(100, 60_000)
        val durationMs = params.optInt("duration_ms", 2000).coerceIn(100, 30_000)
        val flush = params.optBoolean("flush", true)

        return try {
            val session = EspSerialSession(
                usbManager = usb.usbManager, dev = dev, conn = conn,
                inEp = selection.inEndpoint, outEp = selection.outEndpoint,
                timeoutMs = timeoutMs, bridgeHint = bridge, interfaceId = selection.interfaceObj.id,
            )
            try {
                if (!session.usesUsbSerial()) {
                    conn.claimInterface(selection.interfaceObj, true)
                    runCatching { conn.setInterface(selection.interfaceObj) }
                }
                session.configureSerial()
                if (flush) session.flushInput()
                val data = session.sniffRaw(durationMs)
                CoreApiUtils.ok(
                    "handle" to handle, "bridge_hint" to bridge,
                    "data" to data.asUByteArray(),
                    "data_ascii" to bytesToAsciiPreview(data),
                    "length" to data.size,
                )
            } finally {
                session.close()
            }
        } catch (ex: Exception) {
            CoreApiUtils.error("mcu_serial_monitor_failed", 500, mapOf("detail" to (ex.message ?: "")))
        }
    }

    // ---- MicroPython ----------------------------------------------------------

    fun micropythonExec(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "MicroPython exec")
        if (perm is PermissionResult.Pending) return perm.response

        val lease = acquireMicroPythonSession(params) ?: return CoreApiUtils.error("micropython_session_failed")
        return try {
            val code = extractMicroPythonCode(params)
                ?: return CoreApiUtils.error("code_required")
            val result = runMicroPythonRawExec(lease.serial, code)
            val includeRaw = params.optBoolean("include_raw", false)
            CoreApiUtils.ok(
                "model" to lease.model,
                "serial_handle" to lease.serial.id,
                "stdout" to String(result.stdout, Charsets.UTF_8),
                "stderr" to String(result.stderr, Charsets.UTF_8),
                *if (includeRaw) arrayOf(
                    "raw" to result.raw.asUByteArray(),
                    "raw_ascii" to bytesToAsciiPreview(result.raw),
                ) else emptyArray(),
            )
        } catch (ex: Exception) {
            CoreApiUtils.error("micropython_exec_failed", 500, mapOf("detail" to (ex.message ?: "")))
        } finally {
            releaseMicroPythonSession(lease)
        }
    }

    fun micropythonWriteFile(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "MicroPython write_file")
        if (perm is PermissionResult.Pending) return perm.response

        val lease = acquireMicroPythonSession(params) ?: return CoreApiUtils.error("micropython_session_failed")
        return try {
            val remotePath = params.optString("remote_path").trim()
            if (remotePath.isBlank()) return CoreApiUtils.error("remote_path_required")
            val content = extractMicroPythonContent(params) ?: return CoreApiUtils.error("content_required")
            val mkdirs = params.optBoolean("mkdirs", false)

            if (mkdirs) {
                val dir = remotePath.substringBeforeLast('/', "")
                if (dir.isNotBlank()) {
                    val mkdirCode = "import os\ntry:\n os.makedirs('$dir')\nexcept:\n pass\n"
                    try { runMicroPythonRawExec(lease.serial, mkdirCode) } catch (_: Exception) {}
                }
            }

            val chunkSize = params.optInt("chunk_size", 256).coerceIn(32, 4096)
            val chunks = content.toList().chunked(chunkSize).map { it.toByteArray() }
            for ((i, chunk) in chunks.withIndex()) {
                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                val mode = if (i == 0) "wb" else "ab"
                val pyCode = "import ubinascii\nf=open(${pythonSingleQuoted(remotePath)},'$mode')\nf.write(ubinascii.a2b_base64('$b64'))\nf.close()\nprint('ok')\n"
                runMicroPythonRawExec(lease.serial, pyCode)
            }
            CoreApiUtils.ok(
                "model" to lease.model,
                "serial_handle" to lease.serial.id,
                "remote_path" to remotePath,
                "bytes_written" to content.size,
                "chunks" to chunks.size,
            )
        } catch (ex: Exception) {
            CoreApiUtils.error("micropython_write_file_failed", 500, mapOf("detail" to (ex.message ?: "")))
        } finally {
            releaseMicroPythonSession(lease)
        }
    }

    fun micropythonSoftReset(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "MicroPython soft_reset")
        if (perm is PermissionResult.Pending) return perm.response

        val lease = acquireMicroPythonSession(params) ?: return CoreApiUtils.error("micropython_session_failed")
        return try {
            val drain = params.optBoolean("drain", true)
            val captureMaxLines = params.optInt("capture_max_lines", 100).coerceIn(1, 1000)
            val captureIdleMs = params.optInt("capture_idle_ms", 1000).coerceIn(100, 30_000)
            val captureTimeoutMs = params.optInt("capture_timeout_ms", 8000).coerceIn(500, 60_000)

            if (drain) serial.drainSerialInput(lease.serial, 50, 5)

            // Send Ctrl-C + Ctrl-D (soft reset)
            synchronized(lease.serial.lock) {
                serial.writeSerialAll(lease.serial, byteArrayOf(0x03, 0x03, 0x04), 2000)
            }
            Thread.sleep(300)

            val result = synchronized(lease.serial.lock) {
                serial.readSerialLines(lease.serial, captureMaxLines, captureIdleMs, captureTimeoutMs)
            }

            val bootComplete = result.lines.any { it.contains(">>>") || it.contains("MicroPython") }
            CoreApiUtils.ok(
                "model" to lease.model,
                "serial_handle" to lease.serial.id,
                "lines" to result.lines,
                "line_count" to result.lines.size,
                "bytes_read" to result.bytesRead,
                "truncated" to result.truncated,
                "truncation_reason" to result.truncationReason,
                "elapsed_ms" to result.elapsedMs,
                "boot_complete" to bootComplete,
            )
        } catch (ex: Exception) {
            CoreApiUtils.error("micropython_soft_reset_failed", 500, mapOf("detail" to (ex.message ?: "")))
        } finally {
            releaseMicroPythonSession(lease)
        }
    }

    // ---- Internal helpers -------------------------------------------------------

    private fun findSerialBulkPort(dev: UsbDevice): Map<String, Any?>? {
        var best: Map<String, Any?>? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
            }
            if (inEp == null || outEp == null) continue
            val score = when (intf.interfaceClass) {
                UsbConstants.USB_CLASS_CDC_DATA -> 100
                UsbConstants.USB_CLASS_VENDOR_SPEC -> 90
                UsbConstants.USB_CLASS_COMM -> 60
                else -> 30
            } + intf.endpointCount
            if (score > bestScore) {
                bestScore = score
                best = mapOf(
                    "interface_id" to intf.id,
                    "interface_class" to intf.interfaceClass,
                    "interface_subclass" to intf.interfaceSubclass,
                    "interface_protocol" to intf.interfaceProtocol,
                    "in_endpoint_address" to inEp.address,
                    "out_endpoint_address" to outEp.address,
                )
            }
        }
        return best
    }

    private fun pickEspSerialSelection(dev: UsbDevice, payload: JSONObject): EspSerialSelection? {
        val ifId = payload.optInt("interface_id", -1)
        val inAddr = payload.optInt("in_endpoint_address", -1)
        val outAddr = payload.optInt("out_endpoint_address", -1)
        if (ifId >= 0 && inAddr >= 0 && outAddr >= 0) {
            val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == ifId }
                ?: return null
            val inEp = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull { it.address == inAddr }
                ?: return null
            val outEp = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull { it.address == outAddr }
                ?: return null
            return EspSerialSelection(intf, inEp, outEp)
        }
        // Auto-detect
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
            }
            if (inEp != null && outEp != null) return EspSerialSelection(intf, inEp, outEp)
        }
        return null
    }

    private fun buildMcuFlashSegments(payload: JSONObject, fallbackPath: String): List<McuFlashSegment> {
        val segArr = payload.optJSONArray("segments")
        if (segArr != null && segArr.length() > 0) {
            val segments = mutableListOf<McuFlashSegment>()
            for (i in 0 until segArr.length()) {
                val s = segArr.getJSONObject(i)
                val path = s.optString("path", "").trim()
                if (path.isBlank()) throw IllegalArgumentException("segment_path_required")
                val offset = parseOffsetToInt(s.optString("offset", "0"))
                    ?: throw IllegalArgumentException("invalid_offset")
                val (bytes, displayPath) = fileResolver.readPathBytes(path)
                segments.add(McuFlashSegment(displayPath, offset, bytes))
            }
            return segments
        }
        // Fallback: single image
        if (fallbackPath.isBlank()) return emptyList()
        val offset = parseOffsetToInt(payload.optString("offset", "0")) ?: 0
        val (bytes, displayPath) = fileResolver.readPathBytes(fallbackPath)
        return listOf(McuFlashSegment(displayPath, offset, bytes))
    }

    private fun parseOffsetToInt(s: String): Int? {
        val t = s.trim()
        if (t.isBlank()) return null
        return try {
            if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toLong(16).toInt()
            else t.toLong().toInt()
        } catch (_: Exception) { null }
    }

    private fun acquireMicroPythonSession(params: Map<String, Any?>): MicroPythonSessionLease? {
        val model = params.optString("model", "esp32").trim().lowercase(Locale.US)
        val serialHandle = params.optString("serial_handle").trim()
        if (serialHandle.isNotBlank()) {
            val st = serial.serialSessions[serialHandle] ?: return null
            return MicroPythonSessionLease(st, ephemeral = false, model = model, handle = serialHandle)
        }
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return null
        val dev = usb.usbDevicesByHandle[handle] ?: return null
        if (!usb.usbConnections.containsKey(handle)) return null
        if (!runCatching { usb.usbManager.hasPermission(dev) }.getOrDefault(false)) return null
        val baudRate = params.optInt("baud_rate", 115200).coerceIn(300, 3_500_000)
        val serialConn = usb.usbManager.openDevice(dev) ?: return null
        return try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(dev) ?: run {
                runCatching { serialConn.close() }; return null
            }
            val port = driver.ports.firstOrNull() ?: run {
                runCatching { serialConn.close() }; return null
            }
            port.open(serialConn)
            port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            val dtr = if (params.has("dtr")) params.optBoolean("dtr") else null
            val rts = if (params.has("rts")) params.optBoolean("rts") else null
            if (dtr != null) runCatching { port.setDTR(dtr) }
            if (rts != null) runCatching { port.setRTS(rts) }
            val serialId = UUID.randomUUID().toString()
            val st = SerialSessionState(
                id = serialId, usbHandle = handle, deviceName = dev.deviceName,
                portIndex = 0, baudRate = baudRate, dataBits = UsbSerialPort.DATABITS_8,
                stopBits = UsbSerialPort.STOPBITS_1, parity = UsbSerialPort.PARITY_NONE,
                connection = serialConn, port = port, openedAtMs = System.currentTimeMillis(),
            )
            serial.serialSessions[serialId] = st
            MicroPythonSessionLease(st, ephemeral = true, model = model, handle = handle)
        } catch (ex: Exception) {
            runCatching { serialConn.close() }
            null
        }
    }

    private fun releaseMicroPythonSession(lease: MicroPythonSessionLease) {
        if (lease.ephemeral) {
            serial.serialSessions.remove(lease.serial.id)
            serial.closeSessionInternal(lease.serial)
        }
    }

    private fun extractMicroPythonCode(params: Map<String, Any?>): String? {
        val code = params.optString("code").trim()
        if (code.isNotBlank()) return code
        val codeB64 = params.optString("code_b64").trim()
        if (codeB64.isNotBlank()) {
            return try {
                String(Base64.decode(codeB64, Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun extractMicroPythonContent(params: Map<String, Any?>): ByteArray? {
        val b64 = params.optString("content_b64").trim()
        if (b64.isNotBlank()) {
            return try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null }
        }
        val text = params.optString("content")
        if (text.isNotBlank()) return text.toByteArray(Charsets.UTF_8)
        return null
    }

    private fun runMicroPythonRawExec(st: SerialSessionState, code: String): MicroPythonRawExecResult {
        if (code.length > 1_000_000) throw IllegalArgumentException("code_too_large")
        synchronized(st.lock) {
            serial.drainSerialInput(st, 50, 5)
            // Ctrl-C Ctrl-C SOH (enter raw REPL)
            serial.writeSerialAll(st, byteArrayOf(0x03, 0x03, 0x01), 2000)
            Thread.sleep(100)
            serial.drainSerialInput(st, 50, 5)
            // Write code + EOT
            serial.writeSerialAll(st, code.toByteArray(Charsets.UTF_8), 2000)
            serial.writeSerialAll(st, byteArrayOf(0x04), 2000)
            // Read until we find OK + two EOTs
            val buf = ByteArray(4096)
            val accum = java.io.ByteArrayOutputStream()
            val deadline = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < deadline) {
                val n = try { st.port.read(buf, 200) } catch (_: Exception) { 0 }
                if (n > 0) accum.write(buf, 0, n)
                if (hasMicroPythonRawDelimiters(accum.toByteArray())) break
            }
            // Exit raw REPL
            serial.writeSerialAll(st, byteArrayOf(0x02), 2000)
            return parseMicroPythonRawExec(accum.toByteArray())
        }
    }

    private fun hasMicroPythonRawDelimiters(data: ByteArray): Boolean {
        val okIdx = indexOfBytes(data, "OK".toByteArray(), 0) ?: return false
        val eot1 = indexOfByte(data, 0x04, okIdx + 2) ?: return false
        val eot2 = indexOfByte(data, 0x04, eot1 + 1) ?: return false
        return true
    }

    private fun parseMicroPythonRawExec(data: ByteArray): MicroPythonRawExecResult {
        val okIdx = indexOfBytes(data, "OK".toByteArray(), 0) ?: return MicroPythonRawExecResult(ByteArray(0), ByteArray(0), data)
        val body = data.copyOfRange(okIdx + 2, data.size)
        val eot1 = indexOfByte(body, 0x04, 0) ?: return MicroPythonRawExecResult(body, ByteArray(0), data)
        val stdout = body.copyOfRange(0, eot1)
        val afterEot1 = body.copyOfRange(eot1 + 1, body.size)
        val eot2 = indexOfByte(afterEot1, 0x04, 0) ?: return MicroPythonRawExecResult(stdout, afterEot1, data)
        val stderr = afterEot1.copyOfRange(0, eot2)
        return MicroPythonRawExecResult(stdout, stderr, data)
    }

    // ---- Byte-level utilities ---------------------------------------------------

    private fun indexOfByte(data: ByteArray, value: Byte, start: Int): Int? {
        for (i in start until data.size) if (data[i] == value) return i
        return null
    }

    private fun indexOfByte(data: ByteArray, value: Int, start: Int): Int? =
        indexOfByte(data, value.toByte(), start)

    private fun indexOfBytes(data: ByteArray, pattern: ByteArray, start: Int): Int? {
        if (pattern.isEmpty()) return start
        outer@ for (i in start..(data.size - pattern.size)) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return null
    }

    private fun pythonSingleQuoted(s: String): String {
        val sb = StringBuilder("'")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append("'")
        return sb.toString()
    }

    private fun bytesToAsciiPreview(data: ByteArray): String {
        val sb = StringBuilder(data.size)
        for (b in data) {
            val c = b.toInt() and 0xFF
            sb.append(if (c in 32..126 || c == 10 || c == 13 || c == 9) c.toChar() else '.')
        }
        return sb.toString()
    }

    private fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    private fun toJsonObject(params: Map<String, Any?>): JSONObject {
        return CoreApiUtils.toJsonResponse(params + mapOf("_dummy_" to null))
    }
}
