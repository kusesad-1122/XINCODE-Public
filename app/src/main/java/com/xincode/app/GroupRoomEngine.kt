package com.xincode.app

import android.util.Base64
import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.GroupMemberEntity
import com.xincode.data.GroupMessageEntity
import com.xincode.data.GroupRoomEntity
import com.xincode.data.GroupRoomSummaryEntity
import com.xincode.core.AgentState
import com.xincode.tools.WorkspaceContext
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一条消息被回复时,回复要带上的引用上下文。 */
data class GroupQuote(
    val sourceMessageId: Long,
    val sender: String,
    val content: String
)

/** [GroupRoomEngine.driveChain] 里某个成员一次发言的产物。 */
data class GroupReply(
    val content: String,
    val messageId: Long
)

/** 滚动总结 + 游标之后的原文,成员回复时只需要这两块上下文。 */
internal data class RoomContext(
    val summary: String,
    val tail: List<GroupMessageEntity>
)

/**
 * 群聊房间的运行引擎。
 *
 * 轻量模式直接发流式请求,delta 逐段写库,前端能看到打字过程;完全访问模式走成员自己的
 * 工作会话,结束后把工具事件与工作区 diff 镜像回房间,最终汇报按 runId + phase 保序。
 *
 * 防失控(跳数/总量/单人次数)与「被 @ 的一批人全部并行回复、绝不在批中间砍人」
 * 仍然是核心约束,见 [driveChain]。
 */
object GroupRoomEngine {

    private const val TAG = "XincodeGroup"

    /** 一条消息最多叫醒几个成员。@all 时也受这个上限约束。 */
    private const val MAX_TARGETS_PER_TURN = 6

    /** 一条用户发言引发的连锁里,总共最多产生多少条回复。 */
    private const val MAX_REPLIES_PER_CHAIN = 12

    /** 同一个成员在一条链里最多被叫醒几次。 */
    private const val MAX_TURNS_PER_MEMBER = 3

    /** 无上限模式下仍然保留的硬顶。 */
    private const val RUNAWAY_CEILING = 500

    /** 超过多少字符触发一次压缩(仅滚动总结关闭时的兜底)。 */
    private const val DEFAULT_COMPACT_CHARS = 24_000

    /** 压缩后保留最近多少条原始消息。 */
    private const val KEEP_RECENT = 12

    /** 流式 delta 写库的最小间隔,避免每个 token 都触发一次 Room 写入。 */
    private const val STREAM_UPDATE_INTERVAL_MS = 90L

