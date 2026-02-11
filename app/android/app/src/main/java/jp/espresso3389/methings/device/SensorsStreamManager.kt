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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SensorsStreamManager(private val context: Context) {
    data class StreamStart(
        val sensors: List<String>,
        val rateHz: Int,
        val batchMs: Int,
        val timestamp: String,
        val bufferMax: Int,
    )

    private data class StreamState(
        val id: String,
        val sensors: List<Sensor>,
        val names: List<String>,
        val rateHz: Int,
        val batchMs: Int,
        val timestamp: String,
        val bufferMax: Int,
        val wsClients: CopyOnWriteArrayList<NanoWSD.WebSocket>,
        val bufLock: Any,
        val frames: ArrayDeque<JSONObject>,
        var seq: Long,
        val lastValues: ConcurrentHashMap<String, JSONArray>,
        val updatedSinceTick: ConcurrentHashMap<String, Boolean>,
        val running: AtomicBoolean,
        var scheduler: ScheduledExecutorService?,
        var scheduled: ScheduledFuture<*>?,
        val listeners: MutableList<SensorEventListener>,
        @Volatile var lastFrame: JSONObject?,
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
            val sArr = JSONArray()
            for (i in st.sensors.indices) {
                sArr.put(JSONObject().put("name", st.names[i]).put("sensor", sensorInfoJson(st.sensors[i])))
            }
            arr.put(
                JSONObject()
                    .put("id", st.id)
                    .put("sensors", sArr)
                    .put("rate_hz", st.rateHz)
                    .put("batch_ms", st.batchMs)
                    .put("timestamp", st.timestamp)
                    .put("running", st.running.get())
                    .put("ws_clients", st.wsClients.size)
                    .put("buffer_size", synchronized(st.bufLock) { st.frames.size })
                    .put("last_seq", st.seq)
                    .put("last_ts_ms", st.lastFrame?.optLong("t_ms", 0L) ?: 0L)
            )
        }
        return mapOf("status" to "ok", "items" to arr.toString())
    }

    fun latest(streamId: String): JSONObject {
        val st = streams[streamId.trim()] ?: return JSONObject(mapOf("status" to "error", "error" to "stream_not_found"))
        return st.lastFrame ?: JSONObject(mapOf("status" to "error", "error" to "no_frame"))
    }

    fun batch(streamId: String, sinceSeqExclusive: Long, limit: Int = 200): JSONObject {
        val st = streams[streamId.trim()] ?: return JSONObject(mapOf("status" to "error", "error" to "stream_not_found"))
        val out = JSONArray()
        val lim = limit.coerceIn(1, 2000)
        synchronized(st.bufLock) {
            for (f in st.frames) {
                val seq = f.optLong("seq", -1L)
                if (seq > sinceSeqExclusive) {
                    out.put(f)
                    if (out.length() >= lim) break
                }
            }
        }
        return JSONObject()
            .put("status", "ok")
            .put("stream_id", st.id)
            .put("since_seq_exclusive", sinceSeqExclusive)
            .put("items", out)
    }

    @SuppressLint("MissingPermission")
    fun start(req: StreamStart): Map<String, Any?> {
        val m = mgr() ?: return mapOf("status" to "error", "error" to "sensor_manager_unavailable")
        val names = req.sensors.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return mapOf("status" to "error", "error" to "sensors_required")

        val resolved = resolveSensors(m, names)
        if (resolved.isEmpty()) return mapOf("status" to "error", "error" to "no_sensors_resolved")

        val id = "ss_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val rateHz = req.rateHz.coerceIn(1, 200)
        val batchMs = req.batchMs.coerceIn(5, 1000)
        val timestamp = (req.timestamp.ifBlank { "mono" }).trim().lowercase().let { if (it == "unix") "unix" else "mono" }
        val bufferMax = req.bufferMax.coerceIn(16, 4096)

        val sensors = resolved.map { it.first }
        val sensorNames = resolved.map { it.second }

        val st = StreamState(
            id = id,
            sensors = sensors,
            names = sensorNames,
            rateHz = rateHz,
            batchMs = batchMs,
            timestamp = timestamp,
            bufferMax = bufferMax,
            wsClients = CopyOnWriteArrayList(),
            bufLock = Any(),
            frames = ArrayDeque(),
            seq = 0L,
            lastValues = ConcurrentHashMap(),
            updatedSinceTick = ConcurrentHashMap(),
            running = AtomicBoolean(true),
            scheduler = null,
            scheduled = null,
            listeners = mutableListOf(),
            lastFrame = null,
        )

        for (name in sensorNames) {
            st.updatedSinceTick[name] = false
        }

        val samplingPeriodUs = (1_000_000 / rateHz).coerceIn(5_000, 1_000_000)
        for (idx in sensors.indices) {
            val sensor = sensors[idx]
            val name = sensorNames[idx]
            val l = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (!st.running.get()) return
                    val vals = JSONArray()
                    for (x in event.values) vals.put(x.toDouble())
                    st.lastValues[name] = vals
                    st.updatedSinceTick[name] = true
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            st.listeners.add(l)
            val ok = runCatching { m.registerListener(l, sensor, samplingPeriodUs) }.getOrDefault(false)
            if (!ok) {
                st.running.set(false)
                for (li in st.listeners) runCatching { m.unregisterListener(li) }
                return mapOf("status" to "error", "error" to "register_failed", "sensor" to sensorInfoJson(sensor).toString())
            }
        }

        val exec = Executors.newSingleThreadScheduledExecutor()
        st.scheduler = exec
        st.scheduled = exec.scheduleAtFixedRate(
            { tick(st) },
            0L,
            batchMs.toLong(),
            TimeUnit.MILLISECONDS
        )

        streams[id] = st
        return mapOf(
            "status" to "ok",
            "stream_id" to id,
            "ws_path" to "/ws/sensors/stream/$id",
            "rate_hz" to rateHz,
            "batch_ms" to batchMs,
            "timestamp" to timestamp,
            "sensors" to JSONArray(sensorNames).toString(),
        )
    }

    fun stop(streamId: String): Map<String, Any?> {
        val id = streamId.trim()
        val st = streams.remove(id) ?: return mapOf("status" to "ok", "stopped" to false, "stream_id" to id)
        st.running.set(false)
        runCatching { st.scheduled?.cancel(true) }
        runCatching { st.scheduler?.shutdownNow() }
        st.scheduled = null
        st.scheduler = null

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

    private fun tick(st: StreamState) {
        if (!st.running.get()) return
        val monoMs = SystemClock.elapsedRealtime()
        val unixMs = System.currentTimeMillis()

        val samples = JSONObject()
        for (name in st.names) {
            val updated = st.updatedSinceTick[name] == true
            val vals = st.lastValues[name]
            if (!updated || vals == null) {
                samples.put(name, JSONObject.NULL)
            } else {
                samples.put(name, vals)
            }
            st.updatedSinceTick[name] = false
        }

        val seq = st.seq + 1
        st.seq = seq

        val frame = JSONObject()
            .put("status", "ok")
            .put("type", "sensors")
            .put("stream_id", st.id)
            .put("seq", seq)
            .put("t_ms", if (st.timestamp == "unix") unixMs else monoMs)
            .put("samples", samples)
        if (st.timestamp == "unix") {
            frame.put("mono_ms", monoMs)
        } else {
            frame.put("unix_ms", unixMs)
        }

        st.lastFrame = frame
        synchronized(st.bufLock) {
            st.frames.addLast(frame)
            while (st.frames.size > st.bufferMax) st.frames.removeFirst()
        }

        val dead = ArrayList<NanoWSD.WebSocket>()
        val text = frame.toString()
        for (ws in st.wsClients) {
            try {
                if (ws.isOpen) ws.send(text) else dead.add(ws)
            } catch (_: Exception) {
                dead.add(ws)
            }
        }
        for (ws in dead) st.wsClients.remove(ws)
    }

    private fun resolveSensors(m: SensorManager, names: List<String>): List<Pair<Sensor, String>> {
        val out = mutableListOf<Pair<Sensor, String>>()
        val all = runCatching { m.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
        val byString = all.associateBy { runCatching { it.stringType }.getOrDefault("") }
        for (raw in names) {
            val n = raw.trim()
            val lower = n.lowercase()
            val direct = byString[n]
            if (direct != null) {
                out.add(Pair(direct, n))
                continue
            }
            val type = sensorTypeFromName(lower)
            if (type != null) {
                val s = m.getDefaultSensor(type)
                if (s != null) {
                    out.add(Pair(s, lower))
                    continue
                }
            }
            // Best-effort: match by substring in sensor name.
            val matched = all.firstOrNull { s ->
                val nm = (s.name ?: "").lowercase()
                nm.contains(lower) || nm.contains(n.lowercase())
            }
            if (matched != null) {
                out.add(Pair(matched, lower))
            }
        }
        // Dedup by sensor type+name label.
        val seen = HashSet<String>()
        val dedup = mutableListOf<Pair<Sensor, String>>()
        for (p in out) {
            val key = "${p.first.type}:${p.second}"
            if (seen.add(key)) dedup.add(p)
        }
        return dedup
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

    private fun sensorTypeFromName(n: String): Int? {
        if (n.isBlank()) return null
        return when (n) {
            "accelerometer", "accel", "type_accelerometer", "加速度" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope", "gyro", "type_gyroscope", "ジャイロ" -> Sensor.TYPE_GYROSCOPE
            "magnetic_field", "mag", "type_magnetic_field", "磁気" -> Sensor.TYPE_MAGNETIC_FIELD
            "gravity", "type_gravity", "重力" -> Sensor.TYPE_GRAVITY
            "linear_acceleration", "linear", "type_linear_acceleration", "線形加速度" -> Sensor.TYPE_LINEAR_ACCELERATION
            "rotation_vector", "type_rotation_vector", "回転ベクトル" -> Sensor.TYPE_ROTATION_VECTOR
            "game_rotation_vector", "type_game_rotation_vector", "game_rotation" -> Sensor.TYPE_GAME_ROTATION_VECTOR
            "orientation", "type_orientation", "方位", "姿勢" -> Sensor.TYPE_ORIENTATION
            "light", "type_light", "照度" -> Sensor.TYPE_LIGHT
            "proximity", "type_proximity", "近接" -> Sensor.TYPE_PROXIMITY
            "pressure", "type_pressure", "気圧" -> Sensor.TYPE_PRESSURE
            "ambient_temperature", "type_ambient_temperature", "温度" -> Sensor.TYPE_AMBIENT_TEMPERATURE
            "relative_humidity", "type_relative_humidity", "湿度" -> Sensor.TYPE_RELATIVE_HUMIDITY
            "heart_rate", "type_heart_rate", "心拍" -> Sensor.TYPE_HEART_RATE
            "step_counter", "type_step_counter", "ステップカウンタ" -> Sensor.TYPE_STEP_COUNTER
            "step_detector", "type_step_detector", "ステップ検出" -> Sensor.TYPE_STEP_DETECTOR
            "significant_motion", "type_significant_motion", "有意移動" -> Sensor.TYPE_SIGNIFICANT_MOTION
            // These constants are not available on all Android API levels in the build environment.
            // Prefer resolving via sensors.list + string_type matching on the device.
            "pose_6dof", "type_pose_6dof" -> Sensor.TYPE_POSE_6DOF
            else -> null
        }
    }
}
