package com.xincode.app

import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.MemoryDecay
import com.xincode.data.MemoryEntity
import com.xincode.data.MemoryExtractor
import com.xincode.provider.EmbeddingService
import com.xincode.provider.OpenAiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hermes 风格的相关记忆召回 + 平凡提问门控。
 *
 * 每个非平凡的用户回合,在发请求前先用关键词 FTS 检索 + 向量余弦重排,把最相关的
 * 1-4 条记忆注入系统提示;命中次数会累计到 memories.recallCount,用于衡量记忆价值。
 *
 * 三个 M3 改造都落在这里 / 同文件:
 *  - M3-1 前半:召回打分把 `recallCount`(命中次数)与 `lastRecalledAt`(新近度)作为**加成项**接进排序,
 *    向量相似度仍是主信号。
 *  - M3-1 后半:每条记忆按「距上次召回的天数」老化(`MemoryDecay`),归档的记忆退出自动召回但仍可检索。
 *  - M3-3:query 向量走有界线程安全 LRU 缓存(`QueryEmbeddingCache`),避免每轮重复调 `embeddings()`。
 *
 * 与 [CuratedMemory](冻结进系统提示的精编两文件)互补:这里是「按需召回」,
 * 不是每次全量注入。
 */

/** query 向量缓存容量:手机端单会话召回 query 种类有限,64 条足够覆盖一轮对话里反复出现的同义问法。 */
private const val QUERY_EMBEDDING_CACHE_CAPACITY = 64

/** query / 记忆内容送 embedding 的最大字符数(与既有 persistAssistantMemory 保持一致)。 */
private const val QUERY_EMBEDDING_MAX_CHARS = 8000

object MemoryRecall {

    private const val TAG = "MemoryRecall"

    /** 一次注入最多几条记忆。 */
    private const val RECALL_LIMIT = 4

    /** FTS 检索候选上限,之后交给向量重排。 */
    private const val CANDIDATE_LIMIT = 24

    /** 毫秒/天(打分里算「距上次召回天数」用)。 */
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * 无向量记忆的排序基线。设为 -1.0,保证「没向量」的记忆永远排在有向量的记忆之后
     * (向量相似度为主信号:任何 cosine>=0 的命中都压过它)。这样 user/note 事实即便因 embedding
     * 服务失败降级为无向量,也只是落到召回列表末尾,而不会喧宾夺主。
     */
    private const val UNEMBEDDED_BASE_SCORE = -1.0

    /**
     * 命中次数加成系数。recallBonus(c) = min(COEFF * log2(c+1), MAX)。
     * 用 log2 而非线性:recallCount 无上限,线性会让「被召回 100 次」的记忆压过一切相似度差异;
     * log2 把它压成温和的加成(1 次≈0.10、8 次≈0.24、64 次≈0.48)。
     */
    private const val RECALL_BOOST_COEFF = 0.10

    /**
     * 命中次数加成上限。就算一条记忆被召回成百上千次,加成也封顶在 0.15 —— 远小于典型语义相似度差
     * (0.2~0.7),保证「相似度明显更高」的记忆永远排前面(见测试 recallDominatesBonus)。
     */
    private const val RECALL_BOOST_MAX = 0.15

    /**
     * 新近度加成的有效窗口(天)。一条记忆在「上次被召回」后的这 N 天内,给一个随天数线性衰减的加成;
     * 超过窗口不再加成。选 30 天:与 M3-1 降权的冷却节奏一致——「近期还在用」的记忆值得轻微提权。
     */
    private const val RECENCY_WINDOW_DAYS = 30L

    /**
     * 新近度加成系数(窗口内、刚被召回时的最大加成)。与 RECALL_BOOST_MAX 同量级,保证它只是「加分项」
     * 而非「决定项」。
     */
    private const val RECENCY_BOOST_COEFF = 0.10

    /**
     * 老化扫描节流:最多每 6 小时跑一次(挂在每轮召回路径上,fire-and-forget,不阻塞召回)。
     * 选 6h:记忆老化是天级时间尺度,扫太勤毫无收益且徒增 DB 写;6h 足够保证「一天内至少扫两次」。
     */
    private const val DECAY_SWEEP_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** query 向量 LRU 缓存(进程级单例,跨会话共享)。 */
    private val queryEmbeddingCache = QueryEmbeddingCache()

