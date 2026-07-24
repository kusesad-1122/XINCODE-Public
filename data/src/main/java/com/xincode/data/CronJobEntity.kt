package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Hermes-⑦ 定时任务。自然语言 prompt 由模型在运行时执行;schedule 为结构化字段
 * (由 cronjob 工具把用户口语翻译成的 interval/once 规格)。WorkManager 周期 tick 跑到期任务。
 */
@Entity(tableName = "cron_jobs")
data class CronJobEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    /** 到点要让 agent 执行的自然语言指令。 */
    val prompt: String = "",
    /** "interval" | "once"。 */
    val scheduleKind: String = "interval",
    /** 原始规格文本(如 "30m"、"2h"、"1d",供展示/回溯)。 */
    val scheduleSpec: String = "",
    /** interval 模式的分钟数(>0)。 */
    val intervalMinutes: Long = 0,
    /** 下次到期的 epoch millis。 */
    val nextRunAt: Long = 0,
    val lastRunAt: Long = 0,
    val lastStatus: String = "",
    val enabled: Boolean = true,
    /** 投递目标:local(仅记录)| notify(系统通知)。 */
    val deliver: String = "local",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
