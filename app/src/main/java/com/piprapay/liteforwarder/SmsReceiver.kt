package com.piprapay.liteforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.room.Room
import com.piprapay.liteforwarder.db.AppDatabase
import com.piprapay.liteforwarder.db.QueuedSms
import com.piprapay.liteforwarder.work.SendQueueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// The heart of the app. Fires the instant an SMS lands, whether the
// app's UI is open, backgrounded, or fully swiped away -- Android
// delivers this broadcast regardless, as long as the app hasn't been
// force-stopped or battery-restricted by the OEM. Every matching
// message is written to the local database FIRST, before any network
// activity is attempted, so a message is captured even with zero
// signal / airplane mode / Wi-Fi off.
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!Prefs.isServiceEnabled(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages[0].originatingAddress ?: "unknown"
        val fullBody = messages.joinToString(separator = "") { it.messageBody ?: "" }
        val receivedAt = messages[0].timestampMillis

        if (!isAllowed(context, sender, fullBody)) return

        // goAsync() keeps the receiver (and the process) alive long enough
        // to finish this suspend block even though onReceive() itself is
        // not a coroutine -- required because Room's suspend insert would
        // otherwise be killed the instant onReceive() returns.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(context).queuedSmsDao()
                val parsed = SmsParser.parse(sender, fullBody)
                dao.insert(
                    QueuedSms(
                        sender = sender,
                        message = fullBody,
                        receivedAtMillis = receivedAt,
                        provider = parsed?.provider,
                        trxId = parsed?.trxId,
                        amount = parsed?.amount
                    )
                )
            } finally {
                pendingResult.finish()
            }
        }

        // Ask WorkManager to deliver it immediately if a network is up
        // right now. If there is no network, this request simply waits
        // (see ForwarderService's NetworkCallback, which re-fires it the
        // instant connectivity returns) -- nothing is lost either way.
        SendQueueWorker.enqueueImmediate(context)

        // Make sure the always-on foreground service is running so the
        // network-restored trigger keeps working even if the app's
        // activity was never opened after boot.
        ForwarderService.ensureRunning(context)
    }

    private fun isAllowed(context: Context, sender: String, message: String): Boolean {
        val allowList = Prefs.getAllowedNumbers(context)
        if (allowList.isEmpty()) {
            // "Smart mode": no explicit whitelist configured yet, so fall
            // back to auto-detecting bKash/Nagad-looking messages from any
            // sender. Recommended: set up the whitelist in Settings so
            // only your real bKash/Nagad sender IDs are ever logged.
            return SmsParser.isLikelyTransaction(sender, message)
        }
        // Strict mode: sender must match one of the allowed numbers
        // exactly, or the allowed entry must appear inside the sender ID
        // (handles short-code senders like "bKash" vs "16247").
        return allowList.any { allowed ->
            sender.equals(allowed, ignoreCase = true) ||
                sender.contains(allowed, ignoreCase = true) ||
                allowed.contains(sender, ignoreCase = true)
        }
    }
}
