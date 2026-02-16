package jp.espresso3389.methings.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MeMeDiscoveryManager(
    private val context: Context,
    private val servicePort: Int,
    private val logger: (String, Throwable?) -> Unit,
    private val onBlePayload: (payload: ByteArray, sourceAddress: String) -> Unit = { _, _ -> }
) {
    data class Config(
        val deviceId: String,
        val deviceName: String,
        val deviceDescription: String,
        val deviceIcon: String,
        val allowDiscovery: Boolean,
        val connectionMethods: List<String>
    )

    data class ScanSummary(
        val startedAt: Long,
        val timeoutMs: Long,
        val discovered: List<JSONObject>,
        val warnings: List<String>
    )

    private data class Peer(
        val deviceId: String,
        var deviceName: String,
        var deviceDescription: String,
        var deviceIcon: String,
        var lastSeenAt: Long,
        var wifiHost: String? = null,
        var wifiPort: Int? = null,
        var bleAddress: String? = null,
        var bleRssi: Int? = null
    ) {
        val methods = linkedSetOf<String>()

        fun toJson(): JSONObject {
            val out = JSONObject()
                .put("device_id", deviceId)
                .put("device_name", deviceName)
                .put("device_description", deviceDescription)
                .put("device_icon", deviceIcon)
                .put("methods", JSONArray(methods.toList()))
                .put("last_seen_at", lastSeenAt)
            if (wifiHost != null && wifiPort != null) {
                out.put("wifi", JSONObject().put("host", wifiHost).put("port", wifiPort))
            }
            if (bleAddress != null) {
                val ble = JSONObject().put("address", bleAddress)
                if (bleRssi != null) ble.put("rssi", bleRssi)
                out.put("ble", ble)
            }
            return out
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val peers = ConcurrentHashMap<String, Peer>()
    private val bleSendExecutor = Executors.newSingleThreadExecutor()
    private val bleRxBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val bleConnectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val bleNotifyEnabledAddresses = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var bleTxCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile private var registeredService: NsdServiceInfo? = null
    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
    @Volatile private var bleAdvertiser: BluetoothLeAdvertiser? = null
    @Volatile private var bleAdvertiseCallback: AdvertiseCallback? = null
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var lastScanAt: Long = 0L

    fun applyConfig(config: Config) {
        if (!config.allowDiscovery) {
            stopWifiAdvertise()
            stopBleAdvertise()
            return
        }
        if (config.connectionMethods.contains("wifi")) {
            startWifiAdvertise(config)
        } else {
            stopWifiAdvertise()
        }
        if (config.connectionMethods.contains("ble")) {
            startBleAdvertise(config)
            startBleDataServer()
        } else {
            stopBleAdvertise()
            stopBleDataServer()
        }
    }

    fun shutdown() {
        stopWifiAdvertise()
        stopBleAdvertise()
        stopBleDataServer()
        bleSendExecutor.shutdownNow()
    }

    fun scan(config: Config, timeoutMs: Long): ScanSummary {
        val warnings = mutableListOf<String>()
        val latchParts = mutableListOf<() -> Unit>()
        val startedAt = System.currentTimeMillis()
        lastScanAt = startedAt

        if (config.connectionMethods.contains("wifi")) {
            latchParts += { scanWifi(timeoutMs, warnings, config) }
        }
        if (config.connectionMethods.contains("ble")) {
            latchParts += { scanBle(timeoutMs, warnings, config) }
        }
        latchParts.forEach { it() }
        purgeStalePeers()
        val discovered = peers.values
            .filter { it.deviceId != config.deviceId }
            .sortedByDescending { it.lastSeenAt }
            .map { it.toJson() }
        return ScanSummary(
            startedAt = startedAt,
            timeoutMs = timeoutMs,
            discovered = discovered,
            warnings = warnings
        )
    }

    fun statusJson(config: Config): JSONObject {
        purgeStalePeers()
        val discovered = peers.values
            .filter { it.deviceId != config.deviceId }
            .sortedByDescending { it.lastSeenAt }
            .map { it.toJson() }
        return JSONObject()
            .put("discovered_count", discovered.size)
            .put("discovered", JSONArray(discovered))
            .put("last_scan_at", if (lastScanAt > 0L) lastScanAt else JSONObject.NULL)
            .put("advertising", JSONObject()
                .put("wifi", registeredService != null)
                .put("ble", bleAdvertiseCallback != null)
            )
    }

    private fun purgeStalePeers(nowMs: Long = System.currentTimeMillis()) {
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if ((nowMs - e.value.lastSeenAt) > PEER_STALE_MS) {
                it.remove()
            }
        }
    }

    private fun scanWifi(timeoutMs: Long, warnings: MutableList<String>, config: Config) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            warnings += "wifi_nsd_unavailable"
            return
        }
        val latch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                warnings += "wifi_discovery_start_failed:$errorCode"
                runCatching { nsd.stopServiceDiscovery(this) }
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                warnings += "wifi_discovery_stop_failed:$errorCode"
                runCatching { nsd.stopServiceDiscovery(this) }
                latch.countDown()
            }

            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {
                latch.countDown()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != NSD_SERVICE_TYPE) return
                resolveNsdService(nsd, serviceInfo, config, warnings)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        runCatching {
            nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            warnings += "wifi_discovery_failed"
            logger("me.me wifi discovery start failed", it)
            return
        }
        handler.postDelayed({
            runCatching { nsd.stopServiceDiscovery(listener) }.onFailure {
                warnings += "wifi_discovery_stop_failed"
            }
        }, timeoutMs)
        latch.await(timeoutMs + 1500L, TimeUnit.MILLISECONDS)
    }

    private fun resolveNsdService(
        nsd: NsdManager,
        serviceInfo: NsdServiceInfo,
        config: Config,
        warnings: MutableList<String>
    ) {
        runCatching {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    warnings += "wifi_resolve_failed:$errorCode"
                }

                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val attrs = resolved.attributes
                    val did = attrs["id"]?.toString(StandardCharsets.UTF_8).orEmpty().trim()
                    if (did.isBlank() || did == config.deviceId) return
                    val peer = peers.computeIfAbsent(did) {
                        Peer(
                            deviceId = did,
                            deviceName = attrs["name"]?.toString(StandardCharsets.UTF_8).orEmpty().ifBlank { resolved.serviceName ?: did },
                            deviceDescription = attrs["desc"]?.toString(StandardCharsets.UTF_8).orEmpty(),
                            deviceIcon = attrs["icon"]?.toString(StandardCharsets.UTF_8).orEmpty(),
                            lastSeenAt = System.currentTimeMillis()
                        )
                    }
                    peer.deviceName = attrs["name"]?.toString(StandardCharsets.UTF_8).orEmpty().ifBlank { peer.deviceName }
                    peer.deviceDescription = attrs["desc"]?.toString(StandardCharsets.UTF_8).orEmpty().ifBlank { peer.deviceDescription }
                    peer.deviceIcon = attrs["icon"]?.toString(StandardCharsets.UTF_8).orEmpty().ifBlank { peer.deviceIcon }
                    peer.lastSeenAt = System.currentTimeMillis()
                    peer.wifiHost = resolved.host?.hostAddress
                    peer.wifiPort = resolved.port
                    peer.methods.add("wifi")
                }
            })
        }.onFailure {
            warnings += "wifi_resolve_failed"
            logger("me.me wifi resolve exception", it)
        }
    }

    private fun scanBle(timeoutMs: Long, warnings: MutableList<String>, config: Config) {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        ) {
            warnings += "ble_scan_permission_missing"
            return
        }
        val adapter = bluetoothAdapter() ?: run {
            warnings += "ble_unavailable"
            return
        }
        if (!adapter.isEnabled) {
            warnings += "ble_disabled"
            return
        }
        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            warnings += "ble_scanner_unavailable"
            return
        }
        val latch = CountDownLatch(1)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_SERVICE_UUID)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                ingestBleResult(result, config)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) ingestBleResult(result, config)
            }

            override fun onScanFailed(errorCode: Int) {
                warnings += "ble_scan_failed:$errorCode"
                latch.countDown()
            }
        }
        runCatching {
            scanner.startScan(listOf(filter), settings, cb)
        }.onFailure {
            warnings += "ble_scan_failed"
            logger("me.me BLE scan start failed", it)
            return
        }
        handler.postDelayed({
            runCatching { scanner.stopScan(cb) }
            latch.countDown()
        }, timeoutMs)
        latch.await(timeoutMs + 1500L, TimeUnit.MILLISECONDS)
    }

    private fun ingestBleResult(result: ScanResult, config: Config) {
        val data = result.scanRecord?.getServiceData(ParcelUuid(BLE_SERVICE_UUID)) ?: return
        val payload = parseBleServiceData(data)
        val did = payload["id"].orEmpty().trim()
        if (did.isBlank() || did == config.deviceId) return
        val peer = peers.computeIfAbsent(did) {
            Peer(
                deviceId = did,
                deviceName = payload["name"].orEmpty().ifBlank { did },
                deviceDescription = payload["desc"].orEmpty(),
                deviceIcon = payload["icon"].orEmpty(),
                lastSeenAt = System.currentTimeMillis()
            )
        }
        peer.deviceName = payload["name"].orEmpty().ifBlank { peer.deviceName }
        peer.deviceDescription = payload["desc"].orEmpty().ifBlank { peer.deviceDescription }
        peer.deviceIcon = payload["icon"].orEmpty().ifBlank { peer.deviceIcon }
        peer.lastSeenAt = System.currentTimeMillis()
        peer.bleAddress = result.device?.address
        peer.bleRssi = result.rssi
        peer.methods.add("ble")
    }

    private fun startWifiAdvertise(config: Config) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        if (registrationListener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "${NSD_NAME_PREFIX}${config.deviceId.takeLast(8)}"
            serviceType = NSD_SERVICE_TYPE
            port = servicePort
            setAttribute("id", config.deviceId)
            setAttribute("name", config.deviceName.take(64))
            if (config.deviceDescription.isNotBlank()) setAttribute("desc", config.deviceDescription.take(80))
            if (config.deviceIcon.isNotBlank()) setAttribute("icon", config.deviceIcon.take(120))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                registeredService = serviceInfo
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                logger("me.me NSD registration failed: $errorCode", null)
                registeredService = null
                registrationListener = null
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                registeredService = null
                registrationListener = null
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                logger("me.me NSD unregistration failed: $errorCode", null)
            }
        }
        registrationListener = listener
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure {
            logger("me.me NSD register exception", it)
            registrationListener = null
            registeredService = null
        }
    }

    private fun stopWifiAdvertise() {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val listener = registrationListener ?: return
        runCatching { nsd.unregisterService(listener) }
        registrationListener = null
        registeredService = null
    }

    private fun startBleAdvertise(config: Config) {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val adapter = bluetoothAdapter() ?: return
        if (!adapter.isEnabled || !adapter.isMultipleAdvertisementSupported) return
        if (bleAdvertiseCallback != null) return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val payload = buildBleServiceData(config)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BLE_SERVICE_UUID))
            .addServiceData(ParcelUuid(BLE_SERVICE_UUID), payload)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                logger("me.me BLE advertise failed: $errorCode", null)
                bleAdvertiseCallback = null
                bleAdvertiser = null
            }
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {}
        }
        bleAdvertiseCallback = cb
        bleAdvertiser = advertiser
        runCatching { advertiser.startAdvertising(settings, data, cb) }.onFailure {
            logger("me.me BLE advertise exception", it)
            bleAdvertiseCallback = null
            bleAdvertiser = null
        }
    }

    private fun stopBleAdvertise() {
        val advertiser = bleAdvertiser ?: return
        val cb = bleAdvertiseCallback ?: return
        runCatching { advertiser.stopAdvertising(cb) }
        bleAdvertiser = null
        bleAdvertiseCallback = null
    }

    fun sendBlePayload(targetDeviceId: String, payload: ByteArray, timeoutMs: Long = 12_000L): JSONObject {
        val future = bleSendExecutor.submit<JSONObject> {
            sendBlePayloadInternal(targetDeviceId, payload, timeoutMs.coerceIn(2000L, 30_000L))
        }
        return runCatching { future.get(timeoutMs.coerceIn(2000L, 30_000L) + 1000L, TimeUnit.MILLISECONDS) }
            .getOrElse { JSONObject().put("ok", false).put("error", "ble_send_timeout") }
    }

    fun sendBlePayloadToAddress(targetAddress: String, payload: ByteArray, timeoutMs: Long = 12_000L): JSONObject {
        val future = bleSendExecutor.submit<JSONObject> {
            sendBlePayloadInternalByAddress(targetAddress.trim(), payload, timeoutMs.coerceIn(2000L, 30_000L))
        }
        return runCatching { future.get(timeoutMs.coerceIn(2000L, 30_000L) + 1000L, TimeUnit.MILLISECONDS) }
            .getOrElse { JSONObject().put("ok", false).put("error", "ble_send_timeout") }
    }

    fun sendBleNotificationToAddress(targetAddress: String, payload: ByteArray, timeoutMs: Long = 2500L): JSONObject {
        val address = targetAddress.trim()
        if (address.isBlank()) return JSONObject().put("ok", false).put("error", "peer_ble_address_missing")
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return JSONObject().put("ok", false).put("error", "ble_connect_permission_missing")
        }
        val server = gattServer ?: return JSONObject().put("ok", false).put("error", "ble_gatt_server_unavailable")
        val device = bleConnectedDevices[address]
            ?: return JSONObject().put("ok", false).put("error", "ble_peer_not_connected")
        if (!bleNotifyEnabledAddresses.contains(address)) {
            return JSONObject().put("ok", false).put("error", "ble_peer_notify_not_enabled")
        }
        val tx = bleTxCharacteristic ?: return JSONObject().put("ok", false).put("error", "ble_tx_characteristic_missing")
        val chunks = buildBleChunks(payload)
        var sent = 0
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1200L, 10_000L)
        for (chunk in chunks) {
            if (System.currentTimeMillis() > deadline) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "ble_notify_timeout")
                    .put("chunks_sent", sent)
                    .put("chunks", chunks.size)
            }
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                server.notifyCharacteristicChanged(device, tx, false, chunk) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    tx.value = chunk
                    server.notifyCharacteristicChanged(device, tx, false)
                }
            }
            if (!ok) {
                return JSONObject()
                    .put("ok", false)
                    .put("error", "ble_notify_send_failed")
                    .put("chunks_sent", sent)
                    .put("chunks", chunks.size)
            }
            sent += 1
            Thread.sleep(12L)
        }
        return JSONObject()
            .put("ok", true)
            .put("transport", "ble_notify")
            .put("peer_address", address)
            .put("chunks", chunks.size)
            .put("bytes", payload.size)
    }

    private fun sendBlePayloadInternal(targetDeviceId: String, payload: ByteArray, timeoutMs: Long): JSONObject {
        val peer = peers[targetDeviceId]
        val address = peer?.bleAddress?.trim().orEmpty()
        if (address.isBlank()) return JSONObject().put("ok", false).put("error", "peer_ble_unavailable")
        return sendBlePayloadInternalByAddress(address, payload, timeoutMs)
    }

    private fun sendBlePayloadInternalByAddress(address: String, payload: ByteArray, timeoutMs: Long): JSONObject {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return JSONObject().put("ok", false).put("error", "ble_connect_permission_missing")
        }
        val adapter = bluetoothAdapter() ?: return JSONObject().put("ok", false).put("error", "ble_unavailable")
        if (!adapter.isEnabled) return JSONObject().put("ok", false).put("error", "ble_disabled")
        if (address.isBlank()) return JSONObject().put("ok", false).put("error", "peer_ble_address_missing")
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "peer_ble_address_invalid")

        val chunks = buildBleChunks(payload)
        val done = CountDownLatch(1)
        var wroteChunks = 0
        var err: String? = null
        var discovered = false
        var gatt: BluetoothGatt? = null
        var txChar: BluetoothGattCharacteristic? = null
        var rxNotifyChar: BluetoothGattCharacteristic? = null
        val rxBuffer = ByteArrayOutputStream()
        var notifyReady = false
        val expectAck = runCatching {
            val wire = JSONObject(payload.toString(StandardCharsets.UTF_8))
            wire.optString("kind", "").trim() == "data_ingest"
        }.getOrDefault(false)

        fun fail(msg: String) {
            if (err == null) {
                err = msg
                done.countDown()
            }
        }

        fun ingestNotifyChunk(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            val flags = chunk[0].toInt()
            val isStart = (flags and BLE_FLAG_START) != 0
            val isEnd = (flags and BLE_FLAG_END) != 0
            val body = if (chunk.size > 1) chunk.copyOfRange(1, chunk.size) else ByteArray(0)
            synchronized(rxBuffer) {
                if (isStart) rxBuffer.reset()
                if (body.isNotEmpty()) rxBuffer.write(body)
                if (rxBuffer.size() > BLE_MAX_REASSEMBLED_BYTES) {
                    rxBuffer.reset()
                    logger("me.me BLE notify dropped oversized payload", null)
                    return
                }
                if (isEnd) {
                    val assembled = rxBuffer.toByteArray()
                    rxBuffer.reset()
                    if (assembled.isNotEmpty()) {
                        runCatching { onBlePayload(assembled, address) }
                            .onFailure { logger("me.me BLE notify callback failed", it) }
                    }
                }
            }
        }

        fun writeNext() {
            if (err != null) return
            val g = gatt ?: run { fail("ble_gatt_missing"); return }
            val ch = txChar ?: run { fail("ble_tx_characteristic_missing"); return }
            if (!notifyReady) return
            if (wroteChunks >= chunks.size) {
                done.countDown()
                return
            }
            val next = chunks[wroteChunks]
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = next
                g.writeCharacteristic(ch)
            }
            if (!ok) fail("ble_write_start_failed")
        }

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("ble_connect_failed:$status")
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt = g
                    runCatching { g.requestMtu(BLE_MTU) }
                    if (!g.discoverServices()) fail("ble_discover_start_failed")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && err == null && !discovered) {
                    fail("ble_disconnected")
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("ble_discover_failed:$status")
                    return
                }
                val service = g.getService(BLE_GATT_SERVICE_UUID)
                    ?: run { fail("ble_service_missing"); return }
                txChar = service.getCharacteristic(BLE_RX_CHAR_UUID)
                rxNotifyChar = service.getCharacteristic(BLE_TX_CHAR_UUID)
                val notifyChar = rxNotifyChar
                if (notifyChar != null && (notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    val notifySet = g.setCharacteristicNotification(notifyChar, true)
                    if (!notifySet) {
                        fail("ble_notify_enable_failed")
                        return
                    }
                    val ccc = notifyChar.getDescriptor(BLE_CCC_DESCRIPTOR_UUID)
                    if (ccc != null) {
                        val started = if (Build.VERSION.SDK_INT >= 33) {
                            g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
                        } else {
                            ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(ccc)
                        }
                        if (!started) {
                            fail("ble_notify_descriptor_write_failed")
                            return
                        }
                        return
                    }
                }
                notifyReady = true
                discovered = true
                writeNext()
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.characteristic?.uuid != BLE_TX_CHAR_UUID || descriptor.uuid != BLE_CCC_DESCRIPTOR_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("ble_notify_descriptor_failed:$status")
                    return
                }
                notifyReady = true
                discovered = true
                writeNext()
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail("ble_write_failed:$status")
                    return
                }
                wroteChunks += 1
                writeNext()
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid != BLE_TX_CHAR_UUID) return
                ingestNotifyChunk(value)
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33) return
                if (characteristic.uuid != BLE_TX_CHAR_UUID) return
                ingestNotifyChunk(characteristic.value ?: ByteArray(0))
            }
        }

        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, cb)
        }
        if (gatt == null) return JSONObject().put("ok", false).put("error", "ble_connect_start_failed")
        val completed = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (completed && err == null && expectAck) {
            Thread.sleep(timeoutMs.coerceIn(1200L, 3500L))
        }
        if (!completed && err == null) {
            err = "ble_send_timeout"
        }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        return if (err == null && wroteChunks == chunks.size) {
            JSONObject()
                .put("ok", true)
                .put("transport", "ble")
                .put("peer_address", address)
                .put("chunks", chunks.size)
                .put("bytes", payload.size)
        } else {
            JSONObject()
                .put("ok", false)
                .put("transport", "ble")
                .put("peer_address", address)
                .put("error", err ?: "ble_send_failed")
                .put("chunks_sent", wroteChunks)
        }
    }

    private fun startBleDataServer() {
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return
        if (gattServer != null) return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val address = device.address?.trim().orEmpty()
                if (address.isBlank()) return
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    bleConnectedDevices[address] = device
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    bleConnectedDevices.remove(address)
                    bleNotifyEnabledAddresses.remove(address)
                    bleRxBuffers.remove(address)
                }
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                val ok = characteristic.uuid == BLE_RX_CHAR_UUID && offset == 0 && !preparedWrite && value.isNotEmpty()
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                        0,
                        null
                    )
                }
                if (!ok) return
                ingestBleChunk(device.address ?: "", value)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                val address = device.address?.trim().orEmpty()
                val isCcc = descriptor.uuid == BLE_CCC_DESCRIPTOR_UUID && descriptor.characteristic?.uuid == BLE_TX_CHAR_UUID
                val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val ok = isCcc && !preparedWrite && offset == 0 &&
                    (enabled || value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
                if (ok) {
                    if (enabled) bleNotifyEnabledAddresses.add(address) else bleNotifyEnabledAddresses.remove(address)
                }
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device,
                        requestId,
                        if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH,
                        0,
                        null
                    )
                }
            }
        }

        val server = manager.openGattServer(context, callback) ?: return
        val service = BluetoothGattService(BLE_GATT_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            BLE_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val tx = BluetoothGattCharacteristic(
            BLE_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val ccc = BluetoothGattDescriptor(
            BLE_CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        tx.addDescriptor(ccc)
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        if (!server.addService(service)) {
            runCatching { server.close() }
            logger("me.me BLE gatt addService failed", null)
            return
        }
        bleTxCharacteristic = tx
        gattServer = server
    }

    private fun stopBleDataServer() {
        val server = gattServer ?: return
        runCatching { server.clearServices() }
        runCatching { server.close() }
        gattServer = null
        bleTxCharacteristic = null
        bleConnectedDevices.clear()
        bleNotifyEnabledAddresses.clear()
        bleRxBuffers.clear()
    }

    private fun ingestBleChunk(sourceAddress: String, chunk: ByteArray) {
        if (chunk.isEmpty()) return
        val flags = chunk[0].toInt()
        val isStart = (flags and BLE_FLAG_START) != 0
        val isEnd = (flags and BLE_FLAG_END) != 0
        val body = if (chunk.size > 1) chunk.copyOfRange(1, chunk.size) else ByteArray(0)
        val key = sourceAddress.ifBlank { "unknown" }
        val buffer = bleRxBuffers.computeIfAbsent(key) { ByteArrayOutputStream() }
        synchronized(buffer) {
            if (isStart) buffer.reset()
            if (body.isNotEmpty()) buffer.write(body)
            if (buffer.size() > BLE_MAX_REASSEMBLED_BYTES) {
                buffer.reset()
                logger("me.me BLE rx dropped oversized payload", null)
                return
            }
            if (isEnd) {
                val payload = buffer.toByteArray()
                buffer.reset()
                if (payload.isNotEmpty()) {
                    runCatching { onBlePayload(payload, sourceAddress) }
                        .onFailure { logger("me.me BLE payload callback failed", it) }
                }
            }
        }
    }

    private fun buildBleChunks(payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val out = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val rem = payload.size - offset
            val n = rem.coerceAtMost(BLE_CHUNK_BODY_MAX)
            val part = payload.copyOfRange(offset, offset + n)
            val start = offset == 0
            val end = offset + n >= payload.size
            var flags = 0
            if (start) flags = flags or BLE_FLAG_START
            if (end) flags = flags or BLE_FLAG_END
            out += byteArrayOf(flags.toByte()) + part
            offset += n
        }
        return out
    }

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun buildBleServiceData(config: Config): ByteArray {
        // Keep BLE payload tiny. Legacy ASCII payload frequently exceeds advertisement limits.
        // v1 binary format: [1-byte version=1][16-byte UUID(legacy install_ suffix)].
        // New short IDs (d_xxxxxx) use compact ASCII fallback below.
        val uuid = runCatching {
            UUID.fromString(config.deviceId.removePrefix("install_"))
        }.getOrNull()
        if (uuid != null) {
            return ByteBuffer.allocate(17)
                .put(1.toByte())
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
        }
        val fallback = "id=${sanitizeForBle(config.deviceId, 24)}"
        return fallback.toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseBleServiceData(data: ByteArray): Map<String, String> {
        if (data.size == 17 && data[0] == 1.toByte()) {
            return runCatching {
                val buf = ByteBuffer.wrap(data)
                buf.get() // version
                val uuid = UUID(buf.long, buf.long)
                // Legacy format compatibility: keep original install_ style ID.
                mapOf("id" to "install_$uuid")
            }.getOrDefault(emptyMap())
        }
        val raw = data.toString(StandardCharsets.UTF_8)
        val out = mutableMapOf<String, String>()
        raw.split(';').forEach { segment ->
            val idx = segment.indexOf('=')
            if (idx <= 0 || idx >= segment.length - 1) return@forEach
            val key = segment.substring(0, idx).trim()
            val value = segment.substring(idx + 1).trim()
            if (key.isNotBlank() && value.isNotBlank()) out[key] = value
        }
        return out
    }

    private fun sanitizeForBle(input: String, max: Int): String {
        return input
            .replace(';', ' ')
            .replace('=', ' ')
            .trim()
            .take(max)
    }

    companion object {
        private const val NSD_SERVICE_TYPE = "_me_things._tcp."
        private const val NSD_NAME_PREFIX = "me-things-"
        // 16-bit based UUID keeps BLE advertisement payload compact enough for legacy 31-byte limit.
        private val BLE_SERVICE_UUID: UUID = UUID.fromString("0000F0F0-0000-1000-8000-00805F9B34FB")
        private val BLE_GATT_SERVICE_UUID: UUID = UUID.fromString("0000F0F1-0000-1000-8000-00805F9B34FB")
        private val BLE_RX_CHAR_UUID: UUID = UUID.fromString("0000F0F2-0000-1000-8000-00805F9B34FB")
        private val BLE_TX_CHAR_UUID: UUID = UUID.fromString("0000F0F3-0000-1000-8000-00805F9B34FB")
        private val BLE_CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val BLE_FLAG_START = 1
        private const val BLE_FLAG_END = 2
        private const val BLE_CHUNK_BODY_MAX = 180
        private const val BLE_MAX_REASSEMBLED_BYTES = 1_200_000
        private const val BLE_MTU = 247
        private const val PEER_STALE_MS = 10L * 60L * 1000L
    }
}
