package jp.espresso3389.methings.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import jp.espresso3389.methings.BuildConfig
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AppUpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val tracker = AppUpdateCheckTracker(applicationContext)
        tracker.markStarted()

        if (BuildConfig.DEBUG) {
            tracker.markFinished(
                status = "debug_disabled",
                hasUpdate = false,
                latestTag = "",
                detail = "Auto update check disabled for debug build"
            )
            return Result.success()
        }

        return try {
            val res = AppUpdateManager(applicationContext).checkLatestRelease()
            tracker.markFinished(
                status = "ok",
                hasUpdate = res.optBoolean("has_update", false),
                latestTag = res.optString("latest_tag", ""),
                detail = ""
            )
            Result.success()
        } catch (ex: Exception) {
            tracker.markFinished(
                status = "error",
                hasUpdate = false,
                latestTag = "",
                detail = "${ex.javaClass.simpleName}:${ex.message ?: ""}"
            )
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC = "methings.app_update_check.periodic"
        private const val UNIQUE_NOW = "methings.app_update_check.once"

        fun schedulePeriodic(
            context: Context,
            intervalMinutes: Long,
            requireCharging: Boolean,
            requireUnmetered: Boolean,
            replace: Boolean,
        ) {
            val wm = WorkManager.getInstance(context.applicationContext)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(requireCharging)
                .build()
            val req = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(
                intervalMinutes.coerceAtLeast(15L),
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                req
            )
        }

        fun runOnce(
            context: Context,
            requireCharging: Boolean,
            requireUnmetered: Boolean,
        ) {
            val wm = WorkManager.getInstance(context.applicationContext)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(requireCharging)
                .build()
            val req = OneTimeWorkRequestBuilder<AppUpdateCheckWorker>()
                .setConstraints(constraints)
                .setInputData(
                    Data.Builder()
                        .putBoolean("manual", true)
                        .build()
                )
                .build()
            wm.enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelAll(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(UNIQUE_PERIODIC)
            wm.cancelUniqueWork(UNIQUE_NOW)
        }

        fun periodicName(): String = UNIQUE_PERIODIC
        fun onceName(): String = UNIQUE_NOW
    }
}

class AppUpdateCheckTracker(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markStarted() {
        prefs.edit()
            .putLong(KEY_LAST_START_MS, System.currentTimeMillis())
            .apply()
    }

    fun markFinished(status: String, hasUpdate: Boolean, latestTag: String, detail: String) {
        prefs.edit()
            .putLong(KEY_LAST_FINISH_MS, System.currentTimeMillis())
            .putString(KEY_LAST_STATUS, status)
            .putBoolean(KEY_LAST_HAS_UPDATE, hasUpdate)
            .putString(KEY_LAST_LATEST_TAG, latestTag)
            .putString(KEY_LAST_DETAIL, detail)
            .apply()
    }

    fun saveSchedule(intervalMinutes: Long, requireCharging: Boolean, requireUnmetered: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_ENABLED, true)
            .putLong(KEY_SCHEDULE_INTERVAL_MIN, intervalMinutes.coerceAtLeast(15L))
            .putBoolean(KEY_SCHEDULE_REQUIRE_CHARGING, requireCharging)
            .putBoolean(KEY_SCHEDULE_REQUIRE_UNMETERED, requireUnmetered)
            .apply()
    }

    fun clearSchedule() {
        prefs.edit()
            .putBoolean(KEY_SCHEDULE_ENABLED, false)
            .apply()
    }

    fun snapshot(): JSONObject {
        return JSONObject()
            .put("schedule_enabled", prefs.getBoolean(KEY_SCHEDULE_ENABLED, false))
            .put("interval_minutes", prefs.getLong(KEY_SCHEDULE_INTERVAL_MIN, 360L))
            .put("require_charging", prefs.getBoolean(KEY_SCHEDULE_REQUIRE_CHARGING, false))
            .put("require_unmetered", prefs.getBoolean(KEY_SCHEDULE_REQUIRE_UNMETERED, false))
            .put("last_start_ms", prefs.getLong(KEY_LAST_START_MS, 0L))
            .put("last_finish_ms", prefs.getLong(KEY_LAST_FINISH_MS, 0L))
            .put("last_status", prefs.getString(KEY_LAST_STATUS, "") ?: "")
            .put("last_has_update", prefs.getBoolean(KEY_LAST_HAS_UPDATE, false))
            .put("last_latest_tag", prefs.getString(KEY_LAST_LATEST_TAG, "") ?: "")
            .put("last_detail", prefs.getString(KEY_LAST_DETAIL, "") ?: "")
    }

    companion object {
        private const val PREFS = "work_jobs"
        private const val KEY_SCHEDULE_ENABLED = "app_update_check.schedule.enabled"
        private const val KEY_SCHEDULE_INTERVAL_MIN = "app_update_check.schedule.interval_min"
        private const val KEY_SCHEDULE_REQUIRE_CHARGING = "app_update_check.schedule.require_charging"
        private const val KEY_SCHEDULE_REQUIRE_UNMETERED = "app_update_check.schedule.require_unmetered"

        private const val KEY_LAST_START_MS = "app_update_check.last.start_ms"
        private const val KEY_LAST_FINISH_MS = "app_update_check.last.finish_ms"
        private const val KEY_LAST_STATUS = "app_update_check.last.status"
        private const val KEY_LAST_HAS_UPDATE = "app_update_check.last.has_update"
        private const val KEY_LAST_LATEST_TAG = "app_update_check.last.latest_tag"
        private const val KEY_LAST_DETAIL = "app_update_check.last.detail"
    }
}
