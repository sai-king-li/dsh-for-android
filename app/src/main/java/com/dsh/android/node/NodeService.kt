package com.dsh.android.node

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dsh.android.DshApplication
import com.dsh.android.MainActivity
import com.dsh.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the embedded dsh server alive while the user
 * is not looking at the app.
 */
class NodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.getBooleanExtra(EXTRA_STOP, false) == true) {
            NodeManager.stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Only kick off a fresh start when the server is idle/stopped/failed —
        // never while it is already starting, installing or running (multiple
        // launch paths call this service).
        val status = NodeManager.state.value.status
        if (status == NodeManager.Status.IDLE ||
            status == NodeManager.Status.STOPPED ||
            status == NodeManager.Status.ERROR
        ) {
            scope.launch {
                NodeManager.startServer()
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // "specialUse" foreground service type: the embedded server has no
            // time limit (dataSync would be capped at 6h/day on Android 15+).
            startForeground(
                NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, NodeService::class.java).putExtra(EXTRA_STOP, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DshApplication.CHANNEL_SERVER)
            .setContentTitle(getString(R.string.notif_server_title))
            .setContentText(getString(R.string.notif_server_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notif_server_action_stop), stopIntent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val EXTRA_STOP = "extra_stop"

        fun start(context: android.content.Context) {
            val intent = Intent(context, NodeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, NodeService::class.java).putExtra(EXTRA_STOP, true))
        }
    }
}
