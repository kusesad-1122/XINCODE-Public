package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.data.CronJobEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hermes-⑦ `cronjob` —— 让模型把用户口语("每天早上提醒我…"、"2 小时后…")翻译成结构化定时任务。
 * 存 Room,由 [CronScheduler] 的 WorkManager 周期 tick 执行。
 */
class CronJobTool(private val database: AppDatabase) : Tool {

    override val name = "cronjob"
    override val description =
        "创建/管理定时任务。到点后系统会在后台以你的身份执行 prompt。" +
        "schedule 支持:'30m'/'2h'/'1d'(一次性延时)、'every 30m'/'every 2h'/'every 1d'(周期)。" +
        "action=create 需要 prompt+schedule;list 列出;remove 需要 id;deliver=notify 会发系统通知。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string"); put("enum", JSONArray(listOf("create", "list", "remove")))
            })
            put("name", JSONObject().apply { put("type", "string"); put("description", "任务名") })
            put("prompt", JSONObject().apply { put("type", "string"); put("description", "到点执行的指令") })
            put("schedule", JSONObject().apply { put("type", "string"); put("description", "如 30m / 2h / every 1d") })
            put("deliver", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("local", "notify"))) })
            put("id", JSONObject().apply { put("type", "integer"); put("description", "remove 用") })
        })
        put("required", JSONArray(listOf("action")))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.trim().orEmpty()
        val dao = database.cronJobDao()
        try {
            when (action) {
                "create" -> {
                    val prompt = params["prompt"]?.trim().orEmpty()
                    val schedule = params["schedule"]?.trim().orEmpty()
                    if (prompt.isEmpty() || schedule.isEmpty())
                        return@withContext ToolResult.Error("create 需要 prompt 和 schedule")
                    val parsed = CronScheduler.parseSchedule(schedule)
                        ?: return@withContext ToolResult.Error("无法解析 schedule '$schedule'(用 30m/2h/1d 或 every 30m)")
                    val now = System.currentTimeMillis()
                    val id = dao.upsert(CronJobEntity(
                        name = params["name"]?.trim().orEmpty().ifBlank { prompt.take(24) },
                        prompt = prompt,
                        scheduleKind = parsed.kind,
                        scheduleSpec = schedule,
                        intervalMinutes = parsed.intervalMinutes,
                        nextRunAt = now + parsed.firstDelayMs,
                        deliver = params["deliver"]?.trim()?.ifBlank { "local" } ?: "local",
                        enabled = true,
                        createdAt = now, updatedAt = now
                    ))
                    ToolResult.Success("已创建定时任务 #$id(${parsed.kind}, 首次 ${parsed.firstDelayMs / 60000} 分钟后)")
                }
                "list" -> {
                    val jobs = dao.getAll()
                    if (jobs.isEmpty()) ToolResult.Success("(无定时任务)")
                    else ToolResult.Success(jobs.joinToString("\n") { j ->
                        "#${j.id} [${if (j.enabled) "on" else "off"}] ${j.name} — ${j.scheduleSpec} — next ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(j.nextRunAt))}"
                    })
                }
                "remove" -> {
                    val id = params["id"]?.toLongOrNull() ?: return@withContext ToolResult.Error("remove 需要 id")
                    dao.deleteById(id)
                    ToolResult.Success("已删除定时任务 #$id")
                }
                else -> ToolResult.Error("未知 action: $action")
            }
        } catch (e: Exception) {
            ToolResult.Error("cronjob 失败: ${e.message}")
        }
    }
}
