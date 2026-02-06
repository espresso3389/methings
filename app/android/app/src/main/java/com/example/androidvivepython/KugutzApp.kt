package jp.espresso3389.kugutz

import android.app.Application
import jp.espresso3389.kugutz.service.CaBundleUpdateWorker
class KugutzApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CaBundleUpdateWorker.schedule(this)
    }
}
