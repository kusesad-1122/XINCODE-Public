package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.data.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hermes-① `save_memory` — 记忆写入工具(供「后台复盘分身」自主沉淀,也可主对话直接用)。
 *
 * 三个 target:
 *  - `user`:耐久用户画像(USER.md),写入 [CuratedMemory] → 冻结进系统提示。
 *  - `situation`:当前近况(MEMORY.md),同上。
 *  - `note`:可检索的长期记忆条目,写入 `memories` FTS 表(与 recall_memory 配套)。
 *
 * action:add / replace / remove。内容会做注入/外泄模式的极简清洗(冻结进系统提示,需保守)。
 */
class SaveMemoryTool(private val database: AppDatabase) : Tool {

    override val name = "save_memory"
    override val description =
        "把值得长期记住的信息写入记忆。target=user 记用户画像/偏好;target=situation 记当前近况;" +
        "target=note 记可检索的知识条目。action=add 新增、replace 改写、remove 删除。" +
        "只记真正持久、跨会话有用的东西——不要记一次性、环境相关或否定性的失败结论。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("target", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("user", "situation", "note")))
                put("description", "user=用户画像;situation=近况;note=可检索知识条目")
            })
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("add", "replace", "remove")))
                put("description", "add 新增 / replace 改写 / remove 删除,默认 add")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "要记的内容(add/replace 必填)")
            })
            put("match", JSONObject().apply {
                put("type", "string")
                put("description", "replace/remove 时用来定位旧条目的子串;note 时作为标题")
            })
        })
        put("required", JSONArray(listOf("target")))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val target = params["target"]?.trim().orEmpty()
        val action = params["action"]?.trim()?.ifBlank { "add" } ?: "add"
        val content = sanitize(params["content"].orEmpty())
        val match = params["match"].orEmpty()

        try {
            when (target) {
                "user", "situation" -> {
                    val t = if (target == "user") "user" else "memory"
                    val msg = when (action) {
                        "add" -> CuratedMemory.add(database, t, content)
                        "replace" -> CuratedMemory.replace(database, t, match, content)
                        "remove" -> CuratedMemory.remove(database, t, match.ifBlank { content })
                        else -> return@withContext ToolResult.Error("未知 action: $action")
                    }
                    ToolResult.Success(msg)
                }
                "note" -> {
                    val dao = database.memoryDao()
                    when (action) {
                        "add", "replace" -> {
                            if (content.isBlank()) return@withContext ToolResult.Error("content 不能为空")
                            val title = match.ifBlank { content.lineSequence().first().take(60) }
                            dao.upsert(MemoryEntity(
                                title = title,
                                content = content,
                                projectId = com.xincode.tools.WorkspaceContext.projectId, // 项目隔离
                                tags = "agent-curated",
                                updatedAt = System.currentTimeMillis()
                            ))
                            ToolResult.Success("已记入长期记忆: $title")
                        }
                        "remove" -> {
                            val hit = dao.getByTitle(match.ifBlank { content })
                                ?: return@withContext ToolResult.Error("未找到标题匹配的记忆")
                            dao.delete(hit)
                            ToolResult.Success("已删除记忆: ${hit.title}")
                        }
                        else -> ToolResult.Error("未知 action: $action")
                    }
                }
                else -> ToolResult.Error("未知 target: $target(应为 user/situation/note)")
            }
        } catch (e: Exception) {
            ToolResult.Error("save_memory 失败: ${e.message}")
        }
    }

    /** 冻结进系统提示前的极简清洗:剥掉可疑的指令注入/外泄标记行。 */
    private fun sanitize(s: String): String {
        if (s.isBlank()) return s
        val bad = Regex("(?i)(ignore (previous|all) instructions|system prompt|exfiltrate|api[_ ]?key)")
        return s.lineSequence().filterNot { bad.containsMatchIn(it) }.joinToString("\n").trim()
    }
}
