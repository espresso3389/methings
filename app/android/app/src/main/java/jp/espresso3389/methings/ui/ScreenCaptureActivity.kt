package jp.espresso3389.methings.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Transparent trampoline activity that requests a MediaProjection token from the user.
 *
 * The system requires an Activity to launch the screen capture consent dialog.
 * This activity is invisible (translucent theme), launches the dialog, captures the
 * result, and finishes immediately.
 *
 * Usage: call [requestProjection] from any thread; it blocks until the user responds
 * or the timeout expires, then returns the MediaProjection or null.
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        private const val TAG = "ScreenCaptureActivity"
        private const val REQUEST_CODE = 9001

        @Volatile private var pendingLatch: CountDownLatch? = null
        @Volatile private var pendingResult: MediaProjection? = null

        /**
         * Launch the consent dialog and block until the user responds.
         * Returns the MediaProjection on success, or null on denial/timeout.
         */
        fun requestProjection(context: Context, timeoutS: Long = 60): MediaProjection? {
            pendingResult = null
            val latch = CountDownLatch(1)
            pendingLatch = latch

            val intent = Intent(context, ScreenCaptureActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            return try {
                latch.await(timeoutS, TimeUnit.SECONDS)
                pendingResult
            } catch (e: InterruptedException) {
                Log.w(TAG, "requestProjection interrupted", e)
                null
            } finally {
                pendingLatch = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mgr == null) {
            Log.e(TAG, "MediaProjectionManager unavailable")
            pendingLatch?.countDown()
            finish()
            return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                pendingResult = mgr?.getMediaProjection(resultCode, data)
            } else {
                Log.i(TAG, "User denied screen capture")
                pendingResult = null
            }
            pendingLatch?.countDown()
        }
        finish()
    }
}
