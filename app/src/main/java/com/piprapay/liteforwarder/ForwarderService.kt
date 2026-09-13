package com.piprapay.liteforwarder

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.piprapay.liteforwarder.work.SendQueueWorker

// A lightweight always-on foreground service. Its only real job is to
// hold a NetworkCallback so that the instant the phone regains any
// connectivity (Wi-Fi, mobile data, even a brief signal window), the
// entire offline queue is flushed immediately -- no waiting for the
// next 15-minute periodic check. The persistent notification is the
// price Android requires for this kind of always-alive background
// behaviour on modern Android versions.
class ForwarderService : Service() {

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        // Android 10+ requires foreground services to declare their type at
        // startForeground() time too (not just in the manifest), or the OS
        // throws at runtime on apps targeting API 29+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the OS kills this service under memory pressure,
        // it restarts it automatically as soon as resources allow.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network just came back (or came up for the first time) --
                // flush whatever built up while offline, right now.
                SendQueueWorker.enqueueImmediate(applicationContext)
            }
        }
        connectivityManager?.registerNetworkCallback(request, networkCallback as ConnectivityManager.NetworkCallback)
    }

    private fun buildNotification(): Notification {
        val channelId = "piprapay_lite_forwarder"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "SMS Forwarder", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the SMS forwarder running in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("PipraPay Lite Forwarder")
            .setContentText("Watching for bKash / Nagad SMS")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 4821

        fun ensureRunning(context: Context) {
            if (!Prefs.isServiceEnabled(context)) return
            val intent = Intent(context, ForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForwarderService::class.java))
        }
    }
}
