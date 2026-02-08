package jp.espresso3389.methings.device

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Base64
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class BleManager(private val context: Context) {
    private val wsClients = CopyOnWriteArrayList<NanoWSD.WebSocket>()
    private val scanning = AtomicBoolean(false)
    private var scanCb: ScanCallback? = null

    private val gatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val pendingServices = ConcurrentHashMap<String, List<BluetoothGattService>>()

    private fun adapter(): BluetoothAdapter? {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    fun addWsClient(ws: NanoWSD.WebSocket) {
        wsClients.add(ws)
    }

    fun removeWsClient(ws: NanoWSD.WebSocket) {
        wsClients.remove(ws)
    }

    fun status(): Map<String, Any> {
        val ad = adapter()
        return mapOf(
            "status" to "ok",
            "available" to (ad != null),
            "enabled" to (ad?.isEnabled == true),
            "scanning" to scanning.get(),
            "connections" to gatts.keys.toList(),
        )
    }

    fun scanStart(lowLatency: Boolean = true): Map<String, Any> {
        val ad = adapter() ?: return mapOf("status" to "error", "error" to "bluetooth_unavailable")
        if (!ad.isEnabled) return mapOf("status" to "error", "error" to "bluetooth_disabled")
        if (scanning.get()) return mapOf("status" to "ok", "scanning" to true)

        val scanner = ad.bluetoothLeScanner ?: return mapOf("status" to "error", "error" to "scanner_unavailable")
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) emitScanResult(r)
            }

            override fun onScanFailed(errorCode: Int) {
                scanning.set(false)
                emit("scan_failed", JSONObject().put("code", errorCode))
            }
        }
        scanCb = cb
        scanning.set(true)
        emit("scan_started", JSONObject())
        scanner.startScan(null, settings, cb)
        return mapOf("status" to "ok", "scanning" to true)
    }

    fun scanStop(): Map<String, Any> {
        val ad = adapter() ?: return mapOf("status" to "ok", "scanning" to false)
        val scanner = ad.bluetoothLeScanner
        val cb = scanCb
        if (scanner != null && cb != null) {
            runCatching { scanner.stopScan(cb) }
        }
        scanCb = null
        scanning.set(false)
        emit("scan_stopped", JSONObject())
        return mapOf("status" to "ok", "scanning" to false)
    }

    fun connect(address: String, autoConnect: Boolean = false): Map<String, Any> {
        val ad = adapter() ?: return mapOf("status" to "error", "error" to "bluetooth_unavailable")
        if (!ad.isEnabled) return mapOf("status" to "error", "error" to "bluetooth_disabled")
        val addr = address.trim()
        if (addr.isBlank()) return mapOf("status" to "error", "error" to "address_required")
        if (gatts.containsKey(addr)) return mapOf("status" to "ok", "address" to addr, "connected" to true)

        val dev = runCatching { ad.getRemoteDevice(addr) }.getOrNull()
            ?: return mapOf("status" to "error", "error" to "invalid_address")

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val a = gatt.device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatts[a] = gatt
                    emit("connected", JSONObject().put("address", a).put("status", status))
                    runCatching { gatt.discoverServices() }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatts.remove(a)
                    pendingServices.remove(a)
                    emit("disconnected", JSONObject().put("address", a).put("status", status))
                    runCatching { gatt.close() }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val a = gatt.device.address
                pendingServices[a] = gatt.services ?: emptyList()
                emit("services", JSONObject().put("address", a).put("status", status).put("services", servicesJson(gatt.services)))
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                emit("char_read", JSONObject()
                    .put("address", gatt.device.address)
                    .put("status", status)
                    .put("service_uuid", characteristic.service.uuid.toString())
                    .put("char_uuid", characteristic.uuid.toString())
                    .put("value_b64", Base64.encodeToString(characteristic.value ?: ByteArray(0), Base64.NO_WRAP))
                )
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                emit("char_write", JSONObject()
                    .put("address", gatt.device.address)
                    .put("status", status)
                    .put("service_uuid", characteristic.service.uuid.toString())
                    .put("char_uuid", characteristic.uuid.toString())
                )
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                emit("char_notify", JSONObject()
                    .put("address", gatt.device.address)
                    .put("service_uuid", characteristic.service.uuid.toString())
                    .put("char_uuid", characteristic.uuid.toString())
                    .put("value_b64", Base64.encodeToString(characteristic.value ?: ByteArray(0), Base64.NO_WRAP))
                )
            }
        }

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.connectGatt(context, autoConnect, cb, BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(context, autoConnect, cb)
        }
        gatts[addr] = gatt
        emit("connect_start", JSONObject().put("address", addr))
        return mapOf("status" to "ok", "address" to addr)
    }

    fun disconnect(address: String): Map<String, Any> {
        val addr = address.trim()
        val g = gatts[addr] ?: return mapOf("status" to "ok", "disconnected" to false)
        runCatching { g.disconnect() }
        return mapOf("status" to "ok", "disconnected" to true)
    }

    fun services(address: String): Map<String, Any> {
        val addr = address.trim()
        val g = gatts[addr] ?: return mapOf("status" to "error", "error" to "not_connected")
        return mapOf("status" to "ok", "address" to addr, "services" to servicesJson(g.services).toString())
    }

    fun read(address: String, serviceUuid: String, charUuid: String): Map<String, Any> {
        val ch = findChar(address, serviceUuid, charUuid) ?: return mapOf("status" to "error", "error" to "char_not_found")
        val g = gatts[address.trim()] ?: return mapOf("status" to "error", "error" to "not_connected")
        val ok = runCatching { g.readCharacteristic(ch) }.getOrDefault(false)
        return mapOf("status" to "ok", "requested" to ok)
    }

    fun write(address: String, serviceUuid: String, charUuid: String, value: ByteArray, withResponse: Boolean = true): Map<String, Any> {
        val ch = findChar(address, serviceUuid, charUuid) ?: return mapOf("status" to "error", "error" to "char_not_found")
        val g = gatts[address.trim()] ?: return mapOf("status" to "error", "error" to "not_connected")

        val ok = if (Build.VERSION.SDK_INT >= 33) {
            val writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            runCatching { g.writeCharacteristic(ch, value, writeType) }.getOrDefault(-1) == BluetoothGatt.GATT_SUCCESS
        } else {
            ch.writeType = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ch.value = value
            runCatching { g.writeCharacteristic(ch) }.getOrDefault(false)
        }
        return mapOf("status" to "ok", "requested" to ok)
    }

    fun notifyStart(address: String, serviceUuid: String, charUuid: String): Map<String, Any> {
        val ch = findChar(address, serviceUuid, charUuid) ?: return mapOf("status" to "error", "error" to "char_not_found")
        val g = gatts[address.trim()] ?: return mapOf("status" to "error", "error" to "not_connected")
        val ok1 = runCatching { g.setCharacteristicNotification(ch, true) }.getOrDefault(false)

        val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        val ok2 = if (cccd != null) {
            val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching { g.writeDescriptor(cccd, v) }.getOrDefault(-1) == BluetoothGatt.GATT_SUCCESS
            } else {
                cccd.value = v
                runCatching { g.writeDescriptor(cccd) }.getOrDefault(false)
            }
        } else {
            true
        }
        return mapOf("status" to "ok", "requested" to (ok1 && ok2))
    }

    fun notifyStop(address: String, serviceUuid: String, charUuid: String): Map<String, Any> {
        val ch = findChar(address, serviceUuid, charUuid) ?: return mapOf("status" to "error", "error" to "char_not_found")
        val g = gatts[address.trim()] ?: return mapOf("status" to "error", "error" to "not_connected")
        val ok1 = runCatching { g.setCharacteristicNotification(ch, false) }.getOrDefault(false)

        val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        val ok2 = if (cccd != null) {
            val v = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) {
                runCatching { g.writeDescriptor(cccd, v) }.getOrDefault(-1) == BluetoothGatt.GATT_SUCCESS
            } else {
                cccd.value = v
                runCatching { g.writeDescriptor(cccd) }.getOrDefault(false)
            }
        } else {
            true
        }
        return mapOf("status" to "ok", "requested" to (ok1 && ok2))
    }

    private fun findChar(address: String, serviceUuid: String, charUuid: String): BluetoothGattCharacteristic? {
        val addr = address.trim()
        val g = gatts[addr] ?: return null
        val s = runCatching { UUID.fromString(serviceUuid.trim()) }.getOrNull() ?: return null
        val c = runCatching { UUID.fromString(charUuid.trim()) }.getOrNull() ?: return null
        val svc = g.getService(s) ?: return null
        return svc.getCharacteristic(c)
    }

    private fun servicesJson(services: List<BluetoothGattService>?): JSONArray {
        val arr = JSONArray()
        for (s in services ?: emptyList()) {
            val sj = JSONObject()
                .put("uuid", s.uuid.toString())
                .put("type", s.type)
            val chs = JSONArray()
            for (c in s.characteristics ?: emptyList()) {
                val cj = JSONObject()
                    .put("uuid", c.uuid.toString())
                    .put("properties", c.properties)
                    .put("permissions", c.permissions)
                chs.put(cj)
            }
            sj.put("characteristics", chs)
            arr.put(sj)
        }
        return arr
    }

    private fun emitScanResult(result: ScanResult) {
        val dev = result.device
        val uuids = JSONArray()
        val serviceUuids = result.scanRecord?.serviceUuids
        if (serviceUuids != null) {
            for (u in serviceUuids) uuids.put(u.uuid.toString())
        }
        emit(
            "scan_result",
            JSONObject()
                .put("address", dev.address ?: "")
                .put("name", dev.name ?: result.scanRecord?.deviceName ?: "")
                .put("rssi", result.rssi)
                .put("uuids", uuids)
        )
    }

    private fun emit(kind: String, data: JSONObject) {
        val msg = JSONObject()
            .put("type", "ble")
            .put("event", kind)
            .put("ts_ms", System.currentTimeMillis())
        for (k in data.keys()) {
            msg.put(k, data.get(k))
        }
        val dead = ArrayList<NanoWSD.WebSocket>()
        val text = msg.toString()
        for (ws in wsClients) {
            try {
                if (ws.isOpen) ws.send(text) else dead.add(ws)
            } catch (_: Exception) {
                dead.add(ws)
            }
        }
        for (ws in dead) wsClients.remove(ws)
    }
}

