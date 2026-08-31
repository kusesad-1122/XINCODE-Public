package com.xincode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service that shows a persistent notification while the Agent is running.
 * Started by XincodeApplication when agent becomes busy, stopped when idle.
 */
class AgentForegroundService : Service() {

    companion object {
        private const val TAG = "AgentForegroundService"
        private const val CHANNEL_ID = "xincode_agent"
        private const val NOTIFICATION_ID = 1001

        /** Start the foreground service with current agent status. */
        fun start(context: Context, status: String) {
            val intent = Intent(context, AgentForegroundService::class.java)
            intent.putExtra("status", status)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Update the notification text without restarting. */
        fun updateStatus(context: Context, status: String) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = buildNotification(context, status)
            nm.notify(NOTIFICATION_ID, notification)
        }

        /** Stop the foreground service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }

        private fun buildNotification(context: Context, status: String): Notification {
            createChannel(context)

            // Intent to open the app when notification is tapped
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent().apply { setClassName(context.packageName, "com.xincode.app.MainActivity") }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("XINCODE")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "Agent 状态",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Agent 长任务运行状态"
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(channel)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val status = intent?.getStringExtra("status") ?: "运行中…"
        Log.i(TAG, "startForeground: $status")
        val notification = buildNotification(this, status)
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }
}