package com.EntropicSoftwareLab.ClipboardShare.offgrid

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.EntropicSoftwareLab.ClipboardShare.R
import com.EntropicSoftwareLab.ClipboardShare.TransferDaemon

/**
 * Foreground service (type connectedDevice) that keeps the process alive
 * while a Wi-Fi Direct group is advertising or transferring. Without it,
 * backgrounding the app lets Android kill the process and silently destroy
 * the WifiP2pGroup.
 */
class P2pSessionService : Service() {

    companion object {
        private const val CHANNEL_ID = "P2P_SESSION_CHANNEL"
        private const val NOTIFICATION_ID = 65440

        fun start(context: Context) {
            val intent = Intent(context, P2pSessionService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, P2pSessionService::class.java))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guarantee the port-65433 transfer listener is up whenever a group is
        // advertising — the off-grid flow does not otherwise touch MainActivity/
        // ClipboardService.onCreate. Idempotent (TransferDaemon.start no-ops if running).
        TransferDaemon.start(applicationContext)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Off-Grid Transfer Session", NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.offgrid_session_notification_title))
            .setContentText(getString(R.string.offgrid_session_notification_text))
            .setOngoing(true)
            .build()

        // M-14: On API 31+/34 startForeground can throw
        // ForegroundServiceStartNotAllowedException / InvalidForegroundServiceTypeException /
        // SecurityException. An uncaught throw here crashes the process; instead log and
        // stopSelf() so the app stays alive (the off-grid group simply won't be kept alive).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("P2pSessionService", "startForeground failed; stopping service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
