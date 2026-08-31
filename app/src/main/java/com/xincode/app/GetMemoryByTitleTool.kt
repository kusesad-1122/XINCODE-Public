package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * `get_memory_by_title` —— 按精确标题取回一条记忆的全文。
 *
 * 与 `recall_memory` 的分工很重要:
 *  - recall_memory 是**检索**,给的是若干条候选的摘要,用来"想起来有这么回事"。
 *    为了不顶爆上下文,它对每条内容都做了截断。
 *  - 这个是**取全文**。当模型从检索结果里认出"就是这条"之后,需要完整内容才能干活 ——
 *    被截断的决策记录用起来是危险的,少的那半段可能正是关键约束。
 *
 * 所以典型用法是两步:先 recall_memory 找到标题,再用这个取全文。
 */
class GetMemoryByTitleTool(
    private val database: AppDatabase
) : Tool {

    override val name = "get_memory_by_title"
    override val description = "按【精确标题】取回一条记忆的完整内容(不截断)。" +
            "通常先用 recall_memory 检索出标题,再用这个取全文。标题不确定时用 recall_memory。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("title", JSONObject().apply {
                put("type", "string")
                put("description", "记忆的完整标题,必须精确匹配")
            })
        })
        put("required", JSONArray().apply { put("title") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val title = params["title"]?.trim().orEmpty()
        if (title.isBlank()) return ToolResult.Error("缺少 title 参数")

        val scopeId = com.xincode.tools.WorkspaceContext.projectId
        val memory = runCatching { database.memoryDao().getByTitleAndProject(title, scopeId) }
            .getOrElse { return ToolResult.Error("读取记忆失败: ${it.message}") }

        if (memory == null) {
            // 直接说"没找到"会让模型反复猜标题。给它检索出来的近似项,
            // 它就知道下一步该用哪个标题或者干脆改用 recall_memory。
            val near = runCatching {
                database.memoryDao().searchByProject(title, scopeId, limit = 5)
            }
                .getOrDefault(emptyList())
            return if (near.isEmpty()) {
                ToolResult.Error("没有标题为「$title」的记忆。可以用 recall_memory 按内容检索。")
            } else {
                ToolResult.Error(
                    "没有标题为「$title」的记忆。标题相近的有:\n" +
                        near.joinToString("\n") { "- ${it.title}" }
                )
            }
        }

        return ToolResult.Success(buildString {
            append("标题:${memory.title}\n")
            if (memory.tags.isNotBlank()) append("标签:${memory.tags}\n")
            append("\n").append(memory.content)
        })
    }
}
