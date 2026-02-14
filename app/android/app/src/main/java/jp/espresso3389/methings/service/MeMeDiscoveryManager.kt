package jp.espresso3389.methings.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MeMeDiscoveryManager(
    private val context: Context,
    private val servicePort: Int,
    private val logger: (String, Throwable?) -> Unit
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

    @Volatile private var registeredService: NsdServiceInfo? = null
    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
    @Volatile private var bleAdvertiser: BluetoothLeAdvertiser? = null
    @Volatile private var bleAdvertiseCallback: AdvertiseCallback? = null
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
        } else {
            stopBleAdvertise()
        }
    }

    fun shutdown() {
        stopWifiAdvertise()
        stopBleAdvertise()
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
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
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
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
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

    private fun bluetoothAdapter(): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    private fun buildBleServiceData(config: Config): ByteArray {
        val raw = "id=${config.deviceId};name=${sanitizeForBle(config.deviceName, 24)};" +
            "desc=${sanitizeForBle(config.deviceDescription, 20)};icon=${sanitizeForBle(config.deviceIcon, 16)}"
        return raw.take(120).toByteArray(StandardCharsets.UTF_8)
    }

    private fun parseBleServiceData(data: ByteArray): Map<String, String> {
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
        private val BLE_SERVICE_UUID: UUID = UUID.fromString("2585d3ed-971b-4bfd-8f13-bda58ac27f6e")
    }
}
