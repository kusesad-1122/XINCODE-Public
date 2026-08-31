package com.xincode.app

import com.xincode.data.AppDatabase
import com.xincode.data.MemoryEntity
import com.xincode.data.MemoryExtractor
import com.xincode.provider.EmbeddingService
import com.xincode.provider.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hermes 风格的相关记忆召回 + 平凡提问门控。
 *
 * 每个非平凡的用户回合,在发请求前先用关键词 FTS 检索 + 向量余弦重排,把最相关的
 * 1-4 条记忆注入系统提示;命中次数会累计到 memories.recallCount,用于衡量记忆价值。
 *
 * 与 [CuratedMemory](冻结进系统提示的精编两文件)互补:这里是「按需召回」,
 * 不是每次全量注入。
 */
object MemoryRecall {

    /** 一次注入最多几条记忆。 */
    private const val RECALL_LIMIT = 4

    /** FTS 检索候选上限,之后交给向量重排。 */
    private const val CANDIDATE_LIMIT = 24

    /**
     * 平凡提问门控:空输入、斜杠命令、纯问候/确认词不计为需要召回记忆的回合。
     *
     * ASCII 词用单词边界锚定(避免 k8s/note/hindsight 被误判),中文词直接按整词收尾;
     * 允许尾随标点/表情符号。
     */
    private val TRIVIAL_RE = Regex(
        """^(?:(?:yes|no|ok|okay|sure|thanks|thank you|y|n|yep|nope|yeah|nah|hi|hey|hello|yo|sup|continue|go ahead|do it|proceed|got it|cool|nice|great|done|next|lgtm|k)\b|好|好的|收到|明白|了解|没问题|可以|当然|没错|是的|对|嗯|哦|谢谢|感谢|你好|您好|哈喽|嗨|继续|知道了)[\s!?.:;，。！？~…()\[\]{}<>*&^%$#@!+=`\u00a0]*$""",
        RegexOption.IGNORE_CASE
    )

    fun isTrivialPrompt(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/")) return true
        return TRIVIAL_RE.matches(t)
    }

    /**
     * 按查询向量对候选记忆做余弦重排:有 embedding 的按相似度降序,
     * 没有 embedding 的保持 FTS 顺序追加在后。query 为空时原样返回。
     */
    fun rankMemoriesByEmbedding(
        query: FloatArray?,
        candidates: List<MemoryEntity>
    ): List<MemoryEntity> {
        if (query == null || candidates.isEmpty()) return candidates
        val scored = candidates.mapNotNull { m ->
            val emb = m.embedding?.let { runCatching { EmbeddingService.bytesToFloatArray(it) }.getOrNull() }
            if (emb == null) null else m to cosine(query, emb)
        }
        val ranked = scored.sortedByDescending { it.second }.map { it.first }
        val embeddedIds = ranked.map { it.id }.toHashSet()
        return ranked + candidates.filter { it.id !in embeddedIds }
    }

    /** 组装注入系统提示的记忆块;没有命中返回空串。 */
    fun buildRecallBlock(memories: List<MemoryEntity>, limit: Int = RECALL_LIMIT): String {
        val hits = memories.take(limit).filter { it.content.isNotBlank() }
        if (hits.isEmpty()) return ""
        return buildString {
            append("## 本条消息相关的过往记忆(用户可能希望你先想起这些)\n")
            for (m in hits) {
                val head = m.content.replace("\n", " ").trim().take(180)
                append("- ").append(m.title.take(60)).append(": ").append(head).append("\n")
            }
        }.trimEnd()
    }

    /**
     * 为一个用户回合召回相关记忆并累计命中次数。
     * 平凡提问直接返回空;关键词检索为空时回退到最近记忆交给向量重排,保证中文查询也能命中。
     */
    suspend fun recallForQuery(
        database: AppDatabase,
        client: OpenAiClient,
        projectId: Long,
        query: String,
        limit: Int = RECALL_LIMIT
    ): String {
        if (isTrivialPrompt(query)) return ""
        val dao = database.memoryDao()
        val keywords = extractKeywords(query)
        val candidates = if (keywords.isBlank()) {
            dao.getAllByProject(projectId).take(CANDIDATE_LIMIT)
        } else {
            val hits = dao.searchByProject(keywords, projectId, CANDIDATE_LIMIT)
            if (hits.isEmpty()) dao.getAllByProject(projectId).take(CANDIDATE_LIMIT) else hits
        }
        if (candidates.isEmpty()) return ""

        val queryEmbedding = runCatching { client.embeddings(query.take(8000)) }.getOrNull()
        val ranked = rankMemoriesByEmbedding(queryEmbedding, candidates).take(limit)
        if (ranked.isEmpty()) return ""

        val now = System.currentTimeMillis()
        ranked.forEach { m ->
            runCatching { dao.bumpRecall(m.id, m.recallCount + 1, now) }
        }
        return buildRecallBlock(ranked, limit)
    }

    /** 从用户输入提取 FTS 关键词:去掉平凡词,取最多 6 个有意义的 token。 */
    private fun extractKeywords(text: String): String {
        val stop = setOf(
            "的", "了", "是", "我", "你", "他", "她", "它", "我们", "你们", "请", "帮", "一下",
            "什么", "怎么", "如何", "为什么", "吗", "呢", "吧", "啊", "the", "a", "an", "to",
            "and", "or", "for", "with", "in", "on", "at", "please", "can", "could", "do", "does"
        )
        return text.split(Regex("""[\s,，。！？!?.;；:：'\"()\[\]{}]+"""))
            .map { it.trim() }
            .filter { it.length >= 2 && it.lowercase() !in stop }
            .distinct()
            .take(6)
            .joinToString(" ")
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na <= 0.0 || nb <= 0.0) 0.0 else dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }
}

/**
 * 记忆写入串行队列。
 *
 * Room 本身不保证并发写入顺序;这里把所有「自动沉淀」的提取/写入压到单线程调度器,
 * 保证消息 N 的记忆先于 N+1 落库,并且写失败不会让调用方感知(学习闭环不该影响主流程)。
 */
object MemoryWriteQueue {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun submit(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /** 提取助手消息并落库(来源标记 assistant),按 sourceMessageId 去重。 */
    suspend fun persistAssistantMemory(
        database: AppDatabase,
        openAiClient: OpenAiClient,
        projectId: Long,
        messageId: Long,
        content: String
    ) {
        if (content.isBlank()) return
        val dao = database.memoryDao()
        if (dao.getBySourceMessageId(messageId) != null) return
        val extracted = MemoryExtractor.extract(content, messageId) ?: return
        val embedding = runCatching { openAiClient.embeddings(extracted.content.take(8000)) }.getOrNull()
        dao.upsert(
            MemoryEntity(
                title = extracted.title,
                content = extracted.content,
                tags = extracted.tags,
                sourceMessageId = extracted.sourceMessageId,
                source = "assistant",
                projectId = projectId,
                embedding = embedding?.let { EmbeddingService.floatArrayToBytes(it) }
            )
        )
    }

    /** 提取用户偏好/资料并落库(来源标记 user)。 */
    suspend fun persistUserFact(
        database: AppDatabase,
        projectId: Long,
        messageId: Long,
        text: String
    ) {
        val dao = database.memoryDao()
        if (dao.getBySourceMessageId(messageId) != null) return
        val extracted = MemoryExtractor.extractUserFact(text, messageId) ?: return
        dao.upsert(
            MemoryEntity(
                title = extracted.title,
                content = extracted.content,
                tags = extracted.tags,
                sourceMessageId = extracted.sourceMessageId,
                source = "user",
                projectId = projectId
            )
        )
    }
}
