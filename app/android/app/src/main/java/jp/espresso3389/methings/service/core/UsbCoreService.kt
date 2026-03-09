package jp.espresso3389.methings.service.core

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Base64
import android.util.Log
import jp.espresso3389.methings.device.UsbPermissionWaiter
import jp.espresso3389.methings.service.UsbIsoBridge
import jp.espresso3389.methings.service.core.CoreApiUtils.has
import jp.espresso3389.methings.service.core.CoreApiUtils.optBoolean
import jp.espresso3389.methings.service.core.CoreApiUtils.optInt
import jp.espresso3389.methings.service.core.CoreApiUtils.optLong
import jp.espresso3389.methings.service.core.CoreApiUtils.optString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * USB device state and handlers extracted from LocalHttpServer.
 *
 * Owns [usbConnections] and [usbDevicesByHandle].
 * Streaming state (usbStreams) remains in LocalHttpServer for now
 * because it depends on NanoWSD WebSocket transport.
 */
class UsbCoreService(
    private val context: Context,
    private val permission: PermissionCoreService,
) {
    private companion object {
        const val TAG = "UsbCoreService"
    }

    val usbConnections = ConcurrentHashMap<String, UsbDeviceConnection>()
    val usbDevicesByHandle = ConcurrentHashMap<String, UsbDevice>()

    val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    // ---- Public API (Map in / Map out) ----------------------------------------

    fun list(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "USB list")
        if (perm is PermissionResult.Pending) return perm.response

        val devices = usbManager.deviceList.values.map { usbDeviceToMap(it) }
        return CoreApiUtils.ok("count" to devices.size, "devices" to devices)
    }

    fun status(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val perm = permission.ensurePermission(ctx, params, "device.usb", "usb", "USB status")
        if (perm is PermissionResult.Pending) return perm.response

        val list = usbManager.deviceList.values.toList()
        val devices = list.map { dev ->
            val m = usbDeviceToMap(dev).toMutableMap()
            val has = runCatching { usbManager.hasPermission(dev) }.getOrDefault(false)
            m["has_permission"] = has
            val snap = UsbPermissionWaiter.snapshot(dev.deviceName)
            if (snap != null) {
                m["permission_request"] = mapOf(
                    "requested_at_ms" to snap.requestedAtMs,
                    "age_ms" to (System.currentTimeMillis() - snap.requestedAtMs).coerceAtLeast(0L),
                    "responded" to snap.responded,
                    "granted" to snap.granted,
                    "completed_at_ms" to snap.completedAtMs,
                    "timed_out" to snap.timedOut,
                )
            }
            m.toMap()
        }

        val pending = UsbPermissionWaiter.pendingSnapshots().map { snap ->
            mapOf(
                "name" to snap.deviceName,
                "requested_at_ms" to snap.requestedAtMs,
                "age_ms" to (System.currentTimeMillis() - snap.requestedAtMs).coerceAtLeast(0L),
                "timed_out" to snap.timedOut,
            )
        }

        return CoreApiUtils.ok(
            "now_ms" to System.currentTimeMillis(),
            "count" to devices.size,
            "devices" to devices,
            "pending_permission_requests" to pending,
        )
    }

    fun open(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val name = params.optString("name").trim()
        val vid = params.optInt("vendor_id", -1)
        val pid = params.optInt("product_id", -1)
        val timeoutMs = params.optLong("permission_timeout_ms", 0L)

        val dev = findUsbDevice(name, vid, pid)
            ?: return CoreApiUtils.error("usb_device_not_found", 404)

        val perm = permission.ensurePermission(
            ctx, params, "device.usb", "usb",
            "USB access: vid=${dev.vendorId} pid=${dev.productId} name=${dev.deviceName}",
        )
        if (perm is PermissionResult.Pending) return perm.response

        if (!ensureUsbPermission(dev, timeoutMs)) {
            return CoreApiUtils.error("usb_permission_required", 403, mapOf(
                "name" to dev.deviceName,
                "vendor_id" to dev.vendorId,
                "product_id" to dev.productId,
                "hint" to ("Android USB permission is required. The system 'Allow access to USB device' dialog must be accepted. " +
                    "If no dialog appears, bring the app to foreground and retry (Android may auto-deny requests from background). " +
                    "If it still auto-denies with no dialog, Android may have saved a default 'deny' for this USB device: " +
                    "open the app settings and clear defaults, then replug the device and retry."),
            ))
        }

        val conn = usbManager.openDevice(dev)
            ?: return CoreApiUtils.error("usb_open_failed", 500)

        val handle = UUID.randomUUID().toString()
        usbConnections[handle] = conn
        usbDevicesByHandle[handle] = dev
        return CoreApiUtils.ok("handle" to handle, "device" to usbDeviceToMap(dev))
    }

    fun close(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val serialClosed = closeSerialSessionsForUsbHandle?.invoke(handle) ?: 0
        val conn = usbConnections.remove(handle)
        usbDevicesByHandle.remove(handle)
        runCatching { conn?.close() }
        return CoreApiUtils.ok("closed" to (conn != null), "serial_sessions_closed" to serialClosed)
    }

    fun controlTransfer(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)

        val requestType = params.optInt("request_type", -1)
        val request = params.optInt("request", -1)
        val value = params.optInt("value", 0)
        val index = params.optInt("index", 0)
        val timeout = params.optInt("timeout_ms", 2000).coerceIn(0, 60000)
        if (requestType < 0 || request < 0) return CoreApiUtils.error("request_type_and_request_required")

        val directionIn = (requestType and 0x80) != 0
        val b64 = params.optString("data_b64")
        val length = params.optInt("length", if (directionIn) 256 else 0).coerceIn(0, 16384)

        val buf: ByteArray? = if (directionIn) {
            ByteArray(length)
        } else {
            if (b64.isNotBlank()) Base64.decode(b64, Base64.DEFAULT) else ByteArray(0)
        }

        val transferred = conn.controlTransfer(
            requestType, request, value, index,
            buf, if (directionIn) length else (buf?.size ?: 0), timeout,
        )
        if (transferred < 0) return CoreApiUtils.error("control_transfer_failed", 500)

        val result = mutableMapOf<String, Any?>("status" to "ok", "transferred" to transferred)
        if (directionIn && buf != null) {
            result["data"] = buf.copyOfRange(0, transferred.coerceIn(0, buf.size)).asUByteArray()
        }
        return result
    }

    fun rawDescriptors(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val raw = conn.rawDescriptors ?: return CoreApiUtils.error("raw_descriptors_unavailable", 500)
        return CoreApiUtils.ok(
            "descriptors" to raw.asUByteArray(),
            "length" to raw.size,
        )
    }

    fun claimInterface(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val dev = usbDevicesByHandle[handle] ?: return CoreApiUtils.error("device_not_found", 404)
        val id = params.optInt("interface_id", -1)
        if (id < 0) return CoreApiUtils.error("interface_id_required")
        val force = params.optBoolean("force", true)
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == id }
            ?: return CoreApiUtils.error("interface_not_found", 404)
        val ok = conn.claimInterface(intf, force)
        return CoreApiUtils.ok("claimed" to ok, "interface_id" to id)
    }

    fun releaseInterface(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val dev = usbDevicesByHandle[handle] ?: return CoreApiUtils.error("device_not_found", 404)
        val id = params.optInt("interface_id", -1)
        if (id < 0) return CoreApiUtils.error("interface_id_required")
        val intf = (0 until dev.interfaceCount).map { dev.getInterface(it) }.firstOrNull { it.id == id }
            ?: return CoreApiUtils.error("interface_not_found", 404)
        runCatching { conn.releaseInterface(intf) }
        return CoreApiUtils.ok("interface_id" to id)
    }

    fun bulkTransfer(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val dev = usbDevicesByHandle[handle] ?: return CoreApiUtils.error("device_not_found", 404)
        val epAddr = params.optInt("endpoint_address", -1)
        if (epAddr < 0) return CoreApiUtils.error("endpoint_address_required")
        val timeout = params.optInt("timeout_ms", 2000).coerceIn(0, 60000)

        val ep = findEndpointByAddress(dev, epAddr)
            ?: return CoreApiUtils.error("endpoint_not_found", 404)

        val directionIn = (epAddr and 0x80) != 0
        if (directionIn) {
            val length = params.optInt("length", 512).coerceIn(0, 1024 * 1024)
            val buf = ByteArray(length)
            val n = conn.bulkTransfer(ep, buf, buf.size, timeout)
            if (n < 0) return CoreApiUtils.error("bulk_transfer_failed", 500)
            val slice = buf.copyOfRange(0, n.coerceIn(0, buf.size))
            return CoreApiUtils.ok(
                "transferred" to n,
                "data" to slice.asUByteArray(),
            )
        } else {
            val b64 = params.optString("data_b64")
            val data = if (b64.isNotBlank()) Base64.decode(b64, Base64.DEFAULT) else ByteArray(0)
            val n = conn.bulkTransfer(ep, data, data.size, timeout)
            if (n < 0) return CoreApiUtils.error("bulk_transfer_failed", 500)
            return CoreApiUtils.ok("transferred" to n)
        }
    }

    fun isoTransfer(ctx: ApiContext, params: Map<String, Any?>): Map<String, Any?> {
        val handle = params.optString("handle").trim()
        if (handle.isBlank()) return CoreApiUtils.error("handle_required")
        val conn = usbConnections[handle] ?: return CoreApiUtils.error("handle_not_found", 404)
        val dev = usbDevicesByHandle[handle] ?: return CoreApiUtils.error("device_not_found", 404)

        val epAddr = params.optInt("endpoint_address", -1)
        if (epAddr < 0) return CoreApiUtils.error("endpoint_address_required")

        val interfaceId = params.optInt("interface_id", -1)
        val altSetting = if (params.has("alt_setting")) params.optInt("alt_setting", -1) else null
        val packetSize = params.optInt("packet_size", 1024).coerceIn(1, 1024 * 1024)
        val numPackets = params.optInt("num_packets", 32).coerceIn(1, 1024)
        val timeout = params.optInt("timeout_ms", 800).coerceIn(1, 60000)

        val candidates = (0 until dev.interfaceCount).map { dev.getInterface(it) }
        val chosen = candidates.firstOrNull { intf ->
            if (interfaceId >= 0 && intf.id != interfaceId) return@firstOrNull false
            if (altSetting != null && altSetting >= 0 && intf.alternateSetting != altSetting) return@firstOrNull false
            for (e in 0 until intf.endpointCount) {
                if (intf.getEndpoint(e).address == epAddr) return@firstOrNull true
            }
            false
        } ?: return CoreApiUtils.error("interface_or_endpoint_not_found", 404)

        val isoEp = (0 until chosen.endpointCount).map { chosen.getEndpoint(it) }
            .firstOrNull { it.address == epAddr }
            ?: return CoreApiUtils.error("endpoint_not_found", 404)
        if (isoEp.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) {
            return CoreApiUtils.error("endpoint_not_isochronous", 400, mapOf(
                "endpoint_type" to isoEp.type, "expected" to UsbConstants.USB_ENDPOINT_XFER_ISOC,
            ))
        }

        val force = params.optBoolean("force", true)
        val claimed = conn.claimInterface(chosen, force)
        if (!claimed) return CoreApiUtils.error("claim_interface_failed", 500)
        runCatching { conn.setInterface(chosen) }

        UsbIsoBridge.ensureLoaded()
        val fd = conn.fileDescriptor
        if (fd < 0) return CoreApiUtils.error("file_descriptor_unavailable", 500)

        val blob: ByteArray = try {
            UsbIsoBridge.isochIn(fd, epAddr, packetSize, numPackets, timeout)
                ?: return CoreApiUtils.error("iso_transfer_failed", 500)
        } catch (ex: Exception) {
            Log.e(TAG, "isochIn failed", ex)
            return CoreApiUtils.error("iso_transfer_exception", 500)
        }

        if (blob.size < 12) return CoreApiUtils.error("iso_blob_too_small", 500)
        fun u32le(off: Int): Long {
            return (blob[off].toLong() and 0xFF) or
                ((blob[off + 1].toLong() and 0xFF) shl 8) or
                ((blob[off + 2].toLong() and 0xFF) shl 16) or
                ((blob[off + 3].toLong() and 0xFF) shl 24)
        }
        fun i32le(off: Int): Int = u32le(off).toInt()

        val magic = u32le(0).toInt()
        if (magic != 0x4F53494B) return CoreApiUtils.error("iso_bad_magic", 500)
        val nPk = u32le(4).toInt().coerceIn(0, 1024)
        val payloadLen = u32le(8).toInt().coerceIn(0, 32 * 1024 * 1024)
        val metaLen = 12 + nPk * 8
        if (blob.size < metaLen) return CoreApiUtils.error("iso_blob_meta_truncated", 500)
        val expectedTotal = metaLen + payloadLen
        if (blob.size < expectedTotal) return CoreApiUtils.error("iso_blob_payload_truncated", 500)

        val packets = mutableListOf<Map<String, Any?>>()
        var metaOff = 12
        for (i in 0 until nPk) {
            packets.add(mapOf("status" to i32le(metaOff), "actual_length" to i32le(metaOff + 4)))
            metaOff += 8
        }
        val payloadBytes = blob.copyOfRange(metaLen, metaLen + payloadLen)
        return CoreApiUtils.ok(
            "handle" to handle,
            "interface_id" to chosen.id,
            "alt_setting" to chosen.alternateSetting,
            "endpoint_address" to epAddr,
            "packet_size" to packetSize,
            "num_packets" to nPk,
            "payload_length" to payloadLen,
            "packets" to packets,
            "data" to payloadBytes.asUByteArray(),
        )
    }

    // ---- Helpers (public for MCU/Serial use) -----------------------------------

    fun findUsbDevice(name: String, vendorId: Int, productId: Int): UsbDevice? {
        val list = usbManager.deviceList.values
        if (name.isNotBlank()) {
            findUsbDeviceByName(list, name)?.let { return it }
        }
        if (vendorId >= 0 && productId >= 0) return list.firstOrNull { it.vendorId == vendorId && it.productId == productId }
        if (vendorId >= 0) return list.firstOrNull { it.vendorId == vendorId }
        if (productId >= 0) return list.firstOrNull { it.productId == productId }
        return null
    }

    private fun findUsbDeviceByName(devices: Collection<UsbDevice>, rawName: String): UsbDevice? {
        val exact = rawName.trim()
        if (exact.isBlank()) return null
        devices.firstOrNull { it.deviceName == exact }?.let { return it }

        val normalized = normalizeUsbLookup(exact)
        val byMetadata = devices.firstOrNull { dev ->
            sequenceOf(dev.productName, dev.manufacturerName, usbDisplayName(dev))
                .filterNotNull()
                .map(::normalizeUsbLookup)
                .any { it == normalized }
        }
        if (byMetadata != null) return byMetadata

        return devices.firstOrNull { dev ->
            sequenceOf(dev.productName, dev.manufacturerName, usbDisplayName(dev))
                .filterNotNull()
                .map(::normalizeUsbLookup)
                .any { candidate ->
                    candidate.contains(normalized) || normalized.contains(candidate)
                }
        }
    }

    private fun usbDisplayName(dev: UsbDevice): String {
        val manufacturer = dev.manufacturerName?.trim().orEmpty()
        val product = dev.productName?.trim().orEmpty()
        return listOf(manufacturer, product).filter { it.isNotBlank() }.joinToString(" ").trim()
    }

    private fun normalizeUsbLookup(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    fun ensureUsbPermission(device: UsbDevice, timeoutMs: Long): Boolean {
        if (usbManager.hasPermission(device)) return true
        val name = device.deviceName
        UsbPermissionWaiter.begin(name)
        try {
            val intent = Intent(context, jp.espresso3389.methings.ui.UsbPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(jp.espresso3389.methings.ui.UsbPermissionActivity.EXTRA_DEVICE_NAME, name)
            }
            Log.i(TAG, "Requesting USB permission (activity): name=$name vid=${device.vendorId} pid=${device.productId}")
            context.startActivity(intent)
        } catch (ex: Exception) {
            Log.w(TAG, "USB permission activity launch failed", ex)
        }
        val granted = UsbPermissionWaiter.await(name, timeoutMs)
        UsbPermissionWaiter.clear(name)
        val has = runCatching { usbManager.hasPermission(device) }.getOrDefault(false)
        Log.i(TAG, "USB permission result: name=$name granted=$granted hasPermission=$has")
        return granted && has
    }

    fun guessUsbSerialBridge(dev: UsbDevice): String? {
        return when (dev.vendorId) {
            0x10C4 -> "cp210x"
            0x1A86 -> "ch34x"
            0x0403 -> "ftdi"
            0x303A -> "esp-usb-serial-jtag"
            else -> null
        }
    }

    fun usbDeviceToMap(dev: UsbDevice): Map<String, Any?> {
        val interfaces = (0 until dev.interfaceCount).map { i ->
            val intf = dev.getInterface(i)
            val endpoints = (0 until intf.endpointCount).map { e ->
                val ep = intf.getEndpoint(e)
                mapOf(
                    "address" to ep.address,
                    "attributes" to ep.attributes,
                    "direction" to ep.direction,
                    "max_packet_size" to ep.maxPacketSize,
                    "number" to ep.endpointNumber,
                    "interval" to ep.interval,
                    "type" to ep.type,
                )
            }
            mapOf(
                "id" to intf.id,
                "interface_class" to intf.interfaceClass,
                "interface_subclass" to intf.interfaceSubclass,
                "interface_protocol" to intf.interfaceProtocol,
                "endpoint_count" to intf.endpointCount,
                "endpoints" to endpoints,
            )
        }
        val m = mutableMapOf<String, Any?>(
            "name" to dev.deviceName,
            "vendor_id" to dev.vendorId,
            "product_id" to dev.productId,
            "device_class" to dev.deviceClass,
            "device_subclass" to dev.deviceSubclass,
            "device_protocol" to dev.deviceProtocol,
            "interface_count" to dev.interfaceCount,
            "interfaces" to interfaces,
        )
        runCatching { m["manufacturer_name"] = dev.manufacturerName ?: "" }
        runCatching { m["product_name"] = dev.productName ?: "" }
        runCatching { m["serial_number"] = dev.serialNumber ?: "" }
        return m
    }

    /** Callback for closing serial sessions when a USB handle is closed.
     *  Set by SerialCoreService during wiring. */
    var closeSerialSessionsForUsbHandle: ((String) -> Int)? = null

    // ---- Internal helpers -------------------------------------------------------

    private fun findEndpointByAddress(dev: UsbDevice, epAddr: Int): UsbEndpoint? {
        for (i in 0 until dev.interfaceCount) {
            val intf = dev.getInterface(i)
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.address == epAddr) return ep
            }
        }
        return null
    }
}
