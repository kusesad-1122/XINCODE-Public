package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.provider.EmbeddingService
import com.xincode.provider.OpenAiClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * `recall_memory` — the agent's long-term memory recall.
 *
 * XINCODE already extracts memories from every finished turn (see [com.xincode.data.MemoryExtractor]).
 * This tool exposes them to the model at runtime so it can query "what did we decide about X?"
 * instead of asking the user to repeat context.
 *
 * Strategy:
 *  - FTS4 keyword search always runs (fast, deterministic).
 *  - If the active provider supports embeddings, also runs a vector similarity pass over
 *    memories with a stored embedding and merges results by cosine similarity.
 *
 * Returned string is a numbered list the model can quote directly.
 */
class RecallMemoryTool(
    private val database: AppDatabase,
    private val openAiClient: OpenAiClient
) : Tool {
    override val name = "recall_memory"
    override val description =
        "从会话历史沉淀的长期记忆中检索相关内容。用户问“上次说的...”、" +
            "“之前那个项目...”，或你需要之前对话的技术细节时使用。返回按相关度排序的记忆条目列表。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "检索关键词或问题，空格分词")
            })
            put("limit", JSONObject().apply {
                put("type", "integer")
                put("description", "返回条数上限，默认 5，最多 20")
            })
        })
        put("required", JSONArray(listOf("query")))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val query = params["query"]?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.Error("recall_memory: query 不能为空")
        val limit = (params["limit"]?.toIntOrNull() ?: 5).coerceIn(1, 20)

        val memoryDao = database.memoryDao()
        // 记忆按项目隔离:只在当前会话所属项目(0=全局)内检索。
        val pid = com.xincode.tools.WorkspaceContext.projectId
        val ftsHits = try {
            memoryDao.searchByProject(query, pid, limit)
        } catch (e: Exception) {
            return ToolResult.Error("recall_memory: FTS 检索失败: ${e.message}")
        }

        // Best-effort vector rerank when the active provider supports embeddings.
        val embedded = runCatching {
            val queryVec = openAiClient.embeddings(query)
            if (queryVec == null) null
            else {
                val all = memoryDao.getAllEmbedded().filter { it.projectId == pid } // 项目隔离
                all.mapNotNull { m ->
                    val bytes = m.embedding ?: return@mapNotNull null
                    val vec = EmbeddingService.bytesToFloatArray(bytes)
                    val sim = EmbeddingService.cosineSimilarity(queryVec, vec)
                    m to sim
                }.sortedByDescending { it.second }.take(limit).map { it.first }
            }
        }.getOrNull()

        val merged = mutableListOf<com.xincode.data.MemoryEntity>()
        val seen = mutableSetOf<Long>()
        (embedded ?: emptyList()).forEach { if (seen.add(it.id)) merged.add(it) }
        ftsHits.forEach { if (seen.add(it.id)) merged.add(it) }

        // 关键词/向量都没命中时,不要直接说"没记忆"——回退成【当前范围内最近的记忆】,
        // 这样面对"你还记得我们聊过啥"这类泛问,也能给出跨对话的大概记忆(普通对话互通/项目内隔离)。
        if (merged.isEmpty()) {
            val recent = try { memoryDao.getAllByProject(pid).take(limit) } catch (_: Exception) { emptyList() }
            if (recent.isEmpty()) return ToolResult.Success("(当前范围内还没有任何沉淀记忆) 查询: $query")
            val out = buildString {
                appendLine("没有和“$query”精确匹配的记忆,但这是当前范围内最近沉淀的 ${recent.size} 条(可作大概参考):")
                appendLine()
                recent.forEachIndexed { i, m ->
                    appendLine("[${i + 1}] ${m.title}")
                    appendLine(m.content.take(300))
                    appendLine()
                }
            }.trimEnd()
            return ToolResult.Success(out)
        }

        val output = buildString {
            appendLine("找到 ${merged.size} 条记忆 (查询: $query)")
            appendLine()
            merged.take(limit).forEachIndexed { i, m ->
                appendLine("[${i + 1}] ${m.title}")
                val body = m.content.take(500)
                appendLine(body)
                if (m.content.length > 500) appendLine("... (${m.content.length - 500} 字省略)")
                if (m.tags.isNotBlank()) appendLine("标签: ${m.tags}")
                appendLine()
            }
        }.trimEnd()
        return ToolResult.Success(output)
    }
}
