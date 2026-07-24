package com.xincode.app

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Hermes-⑦ 定时任务调度:自然语言 schedule 解析 + WorkManager 周期 tick。
 *
 * WorkManager 最小周期 15 分钟——单一周期 worker([CronWorker])每 tick 扫描到期任务并执行,
 * 替代 Hermes 的 60s 常驻线程(手机上省电、进程被杀也能被系统唤回)。
 */
object CronScheduler {
    private const val WORK_NAME = "xincode_cron_tick"

    data class Parsed(val kind: String, val intervalMinutes: Long, val firstDelayMs: Long)

    /** 解析 "30m"/"2h"/"1d"(一次性)与 "every 30m"/"every 2h"(周期)。 */
    fun parseSchedule(spec: String): Parsed? {
        val s = spec.trim().lowercase()
        val recurring = s.startsWith("every ")
        val body = if (recurring) s.removePrefix("every ").trim() else s
        val m = Regex("^(\\d+)\\s*([mhd])$").find(body) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        val unitMin = when (m.groupValues[2]) {
            "m" -> 1L; "h" -> 60L; "d" -> 1440L; else -> return null
        }
        val minutes = n * unitMin
        if (minutes <= 0) return null
        val firstDelayMs = minutes * 60_000L
        return if (recurring) Parsed("interval", minutes, firstDelayMs)
        else Parsed("once", 0, firstDelayMs)
    }

    /** 启动/确保周期 tick 在跑(幂等)。 */
    fun ensureScheduled(context: Context) {
        val req = PeriodicWorkRequestBuilder<CronWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req
        )
    }
}
