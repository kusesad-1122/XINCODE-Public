package com.xincode.app

import android.util.Log
import com.xincode.core.AgentCore
import com.xincode.core.AgentState
import com.xincode.data.AppDatabase
import com.xincode.data.MemoryEntity
import com.xincode.data.MemoryExtractor
import com.xincode.data.MessageEntity
import com.xincode.provider.EmbeddingService
import com.xincode.security.Decision
import com.xincode.security.GateCommand
import com.xincode.security.ToolConfirmResult
import com.xincode.core.ToolBlockAction
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/**
 * UI-facing chat state backed by [AgentCore] while-loop.
 * Implements [ChatStateLike] — compatible with existing ChatScreen.
 */
class AgentChatState(
    private val database: AppDatabase,
    private val agentCore: AgentCore,
    private val openAiClient: com.xincode.provider.OpenAiClient,
    /** 上下文压缩专用 client(功能模型配置 key="compact");未单独配置时行为与 openAiClient 一致。 */
    private val compactClient: com.xincode.provider.OpenAiClient = openAiClient
) : ChatStateLike {
    companion object {
        private const val TAG = "AgentChatState"
        private val logFile by lazy { java.io.File("/data/data/com.xincode.app/files/xincode_memory.log") }
        private fun fileLog(msg: String) {
            try {
                val dir = logFile.parentFile
                if (dir != null && !dir.exists()) dir.mkdirs()
                logFile.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} [AgentChat] $msg\n")
            } catch (e: Exception) {
                android.util.Log.wtf(TAG, "fileLog failed: ${e.message}")
            }
        }
    }

    private val messageDao = database.messageDao()
    private val memoryDao = database.memoryDao()

    // ---- observable state ----
    override val messages = mutableStateListOf<ChatState.MessageUi>()
    override val input = mutableStateOf("")
    override val isStreaming = mutableStateOf(false)
    override val statusLine = mutableStateOf("")

    // ---- confirmation dialog state ----
    data class ConfirmRequest(
        val toolName: String,
        val preview: String,
        val command: String = "",
        val isIrreversible: Boolean = false
    )
    /** Non-null when UI should show confirmation card. */
    val pendingConfirm = mutableStateOf<ConfirmRequest?>(null)
    /** Resolved by UI when user presses one of three buttons. */
    private var confirmDeferred: CompletableDeferred<ToolConfirmResult>? = null

    // ---- tool block tracking ----
    /** Stack of indices into [messages] for ToolCallBlock items, keyed by callIndex. */
    private val toolCallIndices = mutableMapOf<Int, Int>()

    private var activeJob: Job? = null

    // B2:本会话绑定的工作区根 + 项目 id(由 app 的 applyWorkspaceForSession 设置)。
    // scope 携带 WorkspaceThreadElement,使本会话在自己作用域里跑工具时,工作区/记忆隔离到自己的值,
    // 不被别的会话切换污染(无覆盖时回退全局)。
    @Volatile var sessionWorkspaceRoot: String = com.xincode.tools.WorkspaceContext.defaultRoot
    @Volatile var sessionProjectId: Long = 0L
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main +
            com.xincode.tools.WorkspaceThreadElement { sessionWorkspaceRoot to sessionProjectId }
    )

    // Hermes-③ 缓存纪律:记住上次注入的分层系统提示,内容不变就不重写 messages[0]。
    private var lastLayeredSystemPrompt: String? = null
    // DeepSeek 缓存优化:系统提示前缀已为哪个会话冻结(-999=未冻结);会话不变则不重建前缀。
    private var systemPromptSessionId: Long = -999L

    /** 强制下一轮重建系统提示(协作模式等开关切换后立即生效)。 */
    fun invalidateSystemPrompt() { systemPromptSessionId = -999L }

    /** Current session ID — set by XincodeApplication on switch/create. */
    var currentSessionId: Long = 1L

    var thinkingEnabled: Boolean = false
    var thinkingLevel: Int = 2

    override fun stop() {
        agentCore.stop()
        activeJob?.cancel()
        activeJob = null
    }

    /** The whole agent turn, including tool calls and final token persistence. Main-thread only. */
    internal fun activeTurnJob(): Job? = activeJob

    /** Start a normal send and return the exact turn job that owns its full lifecycle. */
    internal fun sendTracked(): Job? {
        send()
        return activeJob
    }

    /** Cancel only when [job] is still this chat's current turn. Main-thread only. */
    internal fun cancelTurn(job: Job) {
        if (activeJob === job) stop()
    }

    /** Called once after construction to bind coroutine scope and observe state. */
    fun init(scope: CoroutineScope) {
        // gap-10:自动压缩触发时复用 /compact 的非流式总结逻辑。
        agentCore.onAutoCompact = { compactContext() }
        // Observe agent state for status line + confirmation triggers
        scope.launch {
            agentCore.state.collectLatest { state ->
                statusLine.value = when (state) {
                    is AgentState.Idle -> ""
                    is AgentState.Thinking -> "思考中… (第${state.iteration}轮)"
                    is AgentState.CallingTool -> "调用工具: ${state.toolName}"
                    is AgentState.Executing -> "执行: ${state.toolName}"
                    is AgentState.Responding -> "完成"
                    is AgentState.Error -> "✗ ${state.message}"
                    is AgentState.Interrupted -> "已中断"
                    is AgentState.WaitingConfirm -> "等待确认: ${state.toolName}"
                }
                isStreaming.value = state.isBusy
            }
        }
    }

    // ---- tool block methods ----

    /** Generate a one-line summary from tool arguments JSON. */
    fun summarizeParams(toolName: String, arguments: String): String {
        return try {
            val json = org.json.JSONObject(arguments)
            when (toolName) {
                "shell_exec", "su_exec" -> json.optString("command", arguments).take(60)
                "file_read", "file_write" -> json.optString("path", arguments)
                "web_search" -> "\"${json.optString("query", arguments).take(40)}\""
                "web_fetch" -> json.optString("url", arguments)
                else -> arguments.take(50)
            }
        } catch (_: Exception) { arguments.take(50) }
    }

    /** Encode a ToolCall as JSON for Room persistence. */
    private fun toolCallToJson(tc: MessageContent.ToolCall): String {
        return org.json.JSONObject().apply {
            put("__tool_call__", true)
            put("tool_name", tc.toolName)
            put("params_summary", tc.paramsSummary)
            put("full_params", tc.fullParams)
            put("stdout", tc.stdout)
            put("stderr", tc.stderr)
            put("exit_code", tc.exitCode?.toLong() ?: org.json.JSONObject.NULL)
            put("duration_ms", tc.durationMs ?: org.json.JSONObject.NULL)
            put("status", tc.status.name)
        }.toString()
    }

    /** Rebuild a ToolCall from JSON stored in Room. */
    private fun jsonToToolCall(jsonStr: String): MessageContent.ToolCall? {
        return try {
            val j = org.json.JSONObject(jsonStr)
            if (!j.optBoolean("__tool_call__", false)) return null
            MessageContent.ToolCall(
                toolName = j.optString("tool_name", "?"),
                paramsSummary = j.optString("params_summary", ""),
                fullParams = j.optString("full_params", ""),
                stdout = j.optString("stdout", ""),
                stderr = j.optString("stderr", ""),
                exitCode = if (j.isNull("exit_code")) null else j.optInt("exit_code"),
                durationMs = if (j.isNull("duration_ms")) null else j.optLong("duration_ms"),
                status = try { ToolStatus.valueOf(j.optString("status", "RUNNING")) } catch (_: Exception) { ToolStatus.RUNNING }
            )
        } catch (_: Exception) { null }
    }

    // M4:tool 行插入的 id 期约(callIndex → Deferred<rowId>),让 DB 写全部离开 Main 线程。
    private val toolRowIdJobs = mutableMapOf<Int, kotlinx.coroutines.Deferred<Long>>()

    /** Push a new ToolCallBlock message (status=RUNNING) and record its index. Persist to Room OFF-Main. */
    fun pushToolCallBlock(toolName: String, arguments: String, callIndex: Int) {
        val summary = summarizeParams(toolName, arguments)
        val block = MessageContent.ToolCall(
            toolName = toolName,
            paramsSummary = summary,
            fullParams = arguments,
            status = ToolStatus.RUNNING
        )
        val jsonContent = toolCallToJson(block)
        val entity = MessageEntity(role = "tool", content = jsonContent, sessionId = currentSessionId, turnId = agentCore.currentTurnId)
        // M4 修复:之前在 UI 线程 runBlocking(IO) 插库,存储压力下卡顿/ANR。现改为:先用临时负 id 上屏,
        // 真正插入放到 IO;拿到真实 id 后回补(按临时 id 定位,避免 index 漂移)。
        val tempId = -System.nanoTime()
        val msg = ChatState.MessageUi(
            id = tempId,
            role = "tool",
            content = jsonContent,
            timestamp = entity.timestamp,
            turnId = agentCore.currentTurnId,
            contentBlock = block
        )
        messages.add(msg)
        toolCallIndices[callIndex] = messages.size - 1
        val insertJob = scope.async(Dispatchers.IO) { messageDao.insert(entity) }
        toolRowIdJobs[callIndex] = insertJob
        scope.launch {
            val realId = try { insertJob.await() } catch (_: Exception) { return@launch }
            val i = messages.indexOfFirst { it.id == tempId }
            if (i >= 0) messages[i] = messages[i].copy(id = realId)
        }
    }

    /** Update an existing ToolCallBlock with execution results. Update Room OFF-Main. */
    fun updateToolCallBlock(
        callIndex: Int,
        stdout: String,
        stderr: String,
        exitCode: Int?,
        durationMs: Long?,
        status: ToolStatus
    ) {
        val idx = toolCallIndices[callIndex] ?: return
        if (idx < 0 || idx >= messages.size) return
        val old = messages[idx]
        val oldBlock = old.contentBlock as? MessageContent.ToolCall ?: return
        val newBlock = oldBlock.copy(
            stdout = stdout,
            stderr = stderr,
            exitCode = exitCode,
            durationMs = durationMs,
            status = status
        )
        val newJson = toolCallToJson(newBlock)
        messages[idx] = old.copy(contentBlock = newBlock, content = newJson)
        // M4:DB 更新放 IO,先等插入拿到真实 id(通常已完成,极快)再写,不阻塞 Main。
        val insertJob = toolRowIdJobs[callIndex]
        scope.launch(Dispatchers.IO) {
            val id = try { insertJob?.await() ?: old.id } catch (_: Exception) { old.id }
            if (id > 0) messageDao.updateContent(id, newJson)
        }
    }

    private fun extractCommand(toolName: String, arguments: String): String {
        return when (toolName) {
            "shell_exec", "su_exec" -> {
                try { org.json.JSONObject(arguments).optString("command", arguments) } catch (_: Exception) { arguments }
            }
            "file_read", "file_write" -> {
                try { org.json.JSONObject(arguments).optString("path", arguments) } catch (_: Exception) { arguments }
            }
            else -> arguments
        }
    }

    /** User pressed "仅本次" on confirmation card. */
    fun approveOnceConfirmation() {
        pendingConfirm.value = null
        confirmDeferred?.complete(ToolConfirmResult.ALLOW_ONCE)
        confirmDeferred = null
    }

    /** User pressed "总是允许" on confirmation card. */
    fun approveAlwaysConfirmation() {
        pendingConfirm.value = null
        confirmDeferred?.complete(ToolConfirmResult.ALWAYS_ALLOW)
        confirmDeferred = null
    }

    /** User pressed "拒绝" on confirmation card. */
    fun denyConfirmation() {
        pendingConfirm.value = null
        confirmDeferred?.complete(ToolConfirmResult.DENY)
        confirmDeferred = null
    }

    /** Suspend until user responds to confirmation. Passed to AgentCore as confirmHandler. */
    val confirmHandler: suspend (GateCommand, String) -> ToolConfirmResult = { cmd, preview ->
        val deferred = CompletableDeferred<ToolConfirmResult>()
        confirmDeferred = deferred
        val command = extractConfirmCommand(cmd)
        pendingConfirm.value = ConfirmRequest(
            toolName = cmd.toolName,
            preview = preview,
            command = command,
            isIrreversible = cmd.reversibility == com.xincode.security.Reversibility.IRREVERSIBLE
        )
        try {
            deferred.await()
        } finally {
            pendingConfirm.value = null
            confirmDeferred = null
        }
    }

    private fun extractConfirmCommand(cmd: GateCommand): String {
        return when (cmd.toolName) {
            "shell_exec", "su_exec" -> {
                try { org.json.JSONObject(cmd.toolArgs).optString("command", cmd.toolArgs) } catch (_: Exception) { cmd.toolArgs }
            }
            "file_read", "file_write" -> {
                try { org.json.JSONObject(cmd.toolArgs).optString("path", cmd.toolArgs) } catch (_: Exception) { cmd.toolArgs }
            }
            else -> cmd.toolArgs
        }
    }

    /** Compute session token totals for display in ChatScreen's stats bar.
     *  流式进行中会叠加【本回合内存实时用量】(尚未落库),让 Σ/缓存即时反映,不等回合结束。 */
    suspend fun getSessionTokenStats(): TokenStats {
        val t = withContext(Dispatchers.IO) { messageDao.getSessionTokenTotals(currentSessionId) }
        var prompt = t.prompt ?: 0L
        var completion = t.completion ?: 0L
        var hit = t.cacheHit ?: 0L
        var miss = t.cacheMiss ?: 0L
        if (isStreaming.value) {
            prompt += agentCore.cumulativePromptTokens
            completion += agentCore.cumulativeCompletionTokens
            agentCore.lastUsage?.let { u -> val (h, m) = extractCacheTokens(u); hit += h; miss += m }
        }
        return TokenStats(prompt = prompt, cacheHit = hit, cacheMiss = miss, completion = completion)
    }

    /**
     * 从各家 usage 里解析(缓存命中, 未命中)tokens —— 兼容:
     *  DeepSeek:prompt_cache_hit_tokens / prompt_cache_miss_tokens
     *  OpenAI:prompt_tokens_details.cached_tokens(命中);未命中=prompt_tokens-命中
     *  Anthropic:cache_read_input_tokens(命中);未命中=input_tokens+cache_creation_input_tokens
     */
    private fun extractCacheTokens(u: org.json.JSONObject): Pair<Long, Long> {
        val dsHit = u.optLong("prompt_cache_hit_tokens", -1)
        val dsMiss = u.optLong("prompt_cache_miss_tokens", -1)
        if (dsHit >= 0 || dsMiss >= 0) return dsHit.coerceAtLeast(0) to dsMiss.coerceAtLeast(0)
        val details = u.optJSONObject("prompt_tokens_details")
        if (details != null) {
            val cached = details.optLong("cached_tokens", 0).coerceAtLeast(0)
            val prompt = u.optLong("prompt_tokens", 0).coerceAtLeast(0)
            if (cached > 0 || prompt > 0) return cached to (prompt - cached).coerceAtLeast(0)
        }
        val aRead = u.optLong("cache_read_input_tokens", -1)
        if (aRead >= 0) {
            val input = u.optLong("input_tokens", 0).coerceAtLeast(0)
            val create = u.optLong("cache_creation_input_tokens", 0).coerceAtLeast(0)
            return aRead.coerceAtLeast(0) to (input + create)
        }
        return 0L to 0L
    }

    /** 当前上下文占用(供输入框圆环)。usedTokens=最近一次请求的 prompt_tokens(=当前上下文实际大小),
     *  没有则用历史字符估算;windowTokens=有效上下文窗口(全局覆盖 > 供应商配置)。 */
    suspend fun getContextUsage(): ContextUsage {
        val settings = withContext(Dispatchers.IO) { try { database.globalSettingsDao().get() } catch (_: Exception) { null } }
        val provider = withContext(Dispatchers.IO) { try { database.providerConfigDao().getActive() } catch (_: Exception) { null } }
        val window = when {
            (settings?.contextWindowOverride ?: 0) > 0 -> settings!!.contextWindowOverride.toLong()
            (provider?.contextWindow ?: 0) > 0 -> provider!!.contextWindow.toLong()
            else -> 0L
        }
        val fromUsage = agentCore.lastUsage?.optInt("prompt_tokens", 0)?.toLong()?.takeIf { it > 0 }
        val used = fromUsage ?: estimateContextTokens()
        return ContextUsage(used, window)
    }

    /** 粗估当前上下文 token(≈ 字符数/4;中英混合的经验值),仅在还没有真实用量时兜底。 */
    private fun estimateContextTokens(): Long {
        var chars = 0L
        for (m in messages) chars += m.content.length
        return chars / 4
    }

    /** Delete a single message row + refresh the visible list. */
    suspend fun deleteMessage(id: Long) {
        withContext(Dispatchers.IO) { messageDao.deleteById(id) }
        val idx = messages.indexOfFirst { it.id == id }
        if (idx >= 0) messages.removeAt(idx)
    }

    /** Compact context: summarize the current session's messages into one
     *  short "context digest" message, then replace history with just that. */
    suspend fun compactContext(): Boolean {
        // M5 修复:压缩会删库+清空消息列表并重载。若在【回合进行中】(流式)触发,会删掉本回合的
        // in-flight assistant 占位行,残留 consumer 用旧 asstIdx 越界崩溃、答案写入已删行而丢失。
        // 因此仅在空闲时压缩;自动压缩若在回合中触发则跳过(留到回合结束/下次手动 /compact)。
        if (isStreaming.value) { Log.d(TAG, "compact skipped: streaming in progress"); return false }
        val text = try {
            val history = messages.joinToString("\n\n") { m ->
                val role = if (m.role == "assistant") "AI" else if (m.role == "user") "USER" else "TOOL"
                "$role: ${m.content.take(2000)}"
            }
            if (history.length < 200) return false
            // 自定义总结规则(设置→上下文压缩):有则追加到默认规则后,指导总结模型怎么压、留哪些。
            val customRule = withContext(Dispatchers.IO) {
                try { database.globalSettingsDao().get()?.customSummaryRule?.trim() } catch (_: Exception) { null }
            }
            val ruleLine = if (!customRule.isNullOrBlank()) "\n额外要求：$customRule" else ""
            val prompt = "以下是一段对话历史。请用不超过 800 字的中文总结用户目标、关键决策、未完成项，为后续会话保留上下文。只输出总结，不要开场白。$ruleLine\n\n$history"
            compactClient.chat(prompt).getOrNull()?.takeIf { it.isNotBlank() } ?: return false
        } catch (_: Exception) { return false }

        withContext(Dispatchers.IO) {
            messageDao.deleteBySessionId(currentSessionId)
            val digest = MessageEntity(role = "assistant", content = "[上下文摘要]\n$text", sessionId = currentSessionId)
            messageDao.insert(digest)
        }
        agentCore.clearHistory()
        loadHistory()
        return true
    }

    override suspend fun loadHistory() {
        val entities = withContext(Dispatchers.IO) { messageDao.getBySessionId(currentSessionId) }
        messages.clear()
        entities.forEach { msg ->
            val block = if (msg.role == "tool") jsonToToolCall(msg.content) else null
            messages.add(ChatState.MessageUi(msg.id, msg.role, msg.content, msg.timestamp, reasoning = msg.reasoning ?: "", turnId = msg.turnId, contentBlock = block))
        }
        Log.d(TAG, "Loaded ${messages.size} messages from history")
    }

    /** Load messages for a specific session (multi-session support). */
    suspend fun loadHistoryForSession(sessionId: Long) {
        val entities = withContext(Dispatchers.IO) { messageDao.getBySessionId(sessionId) }
        messages.clear()
        entities.forEach { msg ->
            val block = if (msg.role == "tool") jsonToToolCall(msg.content) else null
            messages.add(ChatState.MessageUi(msg.id, msg.role, msg.content, msg.timestamp, reasoning = msg.reasoning ?: "", turnId = msg.turnId, contentBlock = block))
        }
        Log.d(TAG, "Loaded ${messages.size} messages for session $sessionId")
    }

    // ---- send ----
    /**
     * 中途插话(不打断):AI 正在跑时发消息,不新起一轮,而是把这句作为新的用户指令注入到
     * AgentCore 当前循环的下一轮迭代顶部(Hermes-⑧ redirect)。本轮工作不被杀,模型读到后自然衔接。
     * 同时把这条插话消息上屏 + 落库 + 抽取用户短偏好,和正常发送一致。
     */
    private fun steer(text: String) {
        input.value = ""
        scope.launch {
            // turnId 必须为 0(默认):groupByTurn 对 turnId>0 的分组【只取 assistant 与 tool】,
            // 带 turnId 的 user 消息会被整组丢弃、界面上完全看不到这条插话。
            // turnId=0 走 flat 路径,像普通用户消息一样单独平铺显示。
            val userMsg = MessageEntity(role = "user", content = text, sessionId = currentSessionId)
            val userId = withContext(Dispatchers.IO) { messageDao.insert(userMsg) }
            messages.add(ChatState.MessageUi(userId, "user", text, userMsg.timestamp))
            launch(Dispatchers.IO) {
                try {
                    val uf = MemoryExtractor.extractUserFact(text, userId)
                    if (uf != null) {
                        val emb = runCatching { openAiClient.embeddings(uf.content) }.getOrNull()
                        memoryDao.upsert(
                            MemoryEntity(
                                title = uf.title, content = uf.content, tags = uf.tags,
                                sourceMessageId = uf.sourceMessageId, projectId = sessionProjectId,
                                embedding = emb?.let { EmbeddingService.floatArrayToBytes(it) }
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
            agentCore.redirect(text)
        }
    }

    override fun send() {
        val s = scope
        val text = input.value.trim()
        if (text.isEmpty()) return
        // AI 正在跑 → 中途插话(注入到当前循环,不打断);否则正常新起一轮。
        if (activeJob?.isActive == true) { steer(text); return }

        input.value = ""

        activeJob = s.launch {
            // B3 修复:把流式相关协程/通道的引用提到 try 外,保证【任何退出路径】(stop 取消/异常)
            // 都在 finally 里清理,不再泄漏残留 collector(否则下一轮两个 collector 抢同一 channel,
            // 约半数 token 被丢进已关闭的旧 channel,导致回答缺字/串字)。
            var tokenChannelRef: Channel<String>? = null
            var consumerRef: Job? = null
            var tokenCollectorRef: Job? = null
            var reasoningCollectorRef: Job? = null
            try {
                Log.d(TAG, "loop start, text=$text")

                // Identity: refresh system prompt + temperature from the session's locked identity.
                // Always queried fresh (no cache) so identity-card edits apply on the very next turn.
                withContext(Dispatchers.IO) {
                    val session = database.sessionDao().getById(currentSessionId)
                    val identity = session?.identityId?.let { database.identityDao().getById(it) }
                    // gap-11 可用技能清单 + gap-20 全局系统提示,注入分层系统提示。
                    val skills = try {
                        database.skillDao().getAll().map { it.name to it.description }
                    } catch (_: Exception) { emptyList() }
                    val settings = try { database.globalSettingsDao().get() } catch (_: Exception) { null }
                    val globalPrompt = settings?.globalSystemPrompt
                    // gap-19 结构化输出:解析全局 response_format(空/非法=不约束)。
                    agentCore.responseFormat = settings?.structuredOutputSchema
                        ?.takeIf { it.isNotBlank() }
                        ?.let { try { org.json.JSONObject(it) } catch (_: Exception) { null } }
                    // DeepSeek 缓存优化(Reasonix「boot 一次」):系统提示前缀**按会话冻结一次**,
                    // 会话期间绝不重建——即使后台复盘中途改了记忆,也留到下次会话生效,
                    // 从而让 DeepSeek 自动前缀缓存整段会话保持命中(易变态改走 turn tail)。
                    if (currentSessionId != systemPromptSessionId) {
                        val curatedUser = try { database.settingDao().get(CuratedMemory.keyFor("user")) } catch (_: Exception) { null }
                        val curatedSituation = try { database.settingDao().get(CuratedMemory.keyFor("memory")) } catch (_: Exception) { null }
                        val subAgents = try {
                            database.subAgentDao().getAll().map { it.name to it.description }
                        } catch (_: Exception) { emptyList() }
                        // 跨对话记忆摘要:把当前范围(普通对话=全局共享池 pid=0;项目内=本项目)里【其它对话】
                        // 自动沉淀的记忆要点(标题即首句)按时间倒序摘一小段进系统提示,让模型天然有"大概记忆",
                        // 不必等用户点名或靠关键词命中 recall。字数封顶,避免膨胀上下文/破坏前缀缓存稳定性。
                        val crossConvoMemory = try {
                            val pid = session?.projectId ?: 0L
                            val mems = database.memoryDao().getAllByProject(pid)
                            if (mems.isEmpty()) null else buildString {
                                var budget = 1200
                                for (m in mems.take(15)) {
                                    val line = "- " + m.title.replace("\n", " ").take(72)
                                    if (budget - line.length - 1 < 0) break
                                    append(line); append("\n"); budget -= line.length + 1
                                }
                            }.trim().ifBlank { null }
                        } catch (_: Exception) { null }
                        val layered = buildLayeredSystemPrompt(
                            identityPrompt = identity?.systemPrompt,
                            globalSystemPrompt = globalPrompt,
                            availableSkills = skills,
                            curatedUser = curatedUser,
                            curatedSituation = curatedSituation,
                            availableSubAgents = subAgents,
                            crossConvoMemory = crossConvoMemory
                        )
                        agentCore.updateSystemPrompt(layered)
                        lastLayeredSystemPrompt = layered
                        systemPromptSessionId = currentSessionId
                    }
                    agentCore.temperature = identity?.temperature ?: 1.0f
                    // gap-09 采样参数:从 identity 卡注入(null=不发)。
                    agentCore.maxTokens = identity?.maxTokens
                    agentCore.topP = identity?.topP
                    // 身份卡工具白名单:留空=不限制。每回合重设,这样编辑身份卡后下一回合就生效。
                    agentCore.toolRegistry.identityAllowlist =
                        identity?.allowedTools.orEmpty()
                            .split(',', '，')          // 中文逗号一起认,手机上很容易打成全角
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    // gap-10 上下文窗口/自动压缩阈值:全局设置覆盖 > 供应商配置。
                    val activeCfg = database.providerConfigDao().getActive()
                    val winOverride = settings?.contextWindowOverride ?: 0
                    agentCore.contextWindow = if (winOverride > 0) winOverride else (activeCfg?.contextWindow ?: 0)
                    val thOverride = settings?.autoCompactThresholdOverride ?: 0
                    agentCore.autoCompactThresholdPercent =
                        if (thOverride in 1..100) thOverride else (activeCfg?.autoCompactThresholdPercent ?: 85)
                }

                // Insert user message
                val userMsg = MessageEntity(role = "user", content = text, sessionId = currentSessionId)
                val userId = withContext(Dispatchers.IO) { messageDao.insert(userMsg) }
                messages.add(ChatState.MessageUi(userId, "user", text, userMsg.timestamp))

                // 记忆:从【用户这句话】里抓持久偏好/资料(如"我喜欢 go 语言")。用户表述常很短会被
                // 助手抽取的 MIN_LENGTH 滤掉,却最该记住。按本会话范围(普通=全局池/项目=本项目)沉淀。
                launch(Dispatchers.IO) {
                    try {
                        val uf = MemoryExtractor.extractUserFact(text, userId)
                        if (uf != null) {
                            val emb = runCatching { openAiClient.embeddings(uf.content) }.getOrNull()
                            memoryDao.upsert(
                                MemoryEntity(
                                    title = uf.title, content = uf.content, tags = uf.tags,
                                    sourceMessageId = uf.sourceMessageId, projectId = sessionProjectId,
                                    embedding = emb?.let { EmbeddingService.floatArrayToBytes(it) }
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }

                // Pre-set turnId before creating assistant placeholder:
                // runLoop 还没运行,但 assistant placeholder 必须此刻就带正确的 turnId
                val turnId = System.currentTimeMillis()
                agentCore.currentTurnId = turnId

                // Insert empty assistant placeholder
                // 交错时间线:一轮内可能产生【多段】assistant 文字(每次调用工具前的那段各自成条),
                // 因此 id/索引必须可变——工具调用发生时定稿当前段、另起一条,渲染时即可与工具结果按序交错。
                val asstMsg = MessageEntity(role = "assistant", content = "", sessionId = currentSessionId, turnId = agentCore.currentTurnId)
                var asstId = withContext(Dispatchers.IO) { messageDao.insert(asstMsg) }
                messages.add(ChatState.MessageUi(asstId, "assistant", "", asstMsg.timestamp, turnId = agentCore.currentTurnId))
                var asstIdx = messages.size - 1
                // 由 onToolBlock(agent 协程)置位、consumer(主线程)消费:跨线程只传一个布尔信号,
                // 真正的分段动作全部在主线程完成,避免并发改 messages/buffer 造成丢字或错位。
                val wantNewSegment = java.util.concurrent.atomic.AtomicBoolean(false)

                // Token consumer: 16ms frame-aligned
                val tokenChannel = Channel<String>(Channel.UNLIMITED)
                tokenChannelRef = tokenChannel
                val consumer = s.launch(Dispatchers.Main) {
                    val buffer = StringBuilder()
                    var lastDbUpdate = 0L
                    while (isActive) {
                        if (tokenChannel.isClosedForReceive) break
                        delay(16)
                        var drained = 0
                        while (true) {
                            val r = tokenChannel.tryReceive()
                            if (r.isSuccess) { buffer.append(r.getOrThrow()); drained++ }
                            else break
                        }
                        if (drained > 0 && asstIdx >= 0) {
                            val current = buffer.toString()
                            messages[asstIdx] = messages[asstIdx].copy(content = current)
                            val now = System.currentTimeMillis()
                            if (now - lastDbUpdate > 500) {
                                launch(Dispatchers.IO) { messageDao.updateContent(asstId, current) }
                                lastDbUpdate = now
                            }
                        }

                        // 交错分段:工具即将执行 → 把已产出的文字定稿为一段,后续 token 写入新的一条。
                        // 只在【已有非空文字】时才切,避免连续调工具时插入一堆空消息。
                        if (wantNewSegment.compareAndSet(true, false)) {
                            val seg = buffer.toString()
                            if (seg.isNotBlank() && asstIdx >= 0) {
                                messages[asstIdx] = messages[asstIdx].copy(content = seg)
                                val finishedId = asstId
                                launch(Dispatchers.IO) { messageDao.updateContent(finishedId, seg) }

                                val nextMsg = MessageEntity(
                                    role = "assistant", content = "",
                                    sessionId = currentSessionId, turnId = agentCore.currentTurnId
                                )
                                val nextId = withContext(Dispatchers.IO) { messageDao.insert(nextMsg) }
                                messages.add(ChatState.MessageUi(nextId, "assistant", "", nextMsg.timestamp, turnId = agentCore.currentTurnId))
                                asstIdx = messages.size - 1
                                asstId = nextId
                                buffer.setLength(0)
                                lastDbUpdate = 0L
                            }
                        }
                    }
                    // Final drain
                    while (true) {
                        val r = tokenChannel.tryReceive()
                        if (r.isSuccess) buffer.append(r.getOrThrow()) else break
                    }
                    val final = buffer.toString()
                    if (final.isNotEmpty() && asstIdx >= 0) {
                        messages[asstIdx] = messages[asstIdx].copy(content = final)
                        withContext(Dispatchers.IO) { messageDao.updateContent(asstId, final) }
                    }
                }

                consumerRef = consumer
                // Collect tokens from AgentCore → feed to channel
                val tokenCollector = s.launch {
                    var tcCnt = 0
                    // 必须用 collect 而非 collectLatest:collectLatest 在新值到达时会【取消】上一个
                    // 尚未跑完的处理,流式 token 密集到达时会真的丢字 —— 正是「回复被截断」的成因。
                    agentCore.tokenFlow.collect { token ->
                        tcCnt++
                        if (tcCnt % 10 == 0) Log.d(TAG, "content tokens: $tcCnt")
                        tokenChannel.trySend(token)
                    }
                    Log.d(TAG, "content tokens total: $tcCnt")
                }

                tokenCollectorRef = tokenCollector
                // Build per-message reasoning buffer — real-time with 500ms throttling
                val reasoningBuf = StringBuilder()
                var lastReasoningUpdate = 0L
                val reasoningCollector = s.launch {
                    // 同上:思考流也必须 collect,collectLatest 会丢片段。
                    agentCore.reasoningFlow.collect { r ->
                        reasoningBuf.append(r)
                        val now = System.currentTimeMillis()
                        if (now - lastReasoningUpdate > 500 && asstIdx >= 0) {
                            messages[asstIdx] = messages[asstIdx].copy(reasoning = reasoningBuf.toString())
                            lastReasoningUpdate = now
                        }
                    }
                }

                // Run agent loop — onToolBlock callback wires ToolBlockAction to message blocks
                agentCore.onToolBlock = { action ->
                    when (action) {
                        is ToolBlockAction.PushCall -> {
                            // 先请求分段:让此前产出的文字定稿成独立一条,工具块随后插入其下方,
                            // 从而形成「说一段 → 做一步 → 再说一段」的交错时间线。
                            wantNewSegment.set(true)
                            pushToolCallBlock(action.toolName, action.arguments, action.callIndex)
                        }
                        is ToolBlockAction.UpdateResult -> {
                            val status = when (action.status) {
                                "SUCCESS" -> ToolStatus.SUCCESS
                                "FAIL" -> ToolStatus.FAILED
                                else -> ToolStatus.DENIED
                            }
                            updateToolCallBlock(
                                callIndex = action.callIndex,
                                stdout = action.stdout,
                                stderr = action.stderr,
                                exitCode = action.exitCode,
                                durationMs = action.durationMs,
                                status = status
                            )
                        }
                    }
                }
                reasoningCollectorRef = reasoningCollector
                val agentJob = agentCore.run(text, s, thinkingEnabled, thinkingLevel)
                agentJob.join()
                Log.d(TAG, "loop end, state=${agentCore.state.value}")

                // Signal end, wait for consumer
                tokenChannel.close()
                tokenCollector.cancel()
                reasoningCollector.cancel()
                consumer.join()

                // Final persistence + attach reasoning
                if (asstIdx >= 0) {
                    val finalContent = messages[asstIdx].content
                    val finalReasoning = reasoningBuf.toString()
                    messages[asstIdx] = messages[asstIdx].copy(content = finalContent, reasoning = finalReasoning)
                    if (finalContent.isNotEmpty()) {
                        withContext(Dispatchers.IO) { messageDao.updateContent(asstId, finalContent) }
                        // Persist reasoning too
                        if (finalReasoning.isNotEmpty()) {
                            withContext(Dispatchers.IO) { messageDao.updateReasoning(asstId, finalReasoning) }
                        }
                        // Persist usage metrics (DeepSeek cache stats)
                        val usage = agentCore.lastUsage
                        if (usage != null) {
                            val pt = usage.optLong("prompt_tokens", -1).let { if (it < 0) null else it }
                            // 缓存命中/未命中:兼容 DeepSeek/OpenAI/Anthropic 三家 usage 格式。
                            val (h, m) = extractCacheTokens(usage)
                            val hit = if (h > 0 || m > 0) h else null
                            val miss = if (h > 0 || m > 0) m else null
                            val ct = usage.optLong("completion_tokens", -1).let { if (it < 0) null else it }
                            launch(Dispatchers.IO) {
                                messageDao.updateUsage(asstId, pt, hit, miss, ct)
                            }
                        }
                        Log.i(TAG, "Agent complete: ${finalContent.take(80)}...")

                        // --- Memory extraction ---
                        launch(Dispatchers.IO) {
                          try {
                            fileLog("triggering extract for msgId=$asstId contentLen=${finalContent.length}")
                            val extracted = MemoryExtractor.extract(finalContent, asstId)
                            if (extracted != null) {
                                val mem = MemoryEntity(
                                    title = extracted.title,
                                    content = extracted.content,
                                    tags = extracted.tags,
                                    sourceMessageId = extracted.sourceMessageId,
                                    // 记忆按项目隔离:普通对话(projectId=0)记忆互通;项目内对话只归本项目。
                                    // 用本会话自己的 sessionProjectId(而非全局 WorkspaceContext),避免并发/后台会话串号。
                                    projectId = sessionProjectId
                                )
                                // Generate embedding (best-effort)。embeddings() 若在无 embedding 端点的供应商上
                                // 抛异常,绝不能连累 upsert——用 runCatching 兜住,拿不到向量就纯 FTS 存。
                                val emb = runCatching { openAiClient.embeddings(mem.content.take(8000)) }.getOrNull()
                                val memWithEmb = if (emb != null) {
                                    mem.copy(embedding = EmbeddingService.floatArrayToBytes(emb))
                                } else mem
                                memoryDao.upsert(memWithEmb)
                                fileLog("SAVED memory: title='${extracted.title.take(60)}' emb=${if (emb != null) "${emb.size}d" else "NULL"}")
                                Log.i(TAG, "Memory saved: ${extracted.title.take(60)}")
                            } else {
                                fileLog("NOT SAVED: MemoryExtractor returned null")
                            }
                          } catch (e: Exception) { fileLog("extract crashed: ${e.message}") }
                        }
                    }
                }

                // On error, mark assistant bubble red
                val st = agentCore.state.value
                if (st is AgentState.Error && asstIdx >= 0) {
                    messages[asstIdx] = messages[asstIdx].copy(content = "✗ ${st.message}")
                    withContext(Dispatchers.IO) { messageDao.updateContent(asstId, "✗ ${st.message}") }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "loop error: cancelled")
            } catch (e: Exception) {
                Log.d(TAG, "loop error: ${e.message}")
                Log.e(TAG, "Exception detail", e)
            } finally {
                // B3:兜底清理——正常路径已在上面 close/cancel/join;此处对取消/异常路径再确保一次
                // (幂等:已完成的协程 cancel 为空操作,已关闭的 channel close 无副作用)。
                try { tokenChannelRef?.close() } catch (_: Exception) {}
                tokenCollectorRef?.cancel()
                reasoningCollectorRef?.cancel()
                consumerRef?.cancel()
                activeJob = null
            }
        }
    }
}