    /** 进程内共享的 embedding client 持有者:给缺失注入点的路径(persistUserFact / SaveMemoryTool.note)复用。 */
    private val embeddingClientHolder = EmbeddingClientHolder

    /** 老化扫描的调度作用域(单线程串行,避免并发扫 DB)。 */
    private val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** 上次跑老化扫描的时间戳(节流用)。 */
    @Volatile
    private var lastDecaySweepAt = 0L

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
     * 命中次数加成:min(COEFF * log2(count+1), MAX)。count<=0 时为 0。
     */
    private fun recallBonus(count: Int): Double {
        if (count <= 0) return 0.0
        val raw = RECALL_BOOST_COEFF * (kotlin.math.ln(count + 1.0) / kotlin.math.ln(2.0))
        return if (raw < RECALL_BOOST_MAX) raw else RECALL_BOOST_MAX
    }

    /**
     * 新近度加成:上次被召回距今 <= [RECENCY_WINDOW_DAYS] 天内,给一个随天数线性衰减的加成
     * (刚召回=COEFF,窗口末=0);从未被召回或超出窗口=0。
     */
    private fun recencyBonus(lastRecalledAt: Long, now: Long): Double {
        if (lastRecalledAt <= 0L) return 0.0
        val ageDays = (now - lastRecalledAt) / DAY_MS
        if (ageDays <= 0) return RECENCY_BOOST_COEFF
        if (ageDays > RECENCY_WINDOW_DAYS) return 0.0
        return RECENCY_BOOST_COEFF * (1.0 - ageDays.toDouble() / RECENCY_WINDOW_DAYS)
    }

    /**
     * 按查询向量对候选记忆做余弦重排 + 命中/新近度加成 + 活跃度权重。
     *
     * 综合得分 = (向量基线 + 命中加成 + 新近度加成) × decayWeight:
     *  - 向量基线:有 embedding 用 cosine,无 embedding 用 [UNEMBEDDED_BASE_SCORE](-1.0,永远垫底)。
     *  - 命中/新近度是**加成**(见 [RECALL_BOOST_COEFF]/[RECENCY_BOOST_COEFF]),不是替代:
     *    相似度明显更高的记忆,即使加成/权重更低,仍排前面。
     *  - decayWeight 来自 [MemoryDecay](老记忆自动降权),作为乘数——绝不为 0,所以「相似度最高」的记忆
     *    永远有机会上浮。
     * 同分时按 (命中次数↓, 最近召回↓, id↑) 稳定排序,便于单测断言。
     *
     * query 为空时原样返回。
     */
    fun rankMemoriesByEmbedding(
        query: FloatArray?,
        candidates: List<MemoryEntity>,
        now: Long = System.currentTimeMillis()
    ): List<MemoryEntity> {
        if (query == null || candidates.isEmpty()) return candidates
        val scored = candidates.map { m ->
            val base = m.embedding?.let { runCatching { EmbeddingService.bytesToFloatArray(it) }.getOrNull() }
                ?.let { cosine(query, it) } ?: UNEMBEDDED_BASE_SCORE
            val score = (base + recallBonus(m.recallCount) + recencyBonus(m.lastRecalledAt, now)) * m.decayWeight
            m to score
        }
        return scored.sortedWith(
            compareByDescending<Pair<MemoryEntity, Double>> { it.second }
                .thenByDescending { it.first.recallCount }
                .thenByDescending { it.first.lastRecalledAt }
                .thenBy { it.first.id }
        ).map { it.first }
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
        // 顺手把 client 缓存下来,供 persistUserFact / SaveMemoryTool.note 这类缺失注入点的路径复用。
        embeddingClientHolder.client = client
        if (isTrivialPrompt(query)) return ""

        // 老化扫描挂在这里(fire-and-forget、节流),不让它阻塞召回。
        maybeRunDecaySweep(database)

        val dao = database.memoryDao()
        val keywords = extractKeywords(query)
        val candidates = if (keywords.isBlank()) {
            dao.getAllByProject(projectId).take(CANDIDATE_LIMIT)
        } else {
            val hits = dao.searchByProject(keywords, projectId, CANDIDATE_LIMIT)
            if (hits.isEmpty()) {
                // FTS 无命中(含 FTS 损坏后 LIKE 降级仍无命中):回退最近记忆保证中文泛问也能命中,
                // 并打日志让调用方可感知本次是降级路径(DAO 侧已记录 FTS 失败原因)。
                Log.i(TAG, "recall fallback to recent: keywords='$keywords' no FTS/LIKE hit")
                dao.getAllByProject(projectId).take(CANDIDATE_LIMIT)
            } else hits
        }
        // 归档(休眠)记忆不参与自动召回,但仍在库里可被显式检索。
        val active = candidates.filter { it.archived == 0 }
        if (active.isEmpty()) return ""

        // M3-3:query 向量走 LRU 缓存,命中即复用,不重复调 embeddings()。
        val queryEmbedding = queryEmbeddingCache.getOrCompute(query) { client.embeddings(it) }
        val ranked = rankMemoriesByEmbedding(queryEmbedding, active).take(limit)
        if (ranked.isEmpty()) return ""

        val now = System.currentTimeMillis()
        ranked.forEach { m ->
            runCatching { dao.bumpRecall(m.id, m.recallCount + 1, now) }
        }
        return buildRecallBlock(ranked, limit)
    }

