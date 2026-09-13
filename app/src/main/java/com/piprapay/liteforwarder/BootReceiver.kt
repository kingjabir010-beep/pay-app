package com.piprapay.liteforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.piprapay.liteforwarder.work.SendQueueWorker

// Restarts everything after a reboot (or an app update) so the
// forwarder survives without the user ever having to reopen the app.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.isServiceEnabled(context)) return
        ForwarderService.ensureRunning(context)
        SendQueueWorker.schedulePeriodic(context)
        SendQueueWorker.enqueueImmediate(context)
    }
}