    /** 工作区快照最多遍历多少文件,防止成员把仓库克隆进房间导致卡死。 */
    private const val WORKSPACE_SNAPSHOT_LIMIT = 2000

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 处理一条新消息:解析 @,让被点到的成员【同时】回答。
     *
     * 回复会流式写入;每条回复带被回复消息的引用快照。新消息到达时由调用方先中断旧链
     * (见 XincodeApplication.sendGroupMessage),本函数只负责当前这一条链。
     */
    suspend fun onMessage(
        database: AppDatabase,
        keystore: KeystoreProvider,
        roomId: Long,
        content: String,
        senderName: String,
        seedMessageId: Long = 0,
        onReply: (suspend () -> Unit)? = null,
        runWorkTurn: (suspend (sessionId: Long, prompt: String, workspace: String) -> String)? = null,
        ensureWorkSession: (suspend (GroupMemberEntity) -> Long)? = null,
        onSpeaking: ((String) -> Unit)? = null,
        onSummaryStatus: ((Boolean) -> Unit)? = null,
        onContextStatus: ((String, Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val dao = database.groupRoomDao()
        var members = dao.getMembers(roomId)
        if (members.isEmpty()) return@withContext
        val room = dao.getRoom(roomId)

        val allowChain = room?.allowMemberMentions ?: true
        val rawHops = room?.maxHops ?: 3
        val maxHops = if (rawHops == GroupRoomEntity.UNLIMITED_HOPS) rawHops
        else rawHops.coerceIn(1, 20)
        val fullAccess = (room?.fullAccess ?: false) &&
            runWorkTurn != null && ensureWorkSession != null

        if (fullAccess) {
            runCatching { java.io.File(workspaceOf(room)).mkdirs() }
            members = members.map { m ->
                if (m.workSessionId > 0) m
                else m.copy(workSessionId = ensureWorkSession!!(m))
            }
        }

        // 链开始前把该补的滚动总结补上;正常时 summarizeRoomIfNeeded 立刻返回。
        onSummaryStatus?.invoke(true)
        runCatching { summarizeRoomIfNeeded(database, keystore, roomId) }
        onSummaryStatus?.invoke(false)
        val roomContext = runCatching { buildRoomContext(database, roomId) }
            .getOrNull() ?: RoomContext("", emptyList())

        val seedQuote = if (seedMessageId > 0) GroupQuote(seedMessageId, senderName, content) else null
        driveChain(
            memberNames = members.map { it.displayName },
            seedContent = content,
            seedSender = senderName,
            allowChain = allowChain,
            maxHops = maxHops,
            seedQuote = seedQuote,
            onBatchSpeaking = { names -> onSpeaking?.invoke(names.joinToString("、")) }
        ) { name, replyTo ->
            val member = members.firstOrNull { it.displayName == name } ?: return@driveChain null
            if (fullAccess) {
                respondWithTools(
                    database = database,
                    roomId = roomId,
                    member = member,
                    allMembers = members,
                    room = room,
                    context = roomContext,
                    runWorkTurn = runWorkTurn!!,
                    replyTo = replyTo
                )
            } else {
                respondTo(
                    database = database,
                    keystore = keystore,
                    roomId = roomId,
                    member = member,
                    allMembers = members,
                    allowChain = allowChain,
                    context = roomContext,
                    replyTo = replyTo,
                    onContextStatus = onContextStatus
                )
            }
        }

        onSpeaking?.invoke("")
        onSummaryStatus?.invoke(true)
        runCatching { summarizeRoomIfNeeded(database, keystore, roomId) }
        onSummaryStatus?.invoke(false)
        if (room?.summaryEnabled != true) {
            maybeCompact(database, keystore, roomId)
        }
    }

    /**
     * 连锁调度:谁被 @ 了就让谁说话,他说的话里再 @ 谁就继续往下传,直到撞上闸门。
     *
     * 同一批被 @ 的成员【并行】发言;总量闸在【整条消息之间】检查,绝不在一批的中间砍人,
     * 因此最后一批可能略超预算,真正的硬顶是单人次数闸(成员数 × 每人最多 3 次)。
     */
    internal suspend fun driveChain(
        memberNames: List<String>,
        seedContent: String,
        seedSender: String,
        allowChain: Boolean,
        maxHops: Int,
        onBatchSpeaking: ((List<String>) -> Unit)? = null,
        seedQuote: GroupQuote? = null,
        speak: suspend (name: String, replyTo: GroupQuote?) -> GroupReply?
    ): Int {
        val unlimited = maxHops == GroupRoomEntity.UNLIMITED_HOPS

        class ChainItem(
            val text: String,
            val from: String,
            val hop: Int,
            val quote: GroupQuote?
        )
        val queue = ArrayDeque<ChainItem>()
        queue += ChainItem(seedContent, seedSender, 0, seedQuote)

        var repliesMade = 0
        val turnsByMember = mutableMapOf<String, Int>()

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val item = queue.removeFirst()
            if (!unlimited && item.hop >= maxHops) continue

            val budget = if (unlimited) RUNAWAY_CEILING else MAX_REPLIES_PER_CHAIN
            if (repliesMade >= budget) {
                Log.w(TAG, "chain stopped at $repliesMade replies (unlimited=$unlimited)")
                queue.clear()
                break
            }

            val targets = MentionRouting
                .resolveTargets(memberNames, item.text, item.from)
                .take(MAX_TARGETS_PER_TURN)
            if (targets.isEmpty()) continue

            val eligible = mutableListOf<String>()
            for (name in targets) {
                val used = turnsByMember.getOrDefault(name, 0)
                if (!unlimited && used >= MAX_TURNS_PER_MEMBER) continue
                turnsByMember[name] = used + 1
                eligible += name
            }
            if (eligible.isEmpty()) continue

            onBatchSpeaking?.invoke(eligible)

            val results = coroutineScope {
                eligible.map { name ->
                    async {
                        currentCoroutineContext().ensureActive()
                        name to speak(name, item.quote)
                    }
                }.awaitAll()
            }

            for ((name, reply) in results) {
                val r = reply ?: continue
                if (r.content.isBlank()) continue
                repliesMade++
                if (allowChain) {
                    queue += ChainItem(
                        text = r.content,
                        from = name,
                        hop = item.hop + 1,
                        quote = GroupQuote(r.messageId, name, r.content)
                    )
                }
            }
        }
        return repliesMade
    }

    /** 轻量模式:流式请求,delta 写库,空回复重试一次。 */
    private suspend fun respondTo(
        database: AppDatabase,
        keystore: KeystoreProvider,
        roomId: Long,
        member: GroupMemberEntity,
        allMembers: List<GroupMemberEntity>,
        allowChain: Boolean,
        context: RoomContext,
        replyTo: GroupQuote?,
        onContextStatus: ((String, Int) -> Unit)? = null
    ): GroupReply? {
        val dao = database.groupRoomDao()
        val cfgDao = database.providerConfigDao()
        val cfg = (if (member.providerConfigId > 0) cfgDao.getById(member.providerConfigId) else null)
            ?: cfgDao.getActive()
            ?: throw IllegalStateException("没有可用的供应商配置")
        val apiKey = runCatching {
            keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        }.getOrElse { throw IllegalStateException("无法解密 API Key") }
        val model = member.model.ifBlank { cfg.model }
        val identity = if (member.identityId > 0)
            database.identityDao().getById(member.identityId) else null

        val roster = allMembers.joinToString("、") { "@${it.displayName}" }
        val system = buildString {
            append("你现在在一个多人群聊里,你的名字是「${member.displayName}」。\n")
            append("群成员:$roster,以及用户。\n")
            append("历史里 [某某]: 开头的是别人说的话,没有前缀的是你自己说过的。\n")
            append("规则:\n")
            append("- 只说你自己要说的话,不要替别人发言,不要模拟别人的回复。\n")
            append("- 不要在开头写自己的名字,系统会自动标注是谁说的。\n")
            append("- 简洁,群聊里没人想读长篇大论。\n")
            // 防「@ 了两个人只回一个」的最后一环:被点名就必须回,即使同条消息还点了别人。
            append("- 这条消息点名了你。即使同一条消息还点了别的成员,你也必须直接回复自己的内容;")
            append("不要输出空回复,不要因为「别人会回」就不说话。\n")
            if (allowChain) {
                append("- 你可以用 @名字 点名让某个成员接着说,他会真的被叫起来回答。\n")
                append("- 正因为会真把人叫起来,只在确实需要那个人时才 @,一次一般只 @ 一个。\n")
                append("- 话题已经聊完、或者该由用户拍板时,就不要再 @ 任何人,让它停下来。\n")
            } else {
                append("- 不要 @ 别人,本房间关闭了成员之间的互相召唤,@ 了也没人会应。\n")
            }
            identity?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                append("\n你的角色设定:\n").append(it).append("\n")
            }
        }

        val tail = sortGroupMessagesCanonical(context.tail)
        val estimateChars = system.length + context.summary.length + tail.sumOf { displayText(it).length }
        onContextStatus?.invoke(member.displayName, estimateGroupTokens(estimateChars))

        val runId = groupRunId(roomId, member.displayName)
        val ts = System.currentTimeMillis()
        val messageId = dao.insertMessage(
            GroupMessageEntity(
                roomId = roomId, sender = member.displayName, content = "", model = model,
                replyToId = replyTo?.sourceMessageId ?: 0L,
                replyToSender = replyTo?.sender.orEmpty(),
                replyToContent = replyTo?.content.orEmpty(),
                runId = runId, phase = GroupMessagePhase.ASSISTANT, kind = "message",
                streaming = true, ts = ts
            )
        )

        val contentBuf = StringBuilder()
        val reasoningBuf = StringBuilder()
        var usage: JSONObject? = null
        var lastDbWrite = 0L
        suspend fun flush(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastDbWrite < STREAM_UPDATE_INTERVAL_MS) return
            lastDbWrite = now
            dao.updateMessageStream(
                messageId, roomId, contentBuf.toString(), reasoningBuf.toString(),
                streaming = true, interrupted = false,
                promptTokens = 0, completionTokens = 0, cacheHitTokens = 0, cacheMissTokens = 0
            )
        }

