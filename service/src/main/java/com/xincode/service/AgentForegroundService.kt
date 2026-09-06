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

        /** 通知栏“中断”按钮的 action:点即走 AgentServer.requestInterrupt() 真停链路。 */
        const val ACTION_INTERRUPT = "com.xincode.service.INTERRUPT_AGENT"
        private const val REQUEST_INTERRUPT = 2001

        /**
         * 步骤E:审批批复 action。同意/拒绝直回 AgentServer rendezvous(无需进 App);
         * Turn 在 core 侧真停着等回执,这里只是送信。
         */
        const val ACTION_APPROVE = "com.xincode.service.APPROVE"
        const val ACTION_DENY = "com.xincode.service.DENY_APPROVAL"
        const val EXTRA_APPROVAL_ID = "approval_id"
        private const val NOTIFICATION_APPROVAL_ID = 1002
        private const val REQUEST_APPROVE = 2002
        private const val REQUEST_DENY = 2003

        /**
         * 弹出审批通知(独立 ID,不顶掉状态通知)。同意/拒绝按钮各带 requestId,
         * 点后 onStartCommand 回填 rendezvous 并撤掉本通知。
         */
        fun notifyApproval(context: Context, requestId: String, toolName: String, preview: String) {
            createChannel(context)
            fun actionPending(approved: Boolean): PendingIntent {
                val intent = Intent(context, AgentForegroundService::class.java)
                    .setAction(if (approved) ACTION_APPROVE else ACTION_DENY)
                    .putExtra(EXTRA_APPROVAL_ID, requestId)
                // 同意/拒绝必须用不同 requestCode,否则 FLAG_UPDATE_CURRENT 会把两个合并成一个。
                return PendingIntent.getService(
                    context, if (approved) REQUEST_APPROVE else REQUEST_DENY, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val contentPending = launchIntent?.let {
                PendingIntent.getActivity(
                    context, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("需要审批: $toolName")
                .setContentText(preview.take(200).ifBlank { "Agent 请求执行敏感操作" })
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(contentPending)
                .addAction(android.R.drawable.ic_menu_send, "同意", actionPending(true))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "拒绝", actionPending(false))
                .setOngoing(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_APPROVAL_ID, notification)
        }

        /** 撤掉审批通知(批复/超时/任务结束时调)。 */
        fun cancelApproval(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_APPROVAL_ID)
        }

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

            // 步骤A:通知栏“中断”按钮 → 直达拥有方注册的中断入口(杀 UI 进程后依然可用)。
            val interruptIntent = Intent(context, AgentForegroundService::class.java)
                .setAction(ACTION_INTERRUPT)
            val interruptPending = PendingIntent.getService(
                context, REQUEST_INTERRUPT, interruptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return Notification.Builder(context, CHANNEL_ID)
                .setContentTitle("XINCODE")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "中断", interruptPending)
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
        // 步骤A:中断 action 不走前台展示,直接分发后返回。
        if (intent?.action == ACTION_INTERRUPT) {
            Log.i(TAG, "notification interrupt -> AgentServer.requestInterrupt()")
            AgentServer.requestInterrupt()
            return START_NOT_STICKY
        }
        // 步骤E:审批批复 action。无等待者(已超时/已裁决)也照撤通知,避免僵尸按钮。
        if (intent?.action == ACTION_APPROVE || intent?.action == ACTION_DENY) {
            val id = intent.getStringExtra(EXTRA_APPROVAL_ID)
            val approved = intent.action == ACTION_APPROVE
            if (id != null) {
                val delivered = AgentServer.resolveApproval(id, approved)
                Log.i(TAG, "notification approval id=$id approved=$approved delivered=$delivered")
            }
            cancelApproval(this)
            return START_NOT_STICKY
        }
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