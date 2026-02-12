package jp.espresso3389.methings.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.TelephonyManager

class DeviceNetworkManager(private val context: Context) {
    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(ConnectivityManager::class.java)

    private val wifiManager: WifiManager?
        get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val telephonyManager: TelephonyManager?
        get() = context.getSystemService(TelephonyManager::class.java)

    fun status(): Map<String, Any?> {
        val cm = connectivityManager
        if (cm == null) {
            return mapOf("status" to "error", "error" to "connectivity_manager_unavailable")
        }
        val activeNetwork = cm.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
        val lp = if (activeNetwork != null) cm.getLinkProperties(activeNetwork) else null
        val transports = ArrayList<String>()
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.add("bluetooth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports.add("vpn")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && caps.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN)) transports.add("lowpan")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) transports.add("wifi_aware")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) transports.add("usb")
        }
        return mapOf(
            "status" to "ok",
            "connected" to (activeNetwork != null),
            "network_id" to (activeNetwork?.toString() ?: ""),
            "transports" to transports,
            "has_internet" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false),
            "validated" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false),
            "not_metered" to (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false),
            "metered" to cm.isActiveNetworkMetered,
            "interface" to (lp?.interfaceName ?: ""),
            "dns" to (lp?.dnsServers?.map { it.hostAddress ?: "" } ?: emptyList<String>()),
            "wifi" to wifiStatus(),
            "mobile" to mobileStatus(),
        )
    }

    fun wifiStatus(): Map<String, Any?> {
        val wm = wifiManager
        val cm = connectivityManager
        if (wm == null || cm == null) {
            return mapOf("status" to "error", "error" to "wifi_manager_unavailable")
        }
        val activeNetwork = cm.activeNetwork
        val caps = if (activeNetwork != null) cm.getNetworkCapabilities(activeNetwork) else null
        val connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val info = runCatching { wm.connectionInfo }.getOrNull()
        val ssid = normalizeWifiSsid(info?.ssid)
        val bssid = normalizeBssid(info?.bssid)
        val out = linkedMapOf<String, Any?>(
            "status" to "ok",
            "enabled" to wm.isWifiEnabled,
            "connected" to connected,
            "ssid" to ssid,
            "bssid" to bssid,
            "network_id" to (info?.networkId ?: -1),
            "rssi" to (info?.rssi ?: Int.MIN_VALUE),
            "link_speed_mbps" to (info?.linkSpeed ?: -1),
            "tx_link_speed_mbps" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (info?.txLinkSpeedMbps ?: -1) else -1,
            "rx_link_speed_mbps" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (info?.rxLinkSpeedMbps ?: -1) else -1,
            "frequency_mhz" to (info?.frequency ?: -1),
            "ip_address" to runCatching { info?.ipAddress?.let(::intToIpv4) ?: "" }.getOrDefault(""),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            out["wifi_standard"] = info?.wifiStandard ?: -1
        }
        return out
    }

    fun mobileStatus(): Map<String, Any?> {
        val tm = telephonyManager ?: return mapOf("status" to "error", "error" to "telephony_manager_unavailable")
        val signal = runCatching { tm.signalStrength }.getOrNull()
        val strengths: List<CellSignalStrength> = runCatching { signal?.cellSignalStrengths ?: emptyList() }.getOrDefault(emptyList())
        val first = strengths.firstOrNull()
        return mapOf(
            "status" to "ok",
            "phone_type" to runCatching { tm.phoneType }.getOrDefault(0),
            "sim_state" to runCatching { tm.simState }.getOrDefault(0),
            "data_state" to runCatching { tm.dataState }.getOrDefault(0),
            "data_activity" to runCatching { tm.dataActivity }.getOrDefault(0),
            "data_network_type" to runCatching { tm.dataNetworkType }.getOrDefault(0),
            "voice_network_type" to runCatching { tm.voiceNetworkType }.getOrDefault(0),
            "network_operator" to runCatching { tm.networkOperator ?: "" }.getOrDefault(""),
            "network_operator_name" to runCatching { tm.networkOperatorName ?: "" }.getOrDefault(""),
            "sim_operator" to runCatching { tm.simOperator ?: "" }.getOrDefault(""),
            "sim_operator_name" to runCatching { tm.simOperatorName ?: "" }.getOrDefault(""),
            "country_iso" to runCatching { tm.networkCountryIso ?: "" }.getOrDefault(""),
            "is_data_enabled" to runCatching { tm.isDataEnabled }.getOrDefault(false),
            "signal_level" to (signal?.level ?: -1),
            "signal_asu" to (signal?.gsmSignalStrength ?: -1),
            "cell_signal_dbm" to (first?.dbm ?: Int.MIN_VALUE),
            "cell_signal_asu" to (first?.asuLevel ?: -1),
            "cell_signal_level" to (first?.level ?: -1),
        )
    }

    private fun normalizeWifiSsid(raw: String?): String {
        val s = (raw ?: "").trim()
        if (s.isEmpty()) return ""
        if (s == "<unknown ssid>") return ""
        return if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s
    }

    private fun normalizeBssid(raw: String?): String {
        val s = (raw ?: "").trim()
        if (s.equals("02:00:00:00:00:00", ignoreCase = true)) return ""
        return s
    }

    private fun intToIpv4(v: Int): String {
        return "${v and 0xFF}.${(v ushr 8) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 24) and 0xFF}"
    }
}