        try {
            for (attempt in 0..1) {
                val messages = JSONArray()
                messages.put(JSONObject().put("role", "system").put("content", system))
                messages.putAll(
                    projectHistory(tail, member.displayName, keepMentions = allowChain, summary = context.summary)
                )
                messages.put(
                    JSONObject().put(
                        "role", "user"
                    ).put(
                        "content",
                        if (attempt == 0) "(现在轮到你「${member.displayName}」说话)"
                        else "你被点名了,请直接回复你的内容,不要输出空回复,也不要替别人发言。"
                    )
                )

                val body = JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("stream", true)
                }
                val req = Request.Builder().url(chatUrl(cfg.baseUrl))
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body.toString().toRequestBody(JSON))
                    .build()

                val finished = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(150)}")
                    }
                    val source = resp.body?.source() ?: throw IllegalStateException("空响应体")
                    var sawFinish = false
                    while (source.isOpen) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        val chunk = parseGroupSseLine(line) ?: continue
                        if (chunk.content.isNotEmpty()) contentBuf.append(chunk.content)
                        if (chunk.reasoning.isNotEmpty()) reasoningBuf.append(chunk.reasoning)
                        chunk.usage?.let { usage = it }
                        if (chunk.content.isNotEmpty() || chunk.reasoning.isNotEmpty()) flush(false)
                        if (chunk.finishReason != null) {
                            sawFinish = true
                            break
                        }
                    }
                    sawFinish
                }

                val produced = contentBuf.toString().trim()
                if (produced.isNotEmpty() || !finished) break
                // 第一次空回复:带明确指令重试一次
            }

            val finalContent = contentBuf.toString().trim()
            if (finalContent.isEmpty()) {
                dao.updateMessageStream(
                    messageId, roomId, contentBuf.toString(), reasoningBuf.toString(),
                    streaming = false, interrupted = true,
                    promptTokens = 0, completionTokens = 0, cacheHitTokens = 0, cacheMissTokens = 0
                )
                return null
            }

            val pt = usage?.optLong("prompt_tokens", -1)?.let { if (it < 0) null else it.toInt() } ?: 0
            val ct = usage?.optLong("completion_tokens", -1)?.let { if (it < 0) null else it.toInt() } ?: 0
            val (hit, miss) = extractCacheTokens(usage)
            dao.updateMessageStream(
                messageId, roomId, finalContent, reasoningBuf.toString(),
                streaming = false, interrupted = false, pt, ct, hit, miss
            )
            usage?.let {
                UsageRecorder.record(
                    database = database, usage = it, sessionId = -roomId,
                    model = model, provider = cfg.name, source = "group"
                )
            }
            return GroupReply(finalContent, messageId)
        } catch (e: CancellationException) {
            dao.updateMessageStream(
                messageId, roomId, contentBuf.toString(), reasoningBuf.toString(),
                streaming = false, interrupted = true,
                promptTokens = 0, completionTokens = 0, cacheHitTokens = 0, cacheMissTokens = 0
            )
            throw e
        } catch (e: Exception) {
            val err = "✗ 回复失败:${e.message?.take(100)}"
            Log.w(TAG, "member ${member.displayName} stream failed: ${e.message}")
            dao.updateMessageStream(
                messageId, roomId, contentBuf.toString().ifBlank { err }, reasoningBuf.toString(),
                streaming = false, interrupted = true,
                promptTokens = 0, completionTokens = 0, cacheHitTokens = 0, cacheMissTokens = 0
            )
            return null
        }
    }

    /** 完全访问模式:跑工作会话,把工具事件与工作区 diff 镜像回房间,最终正文保序落库。 */
    private suspend fun respondWithTools(
        database: AppDatabase,
        roomId: Long,
        member: GroupMemberEntity,
        allMembers: List<GroupMemberEntity>,
        room: GroupRoomEntity?,
        context: RoomContext,
        runWorkTurn: suspend (sessionId: Long, prompt: String, workspace: String) -> String,
        replyTo: GroupQuote?
    ): GroupReply? {
        val dao = database.groupRoomDao()
        val identity = if (member.identityId > 0)
            database.identityDao().getById(member.identityId) else null

        val roster = allMembers.joinToString("、") { "@${it.displayName}" }
        val workspace = workspaceOf(room)
        val historyText = buildString {
            if (context.summary.isNotBlank()) {
                append("## 群聊历史总结\n").append(context.summary).append("\n\n")
            }
            if (context.tail.isNotEmpty()) {
                append("## 游标之后的对话\n")
                append(sortGroupMessagesCanonical(context.tail).joinToString("\n") { m ->
                    val who = if (m.sender.isBlank()) "用户" else m.sender
                    "$who: ${displayText(m)}"
                })
            }
        }

        val prompt = buildString {
            append("你在一个多人群聊里,你的名字是「${member.displayName}」。\n")
            append("群成员:$roster,以及用户。\n")
            identity?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                append("\n你的角色设定:\n").append(it).append("\n")
            }
            append("\n## 工作目录\n")
            append("这个团队的共享工作区是 `$workspace`。\n")
            append("要产出文档、设计稿、代码,一律写到这个目录里,别人才读得到你的东西。\n")
            append("动手之前先看看目录里已经有什么 —— 别人可能已经写过了。\n")
            append("\n## 群里到目前为止的对话\n").append(historyText.ifBlank { "(还没有可用的对话记录)" }).append("\n\n")
            append("## 现在轮到你\n")
            append("你可以用工具去查证、读写文件、动手做事。这些过程都留在你自己这条工作会话里,")
            append("群里的人看不到,所以不用怕啰嗦。\n\n")
            append("**怎么干活**:一句话说你要做什么 → 做那一步 → 说结果 → 再做下一步。\n")
            append("不要先写一大段计划再一口气把工具全调完;工具失败了就说清楚原因和你打算怎么绕。\n\n")
            append("**@ 只在最后一段有效**:你在过程中间 @ 谁都不会真的叫醒他,要找人接手就把 @ 写在汇报里。\n\n")
            append("做完之后,**最后一段话**是你要发到群里的汇报 —— 简短、只讲结论和下一步,")
            append("需要谁接着做就 @ 他的名字。不要写自己的名字当前缀。")
        }

        val sessionId = member.workSessionId
        if (sessionId <= 0) return null

        val before = workspaceSnapshot(workspace)
        val startId = runCatching {
            database.messageDao().getBySessionId(sessionId).maxOfOrNull { it.id } ?: 0L
        }.getOrDefault(0L)
        val runId = groupRunId(roomId, member.displayName)
        val ts = System.currentTimeMillis()
        val model = member.model

        val finalText = runWorkTurn(sessionId, prompt, workspace).trim()

        // 这些行都花过钱了,落库要挡住取消。
        return withContext(NonCancellable) {
            runCatching {
                database.messageDao().getBySessionId(sessionId)
                    .filter { it.id > startId && it.role == "tool" }
                    .forEach { row ->
                        val ev = parseGroupToolEvent(row.content) ?: return@forEach
                        val running = ev.status == "RUNNING"
                        val content = if (running) "🔧 ${ev.toolName}\n${ev.paramsSummary}"
                        else buildString {
                            append("${ev.toolName}")
                            ev.exitCode?.let { append(" · exit $it") }
                            if (ev.stdout.isNotBlank()) append("\n").append(ev.stdout.take(300))
                            if (ev.stderr.isNotBlank()) append("\n").append(ev.stderr.take(200))
                        }
                        dao.insertMessage(
                            GroupMessageEntity(
                                roomId = roomId, sender = member.displayName, content = content,
                                model = model,
                                runId = runId,
                                phase = if (running) GroupMessagePhase.TOOL_CALL else GroupMessagePhase.TOOL_RESULT,
                                kind = if (running) "toolcall" else "toolresult",
                                ts = ts
                            )
                        )
                    }
            }

            runCatching {
                val diff = workspaceDiff(before, workspace)
                if (diff.isNotEmpty()) {
                    dao.insertMessage(
                        GroupMessageEntity(
                            roomId = roomId, sender = member.displayName, content = diff,
                            model = model,
                            runId = runId, phase = GroupMessagePhase.TOOL_RESULT, kind = "diff",
                            ts = ts
                        )
                    )
                }
            }

            if (finalText.isBlank()) return@withContext null
            val finalId = dao.insertMessage(
                GroupMessageEntity(
                    roomId = roomId, sender = member.displayName, content = finalText, model = model,
                    replyToId = replyTo?.sourceMessageId ?: 0L,
                    replyToSender = replyTo?.sender.orEmpty(),
                    replyToContent = replyTo?.content.orEmpty(),
                    runId = runId, phase = GroupMessagePhase.ASSISTANT, kind = "message",
                    ts = ts
                )
            )
            GroupReply(finalText, finalId)
        }
    }

    /** 房间的工作目录。 */
    fun workspaceOf(room: GroupRoomEntity?): String {
        room?.workspacePath?.takeIf { it.isNotBlank() }?.let { configured ->
            val legacy = WorkspaceContext.LEGACY_SHARED_ROOT
            return if (configured == legacy || configured.startsWith("$legacy/")) {
                WorkspaceContext.defaultRoot + configured.removePrefix(legacy)
            } else configured
        }
        val safe = (room?.name ?: "room")
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace("..", "_")
            .trim()
            .ifBlank { "room" }
        return "${WorkspaceContext.workspaceRoot.trimEnd('/')}/rooms/$safe"
    }

    // ---- 滚动总结 ----

    /** 组装成员回复所需的上下文:总结 + 游标后的原文。 */
    private suspend fun buildRoomContext(database: AppDatabase, roomId: Long): RoomContext {
        val dao = database.groupRoomDao()
        val summary = dao.getSummary(roomId)
        val all = sortGroupMessagesCanonical(dao.getMessages(roomId))
        return RoomContext(
            summary = summary?.summary.orEmpty(),
            tail = groupMessagesAfterSummary(all, summary)
        )
    }

    /**
     * 每累计 summaryEveryTurns 轮用户发言,把「旧总结 + 增量消息」合并成新总结。
     * 失败只记录状态,不阻塞聊天;旧消息不删除,历史投影 = 总结 + 游标后的原文。
     */
    private suspend fun summarizeRoomIfNeeded(
        database: AppDatabase,
        keystore: KeystoreProvider,
        roomId: Long
    ) {
        val dao = database.groupRoomDao()
        val room = dao.getRoom(roomId) ?: return
        if (!room.summaryEnabled) return
        val everyTurns = room.summaryEveryTurns.coerceAtLeast(1)

        val all = sortGroupMessagesCanonical(dao.getMessages(roomId))
        val clean = cleanGroupMessagesForSummary(all)
        var summary = dao.getSummary(roomId)
        if (summary?.status == "summarizing") {
            summary = summary.copy(
                status = "failed", lastError = "上次总结被中断",
                updatedAt = System.currentTimeMillis()
            )
            dao.upsertSummary(summary)
        }

        val unsummarized = groupMessagesAfterSummary(clean, summary)
        val newTurns = unsummarized.count { it.sender.isBlank() }
        if (newTurns < everyTurns || unsummarized.isEmpty()) return

        val now = System.currentTimeMillis()
        dao.upsertSummary(
            (summary ?: GroupRoomSummaryEntity(roomId)).copy(
                status = "summarizing", updatedAt = now, lastError = ""
            )
        )

        val cfg = database.providerConfigDao().getActive() ?: return
        val apiKey = runCatching {
            keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        }.getOrNull() ?: return
        val model = room.summaryModel.ifBlank { cfg.model }

        try {
            val body = JSONObject().apply {
                put("model", model)
                put(
                    "messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", GROUP_SUMMARY_SYSTEM_PROMPT))
                        .put(
                            JSONObject().put(
                                "role", "user"
                            ).put(
                                "content",
                                buildGroupSummaryPrompt(summary?.summary.orEmpty(), unsummarized)
                            )
                        )
                )
                put("stream", false)
            }
            val req = Request.Builder().url(chatUrl(cfg.baseUrl))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            val text = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(150)}")
                resp.body?.string().orEmpty()
            }
            val json = JSONObject(text)
            val output = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content").orEmpty().trim()
            if (output.isBlank()) throw IllegalStateException("总结模型返回空内容")
            json.optJSONObject("usage")?.let {
                UsageRecorder.record(
                    database = database, usage = it, sessionId = -roomId,
                    model = model, provider = cfg.name, source = "group-summary"
                )
            }

            val anchor = unsummarized.last()
            val base = dao.getSummary(roomId) ?: GroupRoomSummaryEntity(roomId)
            dao.upsertSummary(
                base.copy(
                    summary = output,
                    summaryThroughMessageId = anchor.id,
                    summaryThroughMessageTimestamp = anchor.ts,
                    summarizedTurnCount = base.summarizedTurnCount + newTurns,
                    status = "success",
                    version = base.version + 1,
                    updatedAt = System.currentTimeMillis(),
                    lastError = ""
                )
            )
            Log.i(TAG, "room $roomId summary updated: ${unsummarized.size} msgs, $newTurns turns")
        } catch (e: Exception) {
            Log.w(TAG, "room $roomId summary failed: ${e.message}")
            val base = dao.getSummary(roomId) ?: GroupRoomSummaryEntity(roomId)
            dao.upsertSummary(
                base.copy(
                    status = "failed",
                    updatedAt = System.currentTimeMillis(),
                    lastError = e.message?.take(500).orEmpty()
                )
            )
        }
    }

    /** 滚动总结关闭时的兜底:按字符阈值一次性压缩并删除旧消息。 */
    private suspend fun maybeCompact(
        database: AppDatabase,
        keystore: KeystoreProvider,
        roomId: Long
    ) {
        val dao = database.groupRoomDao()
        val room = dao.getRoom(roomId) ?: return
        if (room.summaryEnabled) return
        val threshold = room.compactThreshold.takeIf { it > 0 } ?: DEFAULT_COMPACT_CHARS
        val all = dao.getMessages(roomId)
        val totalChars = all.sumOf { it.content.length }
        if (totalChars < threshold || all.size <= KEEP_RECENT + 2) return

        val old = all.dropLast(KEEP_RECENT)
        if (old.isEmpty()) return
        val cutoffId = old.last().id

        val cfg = database.providerConfigDao().getActive() ?: return
        val apiKey = runCatching {
            keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        }.getOrNull() ?: return

        val transcript = old.joinToString("\n") { m ->
            val who = if (m.sender.isBlank()) "用户" else m.sender
            "$who: ${displayText(m)}"
        }
        val body = JSONObject().apply {
            put("model", cfg.model)
            put(
                "messages", JSONArray().put(
                    JSONObject().put(
                        "role", "user"
                    ).put(
                        "content",
                        "把下面这段多人群聊压缩成不超过 600 字的摘要,保留每个人的立场、结论和未决问题。" +
                            "只输出摘要正文。\n\n$transcript"
                    )
                )
            )
            put("stream", false)
        }
        runCatching {
            val req = Request.Builder().url(chatUrl(cfg.baseUrl))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching
                val digest = JSONObject(resp.body?.string().orEmpty())
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.optString("content").orEmpty().trim()
                if (digest.isBlank()) return@runCatching
                dao.insertMessage(
                    GroupMessageEntity(
                        roomId = roomId, sender = "", content = digest, isDigest = true,
                        ts = old.last().ts
                    )
                )
                dao.deleteMessagesUpTo(roomId, cutoffId)
                Log.i(TAG, "room $roomId compacted: ${old.size} msgs → digest")
            }
        }
    }

    /** 把房间历史投影成【这个成员视角】的多轮对话;带总结时先注入总结对。 */
    private fun projectHistory(
        history: List<GroupMessageEntity>,
        ownName: String,
        keepMentions: Boolean,
        summary: String? = null
    ): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        if (!summary.isNullOrBlank()) {
            out += JSONObject().put("role", "user").put("content", "[群聊历史总结]\n$summary")
            out += JSONObject().put("role", "assistant").put("content", "我已了解之前的对话内容。")
        }
        for (m in history) {
            if (m.isDigest) {
                out += JSONObject().put("role", "user").put("content", "[之前的对话摘要]\n${m.content}")
                out += JSONObject().put("role", "assistant").put("content", "我已了解之前的对话内容。")
                continue
            }
            val isOwn = m.sender.equals(ownName, ignoreCase = true)
            var body = m.content
            if (m.replyToContent.isNotBlank()) {
                val quotedWho = m.replyToSender.ifBlank { "用户" }
                body = "<quote sender=\"$quotedWho\">${m.replyToContent}</quote>\n$body"
            }
            if (!keepMentions) body = stripMentions(body)
            out += if (isOwn) {
                JSONObject().put("role", "assistant").put("content", body)
            } else {
                val who = if (m.sender.isBlank()) "用户" else m.sender
                JSONObject().put("role", "user").put("content", "[$who]: $body")
            }
        }
        return out
    }

    /** 消息的展示文本:带引用时先给引用块,再给正文。 */
    private fun displayText(m: GroupMessageEntity): String {
        val quoted = if (m.replyToContent.isNotBlank()) {
            "引用[${m.replyToSender.ifBlank { "用户" }}]: ${m.replyToContent}\n"
        } else ""
        return quoted + m.content
    }

    /** 去掉正文里的 @提及,并收拢因此产生的多余空格。 */
    private fun stripMentions(content: String): String =
        content.replace(Regex("@([^\\s@]+)"), "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trimStart()

    private fun JSONArray.putAll(items: List<JSONObject>) {
        items.forEach { put(it) }
    }

    private fun extractCacheTokens(usage: JSONObject?): Pair<Int, Int> {
        if (usage == null) return 0 to 0
        val details = usage.optJSONObject("prompt_tokens_details")
        val hit = details?.optLong("cached_tokens", -1)?.let { if (it < 0) null else it.toInt() }
            ?: usage.optLong("prompt_cache_hit_tokens", -1).let { if (it < 0) null else it.toInt() }
            ?: 0
        val miss = usage.optLong("prompt_cache_miss_tokens", -1).let { if (it < 0) null else it.toInt() } ?: 0
        return hit to miss
    }

    private fun groupRunId(roomId: Long, memberName: String): String {
        val safe = memberName.replace(Regex("[^\\p{Alnum}_-]"), "_").take(24)
        return "${roomId}-$safe-${System.currentTimeMillis()}"
    }

    /** 工作区快照:相对路径 → (字节数, 最后修改时间)。 */
    private fun workspaceSnapshot(root: String): Map<String, Pair<Long, Long>> {
        val dir = java.io.File(root)
        if (!dir.isDirectory) return emptyMap()
        val out = HashMap<String, Pair<Long, Long>>()
        var count = 0
        try {
            dir.walkTopDown().forEach { f ->
                if (count >= WORKSPACE_SNAPSHOT_LIMIT) return@forEach
                if (!f.isFile) return@forEach
                val rel = f.relativeTo(dir).path.replace('\\', '/')
                if (rel.startsWith(".git/") || rel.startsWith("build/") ||
                    rel.startsWith("node_modules/") || rel.startsWith(".gradle/")) return@forEach
                count++
                out[rel] = f.length() to f.lastModified()
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 对比快照,输出工作区变更摘要(仅路径与状态,不搬运文件内容)。 */
    private fun workspaceDiff(before: Map<String, Pair<Long, Long>>, root: String): String {
        val after = workspaceSnapshot(root)
        val lines = mutableListOf<String>()
        val allKeys = (before.keys + after.keys).sorted()
        for (key in allKeys) {
            val b = before[key]
            val a = after[key]
            lines += when {
                b == null && a != null -> "A $key"
                b != null && a == null -> "D $key"
                b != null && a != null && b != a -> "M $key"
                else -> continue
            }
        }
        return if (lines.isEmpty()) "" else "工作区变更\n" + lines.joinToString("\n")
    }

    /** 与 OpenAiClient 同一套版本段规则:base_url 自带 /v1 就不再补。 */
    private fun chatUrl(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (Regex("/v\\d+[a-zA-Z0-9]*$").containsMatchIn(b)) "$b/chat/completions"
        else "$b/v1/chat/completions"
    }
}
