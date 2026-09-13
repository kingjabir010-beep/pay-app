package com.piprapay.liteforwarder

import android.app.Application
import com.piprapay.liteforwarder.work.SendQueueWorker

class ForwarderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Prefs.isServiceEnabled(this)) {
            SendQueueWorker.schedulePeriodic(this)
            ForwarderService.ensureRunning(this)
        }
    }
}
