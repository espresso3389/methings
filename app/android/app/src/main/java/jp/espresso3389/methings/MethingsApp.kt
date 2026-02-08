package jp.espresso3389.methings

import android.app.Application
import jp.espresso3389.methings.service.CaBundleUpdateWorker

class MethingsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CaBundleUpdateWorker.schedule(this)
    }
}
