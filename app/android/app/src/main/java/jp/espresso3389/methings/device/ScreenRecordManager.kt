package jp.espresso3389.methings.device

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import jp.espresso3389.methings.ui.ScreenCaptureActivity
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class ScreenRecordManager(private val context: Context) {
    companion object {
        private const val TAG = "ScreenRecordManager"
        private const val PREFS = "screen_record_config"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val userRoot = File(context.filesDir, "user")
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var projection: MediaProjection? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var recordingState: String = "idle"
    @Volatile private var recordingFile: File? = null
    @Volatile private var recordingStartMs: Long = 0L
    @Volatile private var recordingCodec: String = "unknown"
    @Volatile private var lastError: String? = null

    // ── Config ────────────────────────────────────────────────────────────────

    fun getConfig(): Map<String, Any?> {
        return mapOf(
            "status" to "ok",
            "resolution" to prefs.getString("resolution", "720p"),
            "bitrate" to prefs.getInt("bitrate", 6_000_000),
            "max_duration_s" to prefs.getInt("max_duration_s", 300)
        )
    }

    fun setConfig(payload: JSONObject): Map<String, Any?> {
        val ed = prefs.edit()
        if (payload.has("resolution")) {
            val r = payload.optString("resolution", "720p").trim().lowercase()
            if (r in listOf("720p", "1080p")) {
                ed.putString("resolution", r)
            }
        }
        if (payload.has("bitrate")) {
            ed.putInt("bitrate", payload.optInt("bitrate", 6_000_000).coerceIn(1_000_000, 20_000_000))
        }
        if (payload.has("max_duration_s")) {
            ed.putInt("max_duration_s", payload.optInt("max_duration_s", 300).coerceIn(5, 3600))
        }
        ed.apply()
        return getConfig()
    }

    // ── Status ────────────────────────────────────────────────────────────────

    fun status(): Map<String, Any?> {
        val durationMs = if (recordingState == "recording") System.currentTimeMillis() - recordingStartMs else null
        return mapOf(
            "status" to "ok",
            "recording" to (recordingState == "recording"),
            "recording_state" to recordingState,
            "recording_duration_ms" to durationMs,
            "recording_codec" to if (recordingState == "recording") recordingCodec else null,
            "last_error" to lastError
        )
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    fun startRecording(
        outPath: String?,
        maxDurationS: Int?,
        resolution: String?,
        bitrate: Int?
    ): Map<String, Any?> {
        synchronized(lock) {
            if (recordingState == "recording") {
                return mapOf("status" to "error", "error" to "already_recording")
            }

            // Request MediaProjection token (blocks until user responds)
            val proj = ScreenCaptureActivity.requestProjection(context, timeoutS = 45)
                ?: return mapOf("status" to "error", "error" to "projection_denied",
                    "detail" to "User denied screen capture or request timed out")

            val res = (resolution ?: prefs.getString("resolution", "720p") ?: "720p").trim().lowercase()
            val br = (bitrate ?: prefs.getInt("bitrate", 6_000_000)).coerceIn(1_000_000, 20_000_000)
            val maxS = (maxDurationS ?: prefs.getInt("max_duration_s", 300)).coerceIn(5, 3600)

            val metrics = getDisplayMetrics()
            val density = metrics.densityDpi
            val (videoW, videoH) = resolveResolution(res, metrics)

            val recDir = File(userRoot, "recordings/screen")
            recDir.mkdirs()
            val fileName = outPath?.trim()?.ifBlank { null }
                ?: "screen_${System.currentTimeMillis()}.mp4"
            val outFile = if (fileName.startsWith("/")) File(fileName) else File(recDir, fileName)
            outFile.parentFile?.mkdirs()

            val codec = resolveCodec()
            val videoEncoder = if (codec == "h265") MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            return try {
                mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setVideoEncoder(videoEncoder)
                mr.setVideoSize(videoW, videoH)
                mr.setVideoFrameRate(30)
                mr.setVideoEncodingBitRate(br)
                mr.setMaxDuration(maxS * 1000)
                mr.setOutputFile(outFile.absolutePath)
                mr.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        Log.i(TAG, "Max duration reached, auto-stopping")
                        executor.execute { stopRecording() }
                    }
                }
                mr.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what extra=$extra")
                    lastError = "recorder_error_$what"
                    executor.execute { stopRecording() }
                }
                mr.prepare()

                val vd = proj.createVirtualDisplay(
                    "ScreenRecord",
                    videoW, videoH, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    mr.surface,
                    null, null
                )

                mr.start()

                recorder = mr
                projection = proj
                virtualDisplay = vd
                recordingFile = outFile
                recordingStartMs = System.currentTimeMillis()
                recordingState = "recording"
                recordingCodec = codec
                lastError = null

                val relPath = outFile.absolutePath.removePrefix(userRoot.absolutePath).trimStart('/')
                mapOf(
                    "status" to "ok",
                    "state" to "recording",
                    "rel_path" to relPath,
                    "resolution" to "${videoW}x${videoH}",
                    "codec" to codec,
                    "bitrate" to br,
                    "max_duration_s" to maxS,
                    "container" to "mp4"
                )
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                mr.runCatching { release() }
                proj.runCatching { stop() }
                recordingState = "error"
                lastError = e.message ?: "start_failed"
                mapOf("status" to "error", "error" to "start_failed", "detail" to (e.message ?: ""))
            }
        }
    }

    fun stopRecording(): Map<String, Any?> {
        synchronized(lock) {
            val mr = recorder
            if (mr == null || recordingState != "recording") {
                return mapOf("status" to "ok", "stopped" to false, "state" to recordingState)
            }

            val durationMs = System.currentTimeMillis() - recordingStartMs
            return try {
                mr.stop()
                mr.release()
                virtualDisplay?.release()
                projection?.stop()
                recorder = null
                virtualDisplay = null
                projection = null
                recordingState = "idle"

                val outFile = recordingFile
                val relPath = outFile?.absolutePath?.removePrefix(userRoot.absolutePath)?.trimStart('/') ?: ""
                val sizeBytes = outFile?.length() ?: 0L
                recordingFile = null

                mapOf(
                    "status" to "ok",
                    "stopped" to true,
                    "state" to "idle",
                    "rel_path" to relPath,
                    "duration_ms" to durationMs,
                    "size_bytes" to sizeBytes,
                    "codec" to recordingCodec
                )
            } catch (e: Exception) {
                Log.e(TAG, "stopRecording failed", e)
                mr.runCatching { release() }
                virtualDisplay?.runCatching { release() }
                projection?.runCatching { stop() }
                recorder = null
                virtualDisplay = null
                projection = null
                recordingState = "error"
                lastError = e.message ?: "stop_failed"
                mapOf("status" to "error", "error" to "stop_failed", "detail" to (e.message ?: ""))
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun resolveResolution(pref: String, metrics: DisplayMetrics): Pair<Int, Int> {
        // Use device aspect ratio, scale to target height
        val targetH = if (pref == "1080p") 1080 else 720
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val aspect = screenW.toDouble() / screenH.toDouble()
        val w = ((targetH * aspect).toInt() / 2) * 2  // ensure even
        val h = (targetH / 2) * 2
        return Pair(w, h)
    }

    private fun resolveCodec(): String {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hasHevc = codecList.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
            }
        }
        return if (hasHevc) "h265" else "h264"
    }
}
