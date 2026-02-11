package jp.espresso3389.methings.device

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class DeviceLocationManager(private val context: Context) {
    private fun mgr(): LocationManager? {
        return context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    }

    fun status(): Map<String, Any?> {
        val m = mgr() ?: return mapOf("status" to "error", "error" to "location_manager_unavailable")
        val providers: List<String> = runCatching { m.allProviders }.getOrDefault(emptyList())
        val enabled = providers.associateWith { p -> runCatching { m.isProviderEnabled(p) }.getOrDefault(false) }
        return mapOf(
            "status" to "ok",
            "providers" to providers,
            "enabled" to enabled,
        )
    }

    @SuppressLint("MissingPermission")
    fun getCurrent(
        highAccuracy: Boolean = true,
        timeoutMs: Long = 12_000,
    ): Map<String, Any?> {
        val m = mgr() ?: return mapOf("status" to "error", "error" to "location_manager_unavailable")

        val candidates = if (highAccuracy) {
            listOf(
                // FUSED_PROVIDER exists as a constant; it may not be enabled on all devices.
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
        } else {
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
                LocationManager.GPS_PROVIDER,
            )
        }

        val provider = candidates.firstOrNull { p ->
            runCatching { m.allProviders.contains(p) && m.isProviderEnabled(p) }.getOrDefault(false)
        } ?: ""

        if (provider.isBlank()) {
            return mapOf(
                "status" to "error",
                "error" to "no_provider_enabled",
                "detail" to status(),
            )
        }

        val exec = ContextCompat.getMainExecutor(context)
        val latch = CountDownLatch(1)
        val out = AtomicReference<Location?>(null)
        val err = AtomicReference<String?>(null)

        val signal = android.os.CancellationSignal()
        runCatching {
            m.getCurrentLocation(provider, signal, exec) { loc ->
                out.set(loc)
                latch.countDown()
            }
        }.onFailure { ex ->
            err.set(ex.message ?: ex.toString())
            latch.countDown()
        }

        val ok = latch.await(timeoutMs.coerceAtLeast(250), TimeUnit.MILLISECONDS)
        if (!ok) {
            runCatching { signal.cancel() }
            return mapOf("status" to "error", "error" to "timeout", "timeout_ms" to timeoutMs)
        }
        val e = err.get()
        if (!e.isNullOrBlank()) {
            return mapOf("status" to "error", "error" to "get_current_failed", "detail" to e)
        }
        val loc = out.get() ?: return mapOf("status" to "error", "error" to "no_location")

        fun putIf(has: Boolean, v: Any?): Any? = if (has) v else null

        return mapOf(
            "status" to "ok",
            "provider" to provider,
            "latitude" to loc.latitude,
            "longitude" to loc.longitude,
            "accuracy_m" to putIf(loc.hasAccuracy(), loc.accuracy.toDouble()),
            "altitude_m" to putIf(loc.hasAltitude(), loc.altitude),
            "bearing_deg" to putIf(loc.hasBearing(), loc.bearing.toDouble()),
            "speed_mps" to putIf(loc.hasSpeed(), loc.speed.toDouble()),
            "time_ms" to loc.time,
            "elapsed_realtime_nanos" to loc.elapsedRealtimeNanos,
        )
    }
}
