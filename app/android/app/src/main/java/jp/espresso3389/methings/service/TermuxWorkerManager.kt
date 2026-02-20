package jp.espresso3389.methings.service

import android.content.Context
import android.content.Intent
import android.util.Log
import jp.espresso3389.methings.device.AndroidPermissionWaiter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class TermuxWorkerManager(private val context: Context) {
    private val termux = TermuxManager(context)
    private val statusLock = Any()
    private var status: String = "offline"

    fun startWorker(): Boolean {
        if (!termux.isTermuxInstalled()) {
            Log.w(TAG, "Termux is not installed")
            updateStatus("offline")
            return false
        }
        if (!termux.hasRunCommandPermission()) {
            Log.w(TAG, "RUN_COMMAND permission not granted, requesting...")
            updateStatus("permission_needed")
            requestRunCommandPermission()
            return false
        }
        updateStatus("starting")
        try {
            termux.startWorker()
        } catch (e: SecurityException) {
            Log.e(TAG, "RUN_COMMAND permission denied", e)
            updateStatus("permission_needed")
            return false
        }
        startHealthProbe()
        return true
    }

    private fun requestRunCommandPermission() {
        Thread {
            val perm = TermuxManager.RUN_COMMAND_PERMISSION
            val requestId = UUID.randomUUID().toString()
            AndroidPermissionWaiter.begin(requestId, listOf(perm))
            try {
                context.sendBroadcast(Intent(LocalHttpServer.ACTION_ANDROID_PERM_REQUEST).apply {
                    setPackage(context.packageName)
                    putExtra(LocalHttpServer.EXTRA_ANDROID_PERM_REQUEST_ID, requestId)
                    putExtra(LocalHttpServer.EXTRA_ANDROID_PERM_NAMES, arrayOf(perm))
                })
                val results = AndroidPermissionWaiter.await(requestId, 60_000L)
                if (results?.get(perm) == true) {
                    Log.i(TAG, "RUN_COMMAND permission granted, starting worker")
                    startWorker()
                } else {
                    Log.w(TAG, "RUN_COMMAND permission was not granted")
                }
            } finally {
                AndroidPermissionWaiter.clear(requestId)
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        requestShutdown()
    }

    fun restartSoft(): Boolean {
        requestShutdown()
        Thread.sleep(1000)
        return startWorker()
    }

    fun requestShutdown() {
        updateStatus("stopping")
        termux.stopWorker()
        Thread {
            val deadline = System.currentTimeMillis() + 8000
            while (System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                if (!isWorkerResponding()) {
                    updateStatus("offline")
                    return@Thread
                }
            }
            // Check one last time
            if (isWorkerResponding()) {
                updateStatus("ok")
            } else {
                updateStatus("offline")
            }
        }.apply { isDaemon = true }.start()
    }

    fun getStatus(): String {
        synchronized(statusLock) {
            return status
        }
    }

    private fun isWorkerResponding(): Boolean {
        return try {
            val conn = URL("http://127.0.0.1:${TermuxManager.WORKER_PORT}/health")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun startHealthProbe() {
        Thread {
            repeat(12) { attempt ->
                try {
                    if (isWorkerResponding()) {
                        Log.i(TAG, "Worker health OK")
                        updateStatus("ok")
                        return@Thread
                    }
                    Log.w(TAG, "Health check failed (attempt ${attempt + 1})")
                } catch (ex: Exception) {
                    Log.w(TAG, "Health check failed (attempt ${attempt + 1})", ex)
                }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
            }
            Log.e(TAG, "Worker health check failed after retries")
            updateStatus("offline")
        }.apply { isDaemon = true }.start()
    }

    private fun updateStatus(status: String) {
        synchronized(statusLock) {
            this.status = status
        }
        val intent = Intent(ACTION_WORKER_HEALTH)
        intent.setPackage(context.packageName)
        intent.putExtra(EXTRA_STATUS, status)
        context.sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "TermuxWorkerManager"
        const val ACTION_WORKER_HEALTH = "jp.espresso3389.methings.WORKER_HEALTH"
        const val EXTRA_STATUS = "status"
    }
}
