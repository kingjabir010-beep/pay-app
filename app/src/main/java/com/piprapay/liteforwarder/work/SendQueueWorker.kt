package com.piprapay.liteforwarder.work

import android.content.Context
import androidx.work.*
import com.piprapay.liteforwarder.Prefs
import com.piprapay.liteforwarder.db.AppDatabase
import com.piprapay.liteforwarder.db.QueuedSms
import com.piprapay.liteforwarder.net.ApiClient
import java.util.concurrent.TimeUnit

// Drains every PENDING/FAILED row to the server. Nothing is ever
// dropped on failure -- a row just keeps its PENDING/FAILED status and
// is picked up again next time this worker runs (network-restored
// trigger, periodic backup trigger, or app-open trigger). This is what
// makes "offline now, delivered later" work: the phone can be in
// airplane mode for hours or days and every SMS captured during that
// time is still sitting safely in the local database, waiting.
class SendQueueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val baseUrl = Prefs.getBaseUrl(ctx)
        val token = Prefs.getDeviceToken(ctx)

        if (baseUrl.isBlank() || token.isNullOrBlank()) {
            // Not paired yet -- nothing we can do until the user finishes
            // setup. Retry later rather than failing permanently.
            return Result.retry()
        }

        val dao = AppDatabase.get(ctx).queuedSmsDao()
        val pending = dao.getUnsent()
        if (pending.isEmpty()) return Result.success()

        var anyFailure = false

        for (item in pending) {
            val isoTime = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(item.receivedAtMillis))

            val res = ApiClient.sendSms(baseUrl, token, item.sender, item.message, isoTime)

            if (res.ok) {
                item.status = QueuedSms.STATUS_SENT
                item.lastError = null
                item.provider = res.body?.optString("provider")
                item.trxId = res.body?.optString("trx_id")
                item.amount = res.body?.optString("amount")
            } else {
                item.attempts += 1
                item.status = QueuedSms.STATUS_FAILED
                item.lastError = res.error ?: "HTTP ${res.code}: ${res.body?.optString("error") ?: "unknown error"}"
                anyFailure = true
            }
            dao.update(item)
        }

        // Keep the table small: sent rows older than 7 days are pruned.
        dao.pruneOldSent(beforeMillis = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7))

        // If some sends failed (likely a network blip mid-batch), ask
        // WorkManager to retry with backoff instead of declaring success.
        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_IMMEDIATE = "send_queue_immediate"
        private const val UNIQUE_PERIODIC = "send_queue_periodic"

        /** Fire-once, run-as-soon-as-network-is-available request. Call this right after
         *  queuing a new SMS, and whenever connectivity comes back. */
        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SendQueueWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_IMMEDIATE, ExistingWorkPolicy.REPLACE, request)
        }

        /** Safety-net backup in case a network callback is ever missed (e.g. some OEMs
         *  restrict background broadcasts). 15 minutes is the shortest interval Android
         *  allows for periodic work. */
        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<SendQueueWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