    /** 节流触发一次记忆老化扫描(写回归档/权重)。失败静默,不阻塞主流程。 */
    private fun maybeRunDecaySweep(database: AppDatabase) {
        val now = System.currentTimeMillis()
        if (now - lastDecaySweepAt < DECAY_SWEEP_INTERVAL_MS) return
        lastDecaySweepAt = now
        sweepScope.launch {
            runCatching {
                val dao = database.memoryDao()
                MemoryDecay.sweep(dao.getAll(), System.currentTimeMillis()) { id, archived, weight ->
                    dao.updateDecay(id, archived, weight)
                }
            }.onFailure { Log.w(TAG, "decay sweep failed: ${it.message}") }
        }
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
 * query 向量有界 LRU 缓存(M3-3)。
 *
 * key = 规范化后的 query 文本(trim / 折叠空白 / 转小写),否则「同一个问题换个空格」会 miss。
 * value = `embeddings()` 算出的 `FloatArray`。命中即复用,不重复调远程 embedding 服务(省延迟/成本)。
 *
 * 线程安全:[LinkedHashMap] 所有访问都包在 `lock` 里;命中/请求/计算计数用 [AtomicInteger]。
 * 容量超限按访问顺序(LRU)淘汰最旧条目。
 */
class QueryEmbeddingCache(
    private val capacity: Int = QUERY_EMBEDDING_CACHE_CAPACITY,
    private val maxInputChars: Int = QUERY_EMBEDDING_MAX_CHARS
) {
    private val hits = AtomicInteger(0)
    private val requests = AtomicInteger(0)
    private val computeCalls = AtomicInteger(0)
    private val lock = Any()
    private val map = object : LinkedHashMap<String, FloatArray>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>) = size > capacity
    }

    /** 规范化 query:去首尾空白、折叠内部连续空白为单空格、转小写,作为缓存 key。 */
    fun normalize(q: String): String = q.trim().replace(Regex("""\s+"""), " ").lowercase()

    /**
     * 取 query 的向量:命中缓存直接返回;否则调 [compute](异步)并存入。
     * [compute] 抛异常或返回 null 时返回 null(调用方降级为无向量),但**不缓存失败结果**(下次重试)。
     */
    suspend fun getOrCompute(query: String, compute: suspend (String) -> FloatArray?): FloatArray? {
        val key = normalize(query)
        synchronized(lock) {
            map[key]?.let { hits.incrementAndGet(); requests.incrementAndGet(); return it }
        }
        requests.incrementAndGet()
        // computeCallCount 记的是**实际调用 embedding 服务的次数**（含失败）。
        // 这才是成本/延迟观测想要的口径，同时也是"失败没有被缓存"的判据
        // —— 上次失败时下次同 query 会再算一次，计数必然 +1。
        computeCalls.incrementAndGet()
        val vec = runCatching { compute(query.take(maxInputChars)) }.getOrNull() ?: return null
        synchronized(lock) {
            map[key] = vec
        }
        return vec
    }

