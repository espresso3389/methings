package jp.espresso3389.methings.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class CaBundleUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        val mgr = CaBundleManager(applicationContext)
        mgr.ensureSeeded()

        val res = mgr.updateIfDue(force = inputData.getBoolean(KEY_FORCE, false))
        return when (res.status) {
            "updated", "not_modified", "skipped" -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC = "methings.ca_bundle.periodic"
        private const val UNIQUE_STARTUP = "methings.ca_bundle.startup"
        private const val KEY_FORCE = "force"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context.applicationContext)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Periodic: weekly. WorkManager enforces a minimum 15 minutes anyway.
            val periodic = PeriodicWorkRequestBuilder<CaBundleUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)

            // Startup: one-shot best effort.
            val startup = OneTimeWorkRequestBuilder<CaBundleUpdateWorker>()
                .setConstraints(constraints)
                .build()
            wm.enqueueUniqueWork(UNIQUE_STARTUP, ExistingWorkPolicy.KEEP, startup)
        }
    }
}
