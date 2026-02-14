package jp.espresso3389.methings.service

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject

class WorkJobManager(private val context: Context) {
    private val appContext = context.applicationContext

    fun appUpdateCheckStatus(): JSONObject {
        val tracker = AppUpdateCheckTracker(appContext)
        return JSONObject()
            .put("status", "ok")
            .put("job", "app_update_check")
            .put("periodic_unique_name", AppUpdateCheckWorker.periodicName())
            .put("one_time_unique_name", AppUpdateCheckWorker.onceName())
            .put("tracker", tracker.snapshot())
            .put("periodic_work", getUniqueWorkInfo(AppUpdateCheckWorker.periodicName()))
            .put("one_time_work", getUniqueWorkInfo(AppUpdateCheckWorker.onceName()))
    }

    fun scheduleAppUpdateCheck(
        intervalMinutes: Long,
        requireCharging: Boolean,
        requireUnmetered: Boolean,
        replace: Boolean,
    ): JSONObject {
        AppUpdateCheckWorker.schedulePeriodic(
            appContext,
            intervalMinutes = intervalMinutes,
            requireCharging = requireCharging,
            requireUnmetered = requireUnmetered,
            replace = replace,
        )
        AppUpdateCheckTracker(appContext)
            .saveSchedule(intervalMinutes, requireCharging, requireUnmetered)
        return appUpdateCheckStatus().put("scheduled", true)
    }

    fun runAppUpdateCheckOnce(
        requireCharging: Boolean,
        requireUnmetered: Boolean,
    ): JSONObject {
        AppUpdateCheckWorker.runOnce(
            appContext,
            requireCharging = requireCharging,
            requireUnmetered = requireUnmetered,
        )
        return appUpdateCheckStatus().put("run_once", true)
    }

    fun cancelAppUpdateCheck(): JSONObject {
        AppUpdateCheckWorker.cancelAll(appContext)
        AppUpdateCheckTracker(appContext).clearSchedule()
        return appUpdateCheckStatus().put("cancelled", true)
    }

    private fun getUniqueWorkInfo(name: String): JSONObject {
        return try {
            val infos = WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWork(name)
                .get()
            val items = JSONArray()
            infos.forEach { info -> items.put(workInfoToJson(info)) }
            JSONObject()
                .put("count", infos.size)
                .put("items", items)
        } catch (ex: Exception) {
            JSONObject()
                .put("count", 0)
                .put("items", JSONArray())
                .put("error", "${ex.javaClass.simpleName}:${ex.message ?: ""}")
        }
    }

    private fun workInfoToJson(info: WorkInfo): JSONObject {
        return JSONObject()
            .put("id", info.id.toString())
            .put("state", info.state.name.lowercase())
            .put("run_attempt_count", info.runAttemptCount)
            .put("tags", JSONArray(info.tags.toList()))
    }
}
