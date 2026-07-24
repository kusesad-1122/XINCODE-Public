package com.xincode.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xincode.data.AppDatabase
import kotlinx.coroutines.launch

/**
 * Hermes-⑦ 周期 tick worker:扫描到期定时任务,以隔离 AgentCore 执行其 prompt,
 * 按 deliver 投递(notify=系统通知),推进/关闭排程。
 */
class CronWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "CronWorker"
        private const val CHANNEL_ID = "xincode_cron"
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? XincodeApplication ?: return Result.success()
        val db: AppDatabase = app.database
        val now = System.currentTimeMillis()
        val due = try { db.cronJobDao().getDue(now) } catch (e: Exception) {
            Log.w(TAG, "load due failed: ${e.message}"); return Result.retry()
        }
        if (due.isEmpty()) return Result.success()

        for (job in due) {
            try {
                val output = runJob(app, job.prompt)
                if (job.deliver == "notify") notify(applicationContext, job.name.ifBlank { "定时任务" }, output.take(400))
                val next = if (job.scheduleKind == "interval" && job.intervalMinutes > 0)
                    now + job.intervalMinutes * 60_000L else 0L
                db.cronJobDao().update(job.copy(
                    lastRunAt = now,
                    lastStatus = "ok",
                    nextRunAt = next,
                    enabled = job.scheduleKind == "interval",
                    updatedAt = now
                ))
            } catch (e: Exception) {
                Log.w(TAG, "job #${job.id} failed: ${e.message}")
                db.cronJobDao().update(job.copy(lastRunAt = now, lastStatus = "err:${e.message?.take(80)}", updatedAt = now))
            }
        }
        return Result.success()
    }

    /** 以隔离 AgentCore 执行 prompt,采集其 token 输出作为结果。 */
    private suspend fun runJob(app: XincodeApplication, prompt: String): String {
        val core = app.buildIsolatedAgentCore()
        core.clearHistory()
        val buf = StringBuilder()
        kotlinx.coroutines.coroutineScope {
            val drain = launch { core.tokenFlow.collect { buf.append(it) } }
            val job = core.run(prompt, scope = this, thinkingEnabled = false, thinkingLevel = 1)
            job.join()
            drain.cancel()
        }
        return buf.toString().ifBlank { "(无输出)" }
    }

    private fun notify(ctx: Context, title: String, body: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "定时任务", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        try { nm.notify(("cron" + title.hashCode()).hashCode(), n) } catch (_: SecurityException) {}
    }
}