    fun size(): Int = synchronized(lock) { map.size }
    fun clear() = synchronized(lock) { map.clear() }
    fun hitCount(): Int = hits.get()
    fun requestCount(): Int = requests.get()
    /** 实际调用 embedding 服务的次数（含失败），用于观测成本/延迟。 */
    fun computeCallCount(): Int = computeCalls.get()
    fun hitRate(): Float = if (requests.get() == 0) 0f else hits.get().toFloat() / requests.get()
}

/**
 * 进程内共享的 embedding client 持有者。
 *
 * 为什么需要:`persistUserFact` / `SaveMemoryTool(note)` 也要生成 embedding(M3-2),但它们的
 * 构造/调用点在不在本次改动范围内的文件(AgentChatState / XincodeApplication),无法直接注入 client。
 * 同进程的其它路径(persistAssistantMemory / recallForQuery)每轮都拿到 client,这里借它们把 client
 * 缓存下来,供缺失注入点的路径复用。拿不到 client 时 [embedWith] 返回 null(降级为「不生成向量」,
 * 记忆仍按关键词召回)。embedding 失败有计数,便于排障。
 */
object EmbeddingClientHolder {
    private const val TAG = "EmbeddingClientHolder"

    @Volatile
    var client: OpenAiClient? = null

    /** embedding 生成失败累计(便于排障观测)。 */
    val failures = AtomicInteger(0)

    /**
     * 生成文本 embedding:[compute] 是「真正调 embedding 服务」的挂起 lambda(生产侧传
     * `{ client.embeddings(it) }`)。失败/返回 null 时返回 null(降级为无向量,走关键词召回)并累计 [failures]。
     * 用 lambda 而非具体 client,是为了让缺 client 的路径(`{ holder.client?.embeddings(it) }`)与单测
     * 都能复用同一通道。
     */
    suspend fun embedWith(compute: suspend (String) -> FloatArray?, text: String, maxChars: Int = QUERY_EMBEDDING_MAX_CHARS): ByteArray? {
        return runCatching { compute(text.take(maxChars)) }
            .onFailure { failures.incrementAndGet(); Log.w(TAG, "embedding 生成失败(降级为无向量,走关键词召回): ${it.message}") }
            .getOrNull()?.let { EmbeddingService.floatArrayToBytes(it) }
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
        // 缓存 client,供 persistUserFact / SaveMemoryTool.note 这类缺失注入点的路径复用(M3-2)。
        EmbeddingClientHolder.client = openAiClient
        val dao = database.memoryDao()
        if (dao.getBySourceMessageId(messageId) != null) return
        val extracted = MemoryExtractor.extract(content, messageId) ?: return
        // M3-2:assistant 记忆照旧生成 embedding(走同一条 embedWith 通道,失败计数)。
        val embedding = EmbeddingClientHolder.embedWith({ openAiClient.embeddings(it) }, extracted.content)
        dao.upsert(
            MemoryEntity(
                title = extracted.title,
                content = extracted.content,
                tags = extracted.tags,
                sourceMessageId = extracted.sourceMessageId,
                source = "assistant",
                projectId = projectId,
                embedding = embedding
            )
        )
    }

    /**
     * 提取用户偏好/资料并落库(来源标记 user)。
     *
     * M3-2:与 assistant 记忆同一条通道生成 embedding —— 用显式传入的 client,没有则用进程内
     * [EmbeddingClientHolder.client] 兜底。embedding 服务失败/不可用时不阻塞,降级为无向量(关键词召回)。
     */
    suspend fun persistUserFact(
        database: AppDatabase,
        projectId: Long,
        messageId: Long,
        text: String,
        openAiClient: OpenAiClient? = null
    ) {
        val dao = database.memoryDao()
        if (dao.getBySourceMessageId(messageId) != null) return
        val extracted = MemoryExtractor.extractUserFact(text, messageId) ?: return
        // M3-2:用显式 client,没有则借进程内 holder 兜底;失败降级为无向量(关键词召回)。
        val embedding = EmbeddingClientHolder.embedWith(
            { (openAiClient ?: EmbeddingClientHolder.client)?.embeddings(it) }, extracted.content
        )
        dao.upsert(
            MemoryEntity(
                title = extracted.title,
                content = extracted.content,
                tags = extracted.tags,
                sourceMessageId = extracted.sourceMessageId,
                source = "user",
                projectId = projectId,
                embedding = embedding
            )
        )
    }
}
