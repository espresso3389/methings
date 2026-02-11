package jp.espresso3389.methings.device

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SensorsStreamManager(private val context: Context) {
    data class StreamStart(
        val sensors: List<String>,
        val rateHz: Int,
        val latency: String,
        val timestamp: String,
        val bufferMax: Int,
    )

    private data class StreamState(
        val id: String,
        val requestedSensors: List<String>,
        val sensorMap: Map<String, Sensor>,
        val requestedRateHz: Int,
        val latency: String,
        val timestamp: String,
        val bufferMax: Int,
        val wsClients: CopyOnWriteArrayList<NanoWSD.WebSocket>,
        val running: AtomicBoolean,
        val seq: AtomicLong,
        val bufLock: Any,
        val buf: ArrayDeque<JSONObject>,
        val dropped: AtomicLong,
        @Volatile var lastEvent: JSONObject?,
        val listeners: MutableList<SensorEventListener>,
    )

    private val streams = ConcurrentHashMap<String, StreamState>()

    private fun mgr(): SensorManager? {
        return context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    fun listSensors(): Map<String, Any?> {
        val m = mgr() ?: return mapOf("status" to "error", "error" to "sensor_manager_unavailable")
        val out = JSONArray()
        val sensors = runCatching { m.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
        for (s in sensors) {
            out.put(sensorInfoJson(s))
        }
        return mapOf("status" to "ok", "items" to out.toString())
    }

    fun streamsStatus(): Map<String, Any?> {
        val arr = JSONArray()
        for (st in streams.values) {
            arr.put(streamStatus(st.id))
        }
        return mapOf("status" to "ok", "items" to arr.toString())
    }

    fun streamStatus(streamId: String): JSONObject {
        val id = streamId.trim()
        val st = streams[id] ?: return JSONObject(mapOf("status" to "error", "error" to "stream_not_found"))
        val last = st.lastEvent
        return JSONObject()
            .put("status", "ok")
            .put("stream_id", st.id)
            .put("running", st.running.get())
            .put("requested_rate_hz", st.requestedRateHz)
            .put("latency", st.latency)
            .put("timestamp", st.timestamp)
            .put("sensors", JSONArray(st.requestedSensors))
            .put("ws_clients", st.wsClients.size)
            .put("buffer_size", synchronized(st.bufLock) { st.buf.size })
            .put("dropped", st.dropped.get())
            .put("latest_q", st.seq.get())
            .put("latest_t", last?.optDouble("t", 0.0) ?: 0.0)
    }

    fun latest(streamId: String): JSONObject {
        val id = streamId.trim()
        val st = streams[id] ?: return JSONObject(mapOf("status" to "error", "error" to "stream_not_found"))
        return st.lastEvent ?: JSONObject(mapOf("status" to "error", "error" to "no_event"))
    }

    fun batch(streamId: String, sinceQExclusive: Long, limit: Int): JSONObject {
        val id = streamId.trim()
        val st = streams[id] ?: return JSONObject(mapOf("status" to "error", "error" to "stream_not_found"))
        val out = JSONArray()
        val lim = limit.coerceIn(1, 5000)
        synchronized(st.bufLock) {
            for (e in st.buf) {
                val q = e.optLong("q", -1L)
                if (q > sinceQExclusive) {
                    out.put(e)
                    if (out.length() >= lim) break
                }
            }
        }
        return JSONObject()
            .put("status", "ok")
            .put("stream_id", id)
            .put("since_q_exclusive", sinceQExclusive)
            .put("items", out)
    }

    @SuppressLint("MissingPermission")
    fun start(req: StreamStart): Map<String, Any?> {
        val m = mgr() ?: return mapOf("status" to "error", "error" to "sensor_manager_unavailable")
        val keys = req.sensors.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return mapOf("status" to "error", "error" to "sensors_required")

        val sensorMap = resolveSensors(m, keys)
        if (sensorMap.isEmpty()) {
            return mapOf("status" to "error", "error" to "no_sensors_resolved")
        }

        val id = "s" + UUID.randomUUID().toString().replace("-", "").take(8)
        val rateHz = req.rateHz.coerceIn(1, 1000)
        val latency = req.latency.trim().lowercase().ifBlank { "realtime" }
        val timestamp = req.timestamp.trim().lowercase().let { if (it == "unix") "unix" else "mono" }
        val bufferMax = req.bufferMax.coerceIn(64, 50_000)

        val st = StreamState(
            id = id,
            requestedSensors = keys,
            sensorMap = sensorMap,
            requestedRateHz = rateHz,
            latency = latency,
            timestamp = timestamp,
            bufferMax = bufferMax,
            wsClients = CopyOnWriteArrayList(),
            running = AtomicBoolean(true),
            seq = AtomicLong(0L),
            bufLock = Any(),
            buf = ArrayDeque(),
            dropped = AtomicLong(0L),
            lastEvent = null,
            listeners = mutableListOf(),
        )

        val samplingPeriodUs = (1_000_000 / rateHz.coerceAtLeast(1)).coerceIn(0, 1_000_000)
        for ((key, sensor) in sensorMap) {
            val l = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!st.running.get()) return
                    val q = st.seq.incrementAndGet()
                    val t = currentSeconds(st.timestamp)
                    val vals = JSONArray()
                    for (x in event.values) vals.put(x.toDouble())
                    val obj = JSONObject()
                        .put("status", "ok")
                        .put("s", st.id)
                        .put("k", key)
                        .put("t", t)
                        .put("v", vals)
                        .put("q", q)
                    st.lastEvent = obj
                    synchronized(st.bufLock) {
                        st.buf.addLast(obj)
                        while (st.buf.size > st.bufferMax) {
                            st.buf.removeFirst()
                            st.dropped.incrementAndGet()
                        }
                    }
                    emit(st, obj)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            st.listeners.add(l)
            val ok = runCatching {
                // For best-effort realtime, ask for FASTEST when the caller wants high rate.
                if (rateHz >= 200) {
                    m.registerListener(l, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                } else {
                    m.registerListener(l, sensor, samplingPeriodUs)
                }
            }.getOrDefault(false)
            if (!ok) {
                st.running.set(false)
                for (li in st.listeners) runCatching { m.unregisterListener(li) }
                return mapOf("status" to "error", "error" to "register_failed", "sensor" to sensorInfoJson(sensor).toString())
            }
        }

        streams[id] = st
        return mapOf(
            "status" to "ok",
            "stream_id" to id,
            "ws_path" to "/ws/sensor/stream/$id",
            "sensors" to JSONArray(keys).toString(),
            "rate_hz" to rateHz,
            "latency" to latency,
            "timestamp" to timestamp,
        )
    }

    fun stop(streamId: String): Map<String, Any?> {
        val id = streamId.trim()
        val st = streams.remove(id) ?: return mapOf("status" to "ok", "stopped" to false, "stream_id" to id)
        st.running.set(false)
        val m = mgr()
        if (m != null) {
            for (l in st.listeners) runCatching { m.unregisterListener(l) }
        }
        return mapOf("status" to "ok", "stopped" to true, "stream_id" to id)
    }

    fun addWsClient(streamId: String, ws: NanoWSD.WebSocket): Boolean {
        val st = streams[streamId.trim()] ?: return false
        st.wsClients.add(ws)
        return true
    }

    fun removeWsClient(streamId: String, ws: NanoWSD.WebSocket) {
        streams[streamId.trim()]?.wsClients?.remove(ws)
    }

    private fun resolveSensors(m: SensorManager, keys: List<String>): Map<String, Sensor> {
        val out = LinkedHashMap<String, Sensor>()
        // Prefer resolving by known short keys and type constants.
        for (k in keys) {
            val key = k.trim().lowercase()
            val type = sensorTypeFromKey(key)
            if (type != null) {
                val s = m.getDefaultSensor(type)
                if (s != null) {
                    out[key] = s
                    continue
                }
            }
        }

        // Fallback: allow passing stringType on the device (from sensors.list).
        val all = runCatching { m.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
        val byStringType = all.associateBy { runCatching { it.stringType }.getOrDefault("") }
        for (k in keys) {
            val raw = k.trim()
            if (raw.isBlank()) continue
            val lower = raw.lowercase()
            if (out.containsKey(lower)) continue
            val st = byStringType[raw] ?: byStringType[lower]
            if (st != null) out[lower] = st
        }
        return out
    }

    private fun sensorTypeFromKey(key: String): Int? {
        return when (key) {
            // Short keys per last-session spec
            "a" -> Sensor.TYPE_ACCELEROMETER
            "g" -> Sensor.TYPE_GYROSCOPE
            "m" -> Sensor.TYPE_MAGNETIC_FIELD
            "r" -> Sensor.TYPE_ROTATION_VECTOR
            "u" -> Sensor.TYPE_LINEAR_ACCELERATION
            "w" -> Sensor.TYPE_GRAVITY
            "x" -> Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
            "y" -> Sensor.TYPE_GAME_ROTATION_VECTOR
            "z" -> Sensor.TYPE_PROXIMITY
            "l" -> Sensor.TYPE_LIGHT
            "p" -> Sensor.TYPE_PRESSURE
            "h" -> Sensor.TYPE_RELATIVE_HUMIDITY
            "t" -> Sensor.TYPE_AMBIENT_TEMPERATURE
            "s" -> Sensor.TYPE_STEP_COUNTER
            "q" -> Sensor.TYPE_STEP_DETECTOR

            // Also accept long aliases.
            "accel", "accelerometer", "type_accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyro", "gyroscope", "type_gyroscope" -> Sensor.TYPE_GYROSCOPE
            "mag", "magnetic", "magnetic_field", "type_magnetic_field" -> Sensor.TYPE_MAGNETIC_FIELD
            "rotation", "rotation_vector", "type_rotation_vector" -> Sensor.TYPE_ROTATION_VECTOR
            "linear", "linear_acceleration", "type_linear_acceleration" -> Sensor.TYPE_LINEAR_ACCELERATION
            "gravity", "type_gravity" -> Sensor.TYPE_GRAVITY
            "geomagnetic_rotation_vector", "type_geomagnetic_rotation_vector" -> Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
            "game_rotation_vector", "type_game_rotation_vector" -> Sensor.TYPE_GAME_ROTATION_VECTOR
            "proximity", "type_proximity" -> Sensor.TYPE_PROXIMITY
            "light", "type_light" -> Sensor.TYPE_LIGHT
            "pressure", "type_pressure" -> Sensor.TYPE_PRESSURE
            "humidity", "relative_humidity", "type_relative_humidity" -> Sensor.TYPE_RELATIVE_HUMIDITY
            "ambient_temperature", "temperature", "type_ambient_temperature" -> Sensor.TYPE_AMBIENT_TEMPERATURE
            "step_counter", "type_step_counter" -> Sensor.TYPE_STEP_COUNTER
            "step_detector", "type_step_detector" -> Sensor.TYPE_STEP_DETECTOR
            else -> null
        }
    }

    private fun sensorInfoJson(s: Sensor): JSONObject {
        return JSONObject()
            .put("type", s.type)
            .put("string_type", runCatching { s.stringType }.getOrDefault(""))
            .put("name", s.name ?: "")
            .put("vendor", s.vendor ?: "")
            .put("version", s.version)
            .put("power_mw", s.power.toDouble())
            .put("resolution", s.resolution.toDouble())
            .put("max_range", s.maximumRange.toDouble())
            .put("min_delay_us", s.minDelay)
    }

    private fun currentSeconds(mode: String): Double {
        return if (mode == "unix") {
            System.currentTimeMillis().toDouble() / 1000.0
        } else {
            SystemClock.elapsedRealtimeNanos().toDouble() / 1_000_000_000.0
        }
    }

    private fun emit(st: StreamState, obj: JSONObject) {
        val dead = ArrayList<NanoWSD.WebSocket>()
        val text = obj.toString()
        for (ws in st.wsClients) {
            try {
                if (ws.isOpen) ws.send(text) else dead.add(ws)
            } catch (_: Exception) {
                dead.add(ws)
            }
        }
        for (ws in dead) st.wsClients.remove(ws)
    }
}
