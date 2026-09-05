package com.xincode.core

import android.util.Log
import com.xincode.data.StateCursorDao
import com.xincode.data.StateCursorEntity
import com.xincode.provider.AgentStreamResult
import com.xincode.provider.ApiError
import com.xincode.provider.OpenAiClient
import com.xincode.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Actions emitted by AgentCore during tool execution lifecycle.
 * Consumed by AgentChatState to push/update MessageContent blocks in the UI.
 */
sealed class ToolBlockAction {
    /** Tool call started — push a PENDING ToolCall block. */
    data class PushCall(
        val callIndex: Int,
        val toolName: String,
        val arguments: String,
        val thoughtSignature: String = ""
    ) : ToolBlockAction()

    /** Tool execution result ready — update the ToolCall block. */
    data class UpdateResult(
        val callIndex: Int,
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val durationMs: Long?,
        val status: String  // "SUCCESS" | "FAIL" | "DENIED"
    ) : ToolBlockAction()
}

/**
 * Heart of XINCODE: the Agent while-loop.
 *
 * ## Loop (per master plan)
 * 1. Build request: system prompt + history + tool schemas → send to model (stream=true)
 * 2. Stream tokens to UI via [tokenFlow]; accumulate full response (text + tool_calls)
 * 3. If tool_calls present: run through security gate → execute/deny → feed back → go to 1
 * 4. If no tool_calls: final response → done
 * 5. Failsafe: max iterations / total timeout / user interrupt → force stop
 *
 * ## Security Gate integration (feat-010)
 * Before every tool execution: classify → decide → Allow/Deny/NeedConfirm.
 * NeedConfirm triggers [WaitingConfirm] state and calls [confirmHandler] (suspend until user responds).
 * If [confirmHandler] is null, NeedConfirm → auto-deny (safe default).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentCore(
    private val openAiClient: OpenAiClient,
    val toolRegistry: ToolRegistry = ToolRegistry(),   // 身份卡白名单等需要外部按会话调整
    private val systemPrompt: String = "You are a helpful assistant. Respond in Chinese.",
    private val securityGate: SecurityGate? = null,
    private var confirmHandler: (suspend (GateCommand, String) -> ToolConfirmResult)? = null,
    private val cursorDao: StateCursorDao? = null,
    /** Current session ID. Can be updated when switching sessions (see [switchSession]). */
    private var sessionId: Long = 0L
) {
    companion object {
        private const val TAG = "AgentCore"

        /** 一个回合内允许自动续跑的最大次数(超过说明网络持续有问题,再试也是烧 token)。 */
        private const val MAX_CONSECUTIVE_TRUNCATIONS = 3

        /** 同一个工具报同样的错连续多少次就中止整轮,避免模型原地空转。 */
        private const val MAX_REPEATED_TOOL_ERRORS = 3

        // ---- P1: Tool result compaction constants ----
        private const val TURN_END_RESULT_CAP_TOKENS = 3000
        private const val CHARS_PER_TOKEN = 4
        private const val COMPACT_PREFIX_TOKENS = 1500
        private const val COMPACT_SUFFIX_TOKENS = 1000
    }

    // ---- dynamic limits (updated by power mode changes) ----
    /** Max agent loop iterations (default: effectively unlimited). */
    var maxIterations: Int = 99999
        private set
    /** Total timeout for one agent run (default: 1 hour). */
    var totalTimeoutMs: Long = 60 * 60 * 1000L
        private set

    /** Last usage metrics from the most recent API call (for Room persistence). */
    var lastUsage: org.json.JSONObject? = null
        private set

    /**
     * 连续被截断的次数。收到完整流就清零;连续超过 [MAX_CONSECUTIVE_TRUNCATIONS] 就放弃并报错,
     * 避免网络持续不好时无限续跑烧 token。
     */
    private var consecutiveTruncations = 0

    /** 上一次工具失败的「工具名|错误信息」指纹,与 [repeatedToolErrors] 一起做死循环刹车。 */
    private var lastToolErrorSignature: String? = null
    private var repeatedToolErrors = 0

    /** L3:本次 run(整轮/整任务)累计 token —— 供子智能体统计避免只算最后一次调用而少计。 */
    var cumulativePromptTokens: Long = 0L
        private set
    var cumulativeCompletionTokens: Long = 0L
        private set

    // ---- session-level whitelist (reset on app restart) ----
    private val sessionWhitelist = mutableSetOf<String>()

    // ---- tool block callback (set by AgentChatState) ----
    var onToolBlock: ((ToolBlockAction) -> Unit)? = null

    /**
     * 每次模型调用完成后回调一次(usage, sessionId),用于用量记录。
     * 由外部接到 Room;core 模块不直接写库,免得把 DAO 依赖拖进来。
     */
    var onUsageRecorded: ((org.json.JSONObject, Long) -> Unit)? = null

    // ---- tool call index counter for UI block tracking ----

    // ---- observable state ----

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    /**
     * B 方案(2026-08-14):流式 channel 每轮新建、轮末关闭。
     * 修复:①上一轮残留片段被新一轮 collector 继续消费(症状:重复思考内容/正文);
     * ②轮末 cancel 转发协程导致尾部 token 丢弃(症状:输出被截断)。
     * tokenFlow/reasoningFlow 是 getter,collector 每轮在 run() 之后取当前实例。
     */
    @Volatile
    private var tokenChannel = Channel<String>(Channel.UNLIMITED)
    @Volatile
    private var reasoningChannel = Channel<String>(Channel.UNLIMITED)
    val tokenFlow: Flow<String> get() = tokenChannel.receiveAsFlow()
    val reasoningFlow: Flow<String> get() = reasoningChannel.receiveAsFlow()

    // ---- P1: Tool result compaction ----
    /** When true, compact long tool results before sending to API (default: true). */
    var enableResultCompaction: Boolean = true

    // ---- P2: Prefix hash drift detection ----
    /** Callback invoked each time prefix hash is computed in runLoop(). */
    var onPrefixHashComputed: (suspend (hash: String, sessionId: Long) -> Unit)? = null
    private var lastKnownPrefixHash: String? = null

    // ---- 4.5 turnId: synchronously exposed to AgentChatState ----
    /** 4.5 竞态修复:同步暴露当前轮 turnId,AgentChatState 创建 message 时直接读取 */
    @Volatile
    var currentTurnId: Long = 0L
    // 不设 private set —— AgentChatState.send() 需在创建 assistant placeholder
    // 前设值(因为那时 runLoop 还没运行); AgentCore.runLoop 也会覆盖写,
    // 不存在无意的第三方写。private set 在此处弊大于利。

    // ---- internal history ----

    private val messages = mutableListOf<org.json.JSONObject>()

    init {
        // Seed history with system prompt (always first message)
        messages.add(org.json.JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })
    }

    private var currentJob: Job? = null
    private var pendingToolCallJson: String? = null
    private var pendingToolResultJson: String? = null

    // Hermes-⑧ 打断-重定向:运行中注入的新指令,在下一轮迭代顶部作为 user 消息插入。
    @Volatile
    private var pendingRedirect: String? = null

    /**
     * Hermes-⑧:运行中"打断并重定向"——不杀掉本轮,而是把 [message] 作为新的用户指令,
     * 在下一次模型调用前插入历史,让 agent 顺着新方向继续。history 保持 role 交替合法
     * (XINCODE 每轮工具结果都在同一迭代内回灌,迭代顶部注入 user 消息是安全的)。
     */
    fun redirect(message: String) {
        if (message.isBlank()) return
        pendingRedirect = message
        Log.i(TAG, "redirect queued: ${message.take(60)}")
    }

    /** Set the confirmation handler after construction (called by AgentChatState). */
    fun setConfirmHandler(handler: suspend (GateCommand, String) -> ToolConfirmResult) {
        confirmHandler = handler
    }

    // ---- cursor persistence ----

    /** Check if there is a pending cursor that can be resumed. */
    suspend fun hasPendingCursor(): Boolean {
        return cursorDao?.getBySessionId(sessionId) != null
    }

    /**
     * Restore state from a persisted cursor and continue the loop.
     * @return true if restoration succeeded and loop was resumed, false if no cursor found.
     */
    suspend fun resumeFromCursorIfNeeded(scope: CoroutineScope): Boolean {
        val cursor = cursorDao?.getBySessionId(sessionId) ?: return false
        if (cursor.state == "Idle") return false

        Log.i(TAG, "Resuming from cursor: iteration=${cursor.iteration}, state=${cursor.state}")

        // Restore messages
        try {
            val arr = JSONArray(cursor.messagesJson)
            messages.clear()
            // Re-add system prompt first
            messages.add(org.json.JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            for (i in 0 until arr.length()) {
                messages.add(arr.getJSONObject(i))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cursor messages: ${e.message}")
            cursorDao?.deleteBySessionId(sessionId)
            return false
        }

        // Restore state
        when (cursor.state) {
            "CallingTool", "Executing" -> {
                // Tool was executed, result pending — feed back to model
                pendingToolCallJson = cursor.pendingToolCallJson
                pendingToolResultJson = cursor.pendingToolResultJson

                val resultJson = cursor.pendingToolResultJson
                if (resultJson != null) {
                    val toolResult = org.json.JSONObject(resultJson)
                    val callId = toolResult.optString("tool_call_id", "")
                    val content = toolResult.optString("content", "")
                    messages.add(org.json.JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", callId)
                        put("content", content)
                    })
                }
                Log.i(TAG, "Resumed with pending tool result, continuing loop at iteration ${cursor.iteration}")
            }
            else -> {
                Log.i(TAG, "Resumed at iteration ${cursor.iteration}, continuing")
            }
        }

        // Clear the cursor (will be re-created by loop checkpoints)
        cursorDao?.deleteBySessionId(sessionId)
        return true
    }

    /** Save current state to cursor for potential resume. */
    private suspend fun checkpointCursor() {
        val dao = cursorDao ?: return
        try {
            val msgsJson = JSONArray(messages).toString()
            val stateStr = when (_state.value) {
                is AgentState.Idle -> "Idle"
                is AgentState.Thinking -> "Thinking"
                is AgentState.CallingTool -> "CallingTool"
                is AgentState.Executing -> "Executing"
                is AgentState.WaitingConfirm -> "WaitingConfirm"
                is AgentState.Responding -> "Responding"
                is AgentState.Error -> "Error"
                is AgentState.Interrupted -> "Interrupted"
            }
            val iter = when (val s = _state.value) {
                is AgentState.Thinking -> s.iteration
                is AgentState.CallingTool -> s.iteration
                is AgentState.Executing -> s.iteration
                is AgentState.WaitingConfirm -> s.iteration
                else -> 0
            }
            dao.upsert(StateCursorEntity(
                sessionId = sessionId,
                iteration = iter,
                state = stateStr,
                messagesJson = msgsJson,
                pendingToolCallJson = pendingToolCallJson,
                pendingToolResultJson = pendingToolResultJson,
                updatedAt = System.currentTimeMillis()
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Cursor checkpoint failed: ${e.message}")
        }
    }

    /** Clear cursor when conversation completes. */
    private suspend fun clearCursor() {
        cursorDao?.deleteBySessionId(sessionId)
    }

    // ---- public API ----

    /** Cancel current run. Safe to call from any state. */
    fun stop() {
        currentJob?.cancel()
        currentJob = null
        if (_state.value.isBusy) {
            _state.value = AgentState.Interrupted
        }
    }

    /**
     * 本轮跑完后取最后一条助手文本。
     *
     * 派活出去的调用方(群聊成员、看板任务)要的是【那句成品回复】,光知道 state
     * 变回 Idle 没用。工具轮次里的 assistant 消息只有 tool_calls 没有正文,
     * 所以要跳过空内容继续往前找。
     *
     * 只在 run() 返回的 Job join 之后调用 —— 跑的过程中 messages 还在被写。
     */
    fun lastAssistantText(): String? =
        ArrayList(messages).lastOrNull {
            it.optString("role") == "assistant" && it.optString("content").isNotBlank()
        }?.optString("content")?.trim()?.takeIf { it.isNotEmpty() }

    /** Clear conversation history (keeps system prompt). */
    fun clearHistory() {
        while (messages.size > 1) messages.removeAt(messages.size - 1)
    }

    /** Update session ID when switching to a different session. Resets hash tracking for the new session. */
    fun switchSession(newSessionId: Long) {
        sessionId = newSessionId
        lastKnownPrefixHash = null
        Log.d(TAG, "Switched to session $newSessionId, hash tracking reset")
    }

    /** Sampling temperature sent with each request. Overridden per active identity card (Phase 2.0 子项目 B). */
    var temperature: Float = 1.0f

    /** gap-09 采样上限/核采样(null=不发,走服务端默认)。由 identity 卡覆盖。 */
    var maxTokens: Int? = null
    var topP: Float? = null

    /** gap-19 结构化输出:chat 形态 response_format(null=不约束)。由 AgentChatState 从全局设置注入。 */
    var responseFormat: org.json.JSONObject? = null

    /** gap-10 上下文窗口 tokens(0=未声明)与自动压缩阈值百分比(默认 85)。由活跃 provider 配置注入。 */
    var contextWindow: Int = 0
    var autoCompactThresholdPercent: Int = 85
    /** gap-10 触发自动压缩的回调(由 AgentChatState 提供,把非流式总结逻辑接进来)。 */
    var onAutoCompact: (suspend () -> Unit)? = null

    // ---- Hermes-① 自进化学习闭环:后台复盘触发 ----
    /**
     * 回答后派后台复盘分身的回调(由 app 注入)。参数:是否复盘记忆、是否复盘技能、对话尾部文本。
     * 在**最终回复已交付、状态置 Idle 之后**触发,绝不与用户任务抢占。
     */
    var onBackgroundReview: (suspend (reviewMemory: Boolean, reviewSkill: Boolean, conversationTail: String) -> Unit)? = null
    /** 记忆复盘阈值:每 N 个用户轮触发一次(默认 10)。0=禁用。 */
    var memoryNudgeInterval: Int = 10
    /** 技能复盘阈值:每 N 次工具迭代触发一次(复杂任务才够,默认 10)。0=禁用。 */
    var skillNudgeInterval: Int = 10
    private var turnsSinceMemory: Int = 0
    private var itersSinceSkill: Int = 0
    /** 后台复盘自身运行时(isReviewFork=true)不再递归触发复盘。由 review 工厂置位。 */
    var isReviewFork: Boolean = false

    /** gap-24 生命周期 hooks 分发器(可空;由 app 注入具体实现)。 */
    var hookDispatcher: HookDispatcher? = null
    private suspend fun fireHook(event: String, ctx: Map<String, String> = emptyMap()) {
        val d = hookDispatcher ?: return
        try { d.dispatch(event, ctx) } catch (e: Exception) { Log.w(TAG, "hook '$event' failed: ${e.message}") }
    }

    /**
     * Overwrite the system prompt (messages[0] content) without touching history.
     * Called before each turn so identity-card edits are always picked up fresh — never cached.
     */
    fun updateSystemPrompt(newPrompt: String) {
        if (messages.isNotEmpty()) {
            messages[0].put("content", newPrompt)
        }
    }

    /** Update iteration/timeout limits dynamically (called on power mode change). */
    fun updateLimits(maxIterations: Int, totalTimeoutMs: Long) {
        this.maxIterations = maxIterations
        this.totalTimeoutMs = totalTimeoutMs
        Log.i(TAG, "Limits updated: maxIterations=$maxIterations, totalTimeoutMs=$totalTimeoutMs")
    }

    /**
     * Restore conversation history from Room (called after loadHistoryForSession).
     * Parses MessageEntity list into API-compatible JSONObjects and prepends them
     * after the system prompt.
     */
    fun restoreHistory(entities: List<com.xincode.data.MessageEntity>) {
        // Keep system prompt, remove any stale history
        while (messages.size > 1) messages.removeAt(messages.size - 1)

        // B1 修复:tool 消息在 API 里【必须】跟在带匹配 tool_calls 的 assistant 之后,否则整段
        // history 非法 → HTTP 400。之前重建时 assistant 丢了 tool_calls、tool 成孤儿,凡有过工具
        // 调用的会话冷启动/切回后必 400。现在重建时把每个 tool 行回挂到其所在轮的 assistant 的
        // tool_calls 数组里(id 用 restored_<msgId> 一一对应),保证配对合法。
        var lastAssistant: org.json.JSONObject? = null

        fun attachToolCall(assistant: org.json.JSONObject, id: String, name: String, arguments: String, thoughtSignature: String = "") {
            val arr = assistant.optJSONArray("tool_calls") ?: org.json.JSONArray().also { assistant.put("tool_calls", it) }
            arr.put(org.json.JSONObject().apply {
                put("id", id); put("type", "function")
                put("function", org.json.JSONObject().apply { put("name", name); put("arguments", arguments) })
                if (thoughtSignature.isNotBlank()) {
                    put("extra_content", org.json.JSONObject().put("google", org.json.JSONObject().put("thought_signature", thoughtSignature)))
                }
            })
            // assistant 带 tool_calls 时 content 允许为 null;若原本为空串,保持空串也可,这里不动 content。
        }

        for (msg in entities) {
            when (msg.role) {
                "user" -> {
                    messages.add(org.json.JSONObject().apply {
                        put("role", "user"); put("content", msg.content)
                    })
                    lastAssistant = null
                }
                "assistant" -> {
                    val a = org.json.JSONObject().apply { put("role", "assistant"); put("content", msg.content) }
                    messages.add(a)
                    lastAssistant = a
                }
                "tool" -> {
                    val id = "restored_${msg.id}"
                    val j = try { org.json.JSONObject(msg.content) } catch (_: Exception) { null }
                    val isToolCall = j?.optBoolean("__tool_call__", false) == true
                    val name = if (isToolCall) j!!.optString("tool_name", "tool") else "tool"
                    val arguments = if (isToolCall) j!!.optString("full_params", "{}").ifBlank { "{}" } else "{}"
                    val thoughtSignature = if (isToolCall) j!!.optString("thought_signature", "") else ""
                    val content = if (isToolCall) {
                        val stdout = j!!.optString("stdout", "")
                        val stderr = j.optString("stderr", "")
                        val exitCode = if (j.isNull("exit_code")) null else j.optInt("exit_code")
                        buildString {
                            if (stdout.isNotBlank()) append(stdout)
                            if (stderr.isNotBlank()) append("\n[stderr]\n").append(stderr)
                            if (exitCode != null) append("\n[exit $exitCode]")
                        }.ifBlank { "(no output)" }
                    } else msg.content
                    // 确保有一个带 tool_calls 的 assistant 在前;没有就补一个空的(边缘路径)。
                    val assistant = lastAssistant ?: org.json.JSONObject().apply {
                        put("role", "assistant"); put("content", org.json.JSONObject.NULL)
                    }.also { messages.add(it); lastAssistant = it }
                    attachToolCall(assistant, id, name, arguments, thoughtSignature)
                    messages.add(org.json.JSONObject().apply {
                        put("role", "tool"); put("tool_call_id", id); put("content", content)
                    })
                }
            }
        }
        Log.i(TAG, "restoreHistory: loaded ${entities.size} messages from Room (tool_calls repaired)")
    }

    /**
     * Start the agent loop for a new user message.
     * Ignored if already busy.
     * @return Job that completes when the loop finishes (success/error/interrupt).
     */
    fun run(userMessage: String, scope: CoroutineScope, thinkingEnabled: Boolean = false, thinkingLevel: Int = 2): Job {
        if (_state.value.isBusy) {
            Log.w(TAG, "Agent busy (${_state.value}), ignoring run()")
            return Job()
        }

        // B 方案:每轮重建 channel,确保本轮 collector 绑定的是全新队列(无上轮残留)。
        tokenChannel = Channel<String>(Channel.UNLIMITED)
        reasoningChannel = Channel<String>(Channel.UNLIMITED)
        currentJob = scope.launch {
            try {
                withTimeout(totalTimeoutMs) {
                    runLoop(userMessage, thinkingEnabled, thinkingLevel)
                }
            } catch (e: TimeoutCancellationException) {
                _state.value = AgentState.Error("总超时 (${totalTimeoutMs / 60_000} 分钟)")
            } catch (e: CancellationException) {
                _state.value = AgentState.Interrupted
            } catch (e: Exception) {
                Log.e(TAG, "Loop exception: ${e.message}", e)
                _state.value = AgentState.Error(e.message ?: "未知错误")
            } finally {
                // B 方案:轮末 close 两个 channel,让 collector 消费完队列残留后自然结束,
                // 不丢尾部 token;对异常/取消路径同样兜底。
                tokenChannel.close()
                reasoningChannel.close()
                currentJob = null
                if (!_state.value.isTerminal) {
                    _state.value = AgentState.Idle
                }
                Log.d("XINCODE", "state -> Idle, agentCore.isBusy=${_state.value.isBusy}")
            }
        }
        return currentJob!!
    }

    // ---- loop ----

    private suspend fun runLoop(initialUserMessage: String, thinkingEnabled: Boolean, thinkingLevel: Int) {
        // L3:每次 run 开始清零累计,使其代表"本轮/本任务"合计。
        cumulativePromptTokens = 0L
        cumulativeCompletionTokens = 0L
        // gap-24 生命周期 hooks:会话开始 + 用户输入提交
        fireHook("session_start")
        fireHook("user_prompt_submit", mapOf("prompt" to initialUserMessage))

        // Add user message to history (supports direct multimodal images if present)
        messages.add(org.json.JSONObject().apply {
            put("role", "user")
            put("content", buildMultimodalUserContent(initialUserMessage))
        })
        checkpointCursor()  // user message persisted

        // 一个 runLoop = 一个用户问题 = 一个 turn
        // turnId 由 send() 预置在 currentTurnId 中,此处不再覆盖
        // 若从未设过(极边缘路径),则 fallback 设一个
        if (currentTurnId == 0L) {
            currentTurnId = System.currentTimeMillis()
        }
        // 不再有 onTurnStart 回调:AgentChatState 直接读 currentTurnId

        var iteration = 0
        consecutiveTruncations = 0
        lastToolErrorSignature = null; repeatedToolErrors = 0
        while (iteration < maxIterations) {
            iteration++
            // 循环内不再重新计算 turnId
            // Hermes-① 技能复盘按工具迭代计数:复杂任务(多次迭代)才够阈值。
            itersSinceSkill++

            // Hermes-⑧ 打断-重定向:若有排队的新指令,作为 user 消息插入后再调模型。
            val redirect = pendingRedirect
            if (redirect != null) {
                pendingRedirect = null
                messages.add(org.json.JSONObject().apply {
                    put("role", "user"); put("content", redirect)
                })
                fireHook("user_prompt_submit", mapOf("prompt" to redirect))
                checkpointCursor()
                Log.i(TAG, "redirect injected at iter=$iteration")
            }

            _state.value = AgentState.Thinking(iteration)
            Log.d(TAG, "loop start, iter=$iteration")

            // gap-10:自动压缩——上一轮 prompt_tokens 超过 context_window*阈值% 时,触发一次上下文压缩。
            maybeAutoCompact()

            // Build request snapshot
            val messagesSnapshot = ArrayList(messages)

            // M6/防御:确保每个 assistant.tool_calls 都有匹配的 tool 结果(断点恢复/多工具回合被杀可能缺),
            // 否则历史非法 → HTTP 400。在快照上补齐合成占位结果,不动真实 messages。
            sanitizeToolPairs(messagesSnapshot)

            // P1: Compact long tool results before sending to API (does not modify Room data)
            compactToolResults(messagesSnapshot)

            val toolsJson = toolRegistry.buildToolsJson()

            // P2: Compute prefix hash for cache stability monitoring
            val prefixInput = systemPrompt + toolsJson.toString()
            val currentHash = computeSHA256(prefixInput)
            if (lastKnownPrefixHash != null && currentHash != lastKnownPrefixHash) {
                Log.w("CacheStability", "Prefix hash drift detected: " +
                        "old=${lastKnownPrefixHash!!.take(16)}... new=${currentHash.take(16)}...")
            }
            lastKnownPrefixHash = currentHash
            onPrefixHashComputed?.invoke(currentHash, sessionId)

            // Call model
            val result = callModel(messagesSnapshot, toolsJson, iteration, thinkingEnabled, thinkingLevel) ?: return
            Log.d(TAG, "callModel returned: contentLen=${result.content.length}, toolCalls=${result.toolCalls.size}")

            // Add assistant response to history
            messages.add(buildAssistantMessage(result))

            // 流被掐断(没收到 [DONE]/finish_reason/message_stop):拿到的是半截回复。
            // 半截回复常常不含 tool_calls,若不拦住就会被下面当成「最终回答」→ 回合无声结束,
            // 也就是用户反馈的「做一点就罢工」。这里改为自动续跑:让模型接着刚才断掉的地方往下写。
            if (result.truncated) {
                consecutiveTruncations++
                Log.w(TAG, "stream truncated (#$consecutiveTruncations) at iter=$iteration, " +
                        "contentLen=${result.content.length}, toolCalls=${result.toolCalls.size}")
                if (consecutiveTruncations <= MAX_CONSECUTIVE_TRUNCATIONS) {
                    // 只在「没有工具调用」时续跑。带工具调用的半截回复参数可能不完整,
                    // 让它照常走工具流程反而危险,交给下面的正常分支(工具失败会有明确报错)。
                    if (result.toolCalls.isEmpty()) {
                        messages.add(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", "(系统提示:上一条回复因网络中断而截断。请从断点处继续,不要重复已经写过的内容。)")
                        })
                        _state.value = AgentState.Thinking(iteration)
                        checkpointCursor()
                        continue
                    }
                } else {
                    _state.value = AgentState.Error(
                        "响应流连续 $MAX_CONSECUTIVE_TRUNCATIONS 次被中断,已停止重试。请检查网络后重发。",
                        iteration
                    )
                    clearCursor()
                    return
                }
            } else {
                consecutiveTruncations = 0
            }

            // Check: no tool_calls → final response, done
            if (result.toolCalls.isEmpty()) {
                // 中途插话修复:用户最常在「AI 正在输出最终回答」时插话,而这一步本会直接 return,
                // 导致排队的 pendingRedirect 永远没人消费——表现为「发了消息但 AI 毫无反应」。
                // 此处若发现有排队指令,就不结束循环,继续下一轮,由循环顶部把它作为新 user 消息注入。
                if (pendingRedirect != null) {
                    _state.value = AgentState.Responding(iteration)
                    Log.i(TAG, "final answer done but redirect pending → continue loop")
                    continue
                }
                _state.value = AgentState.Responding(iteration)
                clearCursor()  // conversation complete
                _state.value = AgentState.Idle
                Log.i(TAG, "Loop done: $iteration iterations, ${result.content.length} chars")
                // Hermes-① 回答已交付、状态 Idle → 现在(不抢占任务)派后台复盘分身。
                maybeFireBackgroundReview()
                return
            }
// Execute tool calls with security gate
            var callIndex = 0
            for (rawCall in result.toolCalls) {
                // 工具名纠偏必须发生在【权限闸门之前】:闸门是按名字分类的,
                // 如果只在派发处纠正,判权限用的是 `search_web`、真正执行的是 `web_search`,
                // 两边对不上比不纠正更危险。这里换完之后,下游全程只见真名。
                val call = if (toolRegistry.get(rawCall.name) != null) rawCall
                    else rawCall.copy(name = toolRegistry.canonicalName(rawCall.name))
                callIndex++
                _state.value = AgentState.CallingTool(iteration, call.name, call.arguments)

                // Emit PushCall so UI shows a pending ToolCallBlock
                onToolBlock?.invoke(ToolBlockAction.PushCall(callIndex, call.name, call.arguments, call.thoughtSignature))

                // --- Security Gate ---
                val gate = securityGate
                if (gate != null) {
                    val cmd = gate.classify(call.name, call.arguments)
                    val decision = gate.decide(cmd, gate.getPermissionMode())

                    when (decision) {
                        is Decision.Allow -> {
                            gate.audit(cmd, decision, null)
                        }
                        is Decision.Denied -> {
                            gate.audit(cmd, decision, null)
                            onToolBlock?.invoke(ToolBlockAction.UpdateResult(
                                callIndex = callIndex, stdout = "", stderr = "",
                                exitCode = null, durationMs = null, status = "DENIED"))
                            messages.add(org.json.JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", call.id)
                                put("content", "操作被安全闸门拒绝: ${decision.reason}")
                            })
                            checkpointCursor()
                            continue
                        }
                        is Decision.NeedConfirm -> {
                            if (cmd.toolName in sessionWhitelist) {
                                gate.audit(cmd, decision, "whitelist-auto-allow")
                                Log.d(TAG, "Whitelist auto-allow for ${cmd.toolName}")
                            } else {
                                val handler = confirmHandler
                                if (handler == null) {
                                    gate.audit(cmd, decision, "auto-deny: no handler")
                                    onToolBlock?.invoke(ToolBlockAction.UpdateResult(
                                        callIndex = callIndex, stdout = "", stderr = "",
                                        exitCode = null, durationMs = null, status = "DENIED"))
                                    messages.add(org.json.JSONObject().apply {
                                        put("role", "tool")
                                        put("tool_call_id", call.id)
                                        put("content", "操作需要确认但无可用的确认处理程序，已自动拒绝")
                                    })
                                    checkpointCursor()
                                    continue
                                }
                                _state.value = AgentState.WaitingConfirm(
                                    iteration = iteration, toolName = call.name, preview = decision.preview)
                                val confirmResult = handler(cmd, decision.preview)
                                when (confirmResult) {
                                    ToolConfirmResult.DENY -> {
                                        gate.audit(cmd, decision, "用户拒绝")
                                        onToolBlock?.invoke(ToolBlockAction.UpdateResult(
                                            callIndex = callIndex, stdout = "", stderr = "",
                                            exitCode = null, durationMs = null, status = "DENIED"))
                                        messages.add(org.json.JSONObject().apply {
                                            put("role", "tool"); put("tool_call_id", call.id)
                                            put("content", "用户拒绝了此操作")
                                        })
                                        checkpointCursor(); continue
                                    }
                                    ToolConfirmResult.ALLOW_ONCE -> {
                                        gate.audit(cmd, decision, "用户确认(仅本次)")
                                    }
                                    ToolConfirmResult.ALWAYS_ALLOW -> {
                                        sessionWhitelist.add(cmd.toolName)
                                        gate.audit(cmd, decision, "用户确认(总是允许)")
                                        Log.d(TAG, "Added ${cmd.toolName} to session whitelist")
                                    }
                                }
                            }
                        }
                    }
                }
                // --- End Security Gate ---

                _state.value = AgentState.Executing(iteration, call.name)
                // gap-24 pre_tool hook
                fireHook("pre_tool", mapOf("tool" to call.name, "args" to call.arguments))
                val startTime = System.currentTimeMillis()
                val toolResult = withContext(ToolSessionElement(sessionId)) {
                    toolRegistry.execute(call)
                }
                val durationMs = System.currentTimeMillis() - startTime
                // 生图是外部能力边界：失败后禁止 Agent 继续猜测聊天模型并重复调用。
                // 工具卡已经收到失败结果，下面仍会写入工具消息并 checkpoint。
                val stopAfterToolFailure = call.name == "generate_image" && toolResult is ToolResult.Error
                // gap-24 post_tool hook
                fireHook("post_tool", mapOf(
                    "tool" to call.name,
                    "status" to (if (toolResult is ToolResult.Success) "SUCCESS" else "FAIL"),
                    "output_head" to (if (toolResult is ToolResult.Success) toolResult.output.take(200) else "")
                ))

                val stdout = when (toolResult) {
                    is ToolResult.Success -> toolResult.output
                    is ToolResult.Error -> ""
                }
                val stderr = when (toolResult) {
                    is ToolResult.Error -> toolResult.stderr ?: toolResult.message
                    is ToolResult.Success -> ""
                }
                val exitCode = when (toolResult) {
                    is ToolResult.Error -> toolResult.exitCode
                    is ToolResult.Success -> 0
                }
                val resultStatus = when (toolResult) {
                    is ToolResult.Success -> "SUCCESS"
                    is ToolResult.Error -> "FAIL"
                }

                // Emit UpdateResult so UI fills in stdout/stderr/exitCode/duration
                onToolBlock?.invoke(ToolBlockAction.UpdateResult(
                    callIndex = callIndex,
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = exitCode,
                    durationMs = durationMs,
                    status = resultStatus
                ))
                // 死循环刹车:模型撞上同一个错误时往往会一模一样地再试一遍,而它看到的反馈
                // 每次都相同,于是可以一直转下去(实测见过连转 9 轮同一个「未知工具」)。
                // 这里按「工具名 + 错误信息」记连击数,连续同样的失败达到阈值就直接中止整轮,
                // 并把原因如实告诉用户,而不是让它自己撞到天荒地老。
                if (toolResult is ToolResult.Error) {
                    val sig = "${call.name}|${toolResult.message}"
                    if (sig == lastToolErrorSignature) {
                        repeatedToolErrors++
                    } else {
                        lastToolErrorSignature = sig; repeatedToolErrors = 1
                    }
                    if (repeatedToolErrors >= MAX_REPEATED_TOOL_ERRORS) {
                        Log.w(TAG, "aborting: same tool error x$repeatedToolErrors — $sig")
                        _state.value = AgentState.Error(
                            "「${call.name}」连续 $repeatedToolErrors 次同样失败,已中止以免空转。" +
                                "最后的错误:${toolResult.message}",
                            iteration
                        )
                        clearCursor()
                        return
                    }
                } else {
                    lastToolErrorSignature = null; repeatedToolErrors = 0
                }

                // Build content for model feedback (existing logic, keep unchanged)
                val content = when (toolResult) {
                    is ToolResult.Success -> toolResult.output
                    is ToolResult.Error -> buildString {
                        append("错误: ${toolResult.message}")
                        if (toolResult.exitCode != null) append("\n退出码: ${toolResult.exitCode}")
                        if (!toolResult.stderr.isNullOrBlank()) append("\nstderr:\n${toolResult.stderr}")
                        // 明确告诉模型「别再原样重试了」。只回错误不给指引时,模型很容易
                        // 认为是偶发失败而重复同一个调用。
                        if (repeatedToolErrors >= 2) {
                            append("\n\n注意:这个调用已经连续失败 $repeatedToolErrors 次,原样重试不会有不同结果。" +
                                "请先说明你判断的失败原因和打算换的做法,再调用工具;若无法解决,直接告诉用户。")
                        }
                    }
                }

                // Save cursor with pending tool result BEFORE feeding back
                pendingToolCallJson = org.json.JSONObject().apply {
                    put("tool_call_id", call.id)
                    put("name", call.name)
                    put("arguments", call.arguments)
                    if (call.thoughtSignature.isNotBlank()) put("thought_signature", call.thoughtSignature)
                }.toString()
                pendingToolResultJson = org.json.JSONObject().apply {
                    put("tool_call_id", call.id)
                    put("content", content)
                }.toString()
                checkpointCursor()  // ← KILL HERE → resume picks up tool result

                messages.add(org.json.JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", call.id)
                    put("content", content)
                })
                pendingToolCallJson = null  // fed back, clear pending
                pendingToolResultJson = null
                checkpointCursor()  // tool result fed back

                if (stopAfterToolFailure) {
                    _state.value = AgentState.Error(
                        "生图未完成，已停止自动更换模型重试。最后错误: " +
                            (toolResult as ToolResult.Error).message,
                        iteration
                    )
                    clearCursor()
                    return
                }
            }
            // → loop back to Thinking for next iteration
        }

        // Exhausted max iterations
        _state.value = AgentState.Error("已达最大轮数 ($maxIterations)，强制停止", maxIterations)
        clearCursor()
    }

    // ---- helpers ----

    /**
     * gap-10:若声明了 context_window 且上一轮 prompt_tokens 超过阈值百分比,触发一次自动压缩。
     * 用 [onAutoCompact] 回调复用 AgentChatState 的非流式总结逻辑。每次触发后清空 lastUsage 防抖。
     */
    /**
     * Hermes-① 自进化学习闭环:据计数器判断是否该复盘,是则调回调派后台分身。
     * memory 按用户轮计数、skill 按工具迭代计数;后台复盘自身(isReviewFork)不递归触发。
     */
    private suspend fun maybeFireBackgroundReview() {
        if (isReviewFork) return
        val cb = onBackgroundReview ?: return
        turnsSinceMemory++
        val reviewMemory = memoryNudgeInterval > 0 && turnsSinceMemory >= memoryNudgeInterval
        val reviewSkill = skillNudgeInterval > 0 && itersSinceSkill >= skillNudgeInterval
        if (!reviewMemory && !reviewSkill) return
        if (reviewMemory) turnsSinceMemory = 0
        if (reviewSkill) itersSinceSkill = 0
        // 用对话尾部(最近若干条)喂给复盘分身判断该沉淀什么。
        val tail = buildConversationTail(8)
        try { cb.invoke(reviewMemory, reviewSkill, tail) }
        catch (e: Exception) { Log.w(TAG, "background review failed: ${e.message}") }
    }

    /** 取最近 [n] 条非 system 消息,压成一段可读文本(截断超长 tool 结果)。 */
    private fun buildConversationTail(n: Int): String {
        val out = StringBuilder()
        val start = maxOf(1, messages.size - n) // 跳过 messages[0] system
        for (i in start until messages.size) {
            val m = messages[i]
            val role = m.optString("role")
            val content = m.optString("content").take(1200)
            if (content.isBlank()) continue
            out.append(role).append(": ").append(content).append("\n\n")
        }
        return out.toString().trim()
    }

    // Hermes-⑨ 压缩加固:冷却窗 + 抗抖动(连续压缩不见效就退避)。
    private var compactCooldownIters = 0
    private var consecutiveIneffectiveCompactions = 0
    private var lastCompactPromptTokens = 0

    private suspend fun maybeAutoCompact() {
        val cw = contextWindow
        val cb = onAutoCompact
        if (cw <= 0 || cb == null) return
        // Hermes-⑨ 冷却:刚压过就先跳几轮,避免抖动。
        if (compactCooldownIters > 0) { compactCooldownIters--; return }
        val promptTokens = lastUsage?.optInt("prompt_tokens", 0) ?: 0
        if (promptTokens <= 0) return
        val threshold = cw.toLong() * autoCompactThresholdPercent / 100
        if (promptTokens < threshold) return

        // Hermes-⑨ 抗抖动:若上次压缩后 prompt_tokens 没明显下降(<10%),累计一次"无效压缩";
        // 连续 2 次无效就退避(不再压),避免对已压到极限的历史反复召唤总结模型烧钱。
        if (lastCompactPromptTokens > 0) {
            val dropped = lastCompactPromptTokens - promptTokens
            if (dropped < lastCompactPromptTokens / 10) {
                consecutiveIneffectiveCompactions++
                if (consecutiveIneffectiveCompactions >= 2) {
                    Log.w(TAG, "Auto-compact backing off: last 2 passes ineffective (prompt_tokens≈$promptTokens)")
                    lastUsage = null
                    compactCooldownIters = 3
                    return
                }
            } else {
                consecutiveIneffectiveCompactions = 0
            }
        }

        Log.i(TAG, "Auto-compact: prompt_tokens=$promptTokens ≥ $threshold (ctx=$cw, ${autoCompactThresholdPercent}%)")
        try { cb.invoke() } catch (e: Exception) { Log.e(TAG, "auto-compact failed: ${e.message}") }
        lastCompactPromptTokens = promptTokens
        lastUsage = null       // 防抖:压缩后重置,避免同一用量连续触发
        compactCooldownIters = 2 // 压完先冷却 2 轮
    }

    /** Bridge callback-based agentStream to a suspend function via suspendCancellableCoroutine. */
    private suspend fun callModel(
        messages: List<org.json.JSONObject>,
        tools: org.json.JSONArray,
        iteration: Int,
        thinkingEnabled: Boolean,
        thinkingLevel: Int
    ): AgentStreamResult? = suspendCancellableCoroutine { cont ->
        // Launch inside coroutine since agentStream is suspend and this lambda is non-suspend
        //
        // 这个 continuation 只有 onComplete / onError 会唤醒。只要 agentStream 抛出了这两个回调都
        // 覆盖不到的东西(Error 级异常、或将来某个新分支忘了回调),这里就会【永久挂起】——
        // UI 停在「思考中」,不报错、不超时、不结束(用户反馈的「回复老不回」)。
        // 因此下面 try/finally 兜底:无论怎么退出,continuation 必定被唤醒一次。
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
            openAiClient.agentStream(
                messages = messages,
                tools = tools,
                temperature = temperature,
                thinkingEnabled = thinkingEnabled,
                thinkingLevel = thinkingLevel,
                maxTokens = maxTokens,   // gap-09
                topP = topP,             // gap-09
                responseFormat = responseFormat, // gap-19
                onToken = { token -> tokenChannel.trySend(token) },
                onReasoning = { r -> reasoningChannel.trySend(r) },
onComplete = { result ->
                        lastUsage = result.usage
                        result.usage?.let {
                            cumulativePromptTokens += it.optLong("prompt_tokens", 0).coerceAtLeast(0)
                            cumulativeCompletionTokens += it.optLong("completion_tokens", 0).coerceAtLeast(0)
                        }
                        // 用量分析:每次调用都记一笔。放在这里而不是回合结束时记,
                        // 是因为一个回合里工具回环会调很多次模型,只记最后一次会严重少算。
                        onUsageRecorded?.let { cb ->
                            result.usage?.let { u -> runCatching { cb(u, sessionId) } }
                        }
                        if (cont.isActive) cont.resume(result) {}
                    },
                onError = { apiError ->
                    Log.d(TAG, "callModel onError: ${apiError.message}")
                    _state.value = AgentState.Error(apiError.message ?: "API 错误", iteration)
                    if (cont.isActive) cont.resume(null) {}
                }
            )
            } catch (c: CancellationException) {
                throw c   // 用户点「停止」:保持取消语义,由 invokeOnCancellation 收尾
            } catch (t: Throwable) {
                Log.e(TAG, "callModel threw: ${t::class.java.simpleName}: ${t.message}", t)
                _state.value = AgentState.Error(
                    "模型请求异常(${t::class.java.simpleName}): ${t.message ?: "无详情"}", iteration
                )
            } finally {
                // 兜底唤醒:走到这里若 continuation 仍未被唤醒,说明上面漏了回调。
                // 宁可当作一次失败回合,也绝不能把整个 agent 循环永久卡死。
                if (cont.isActive) {
                    Log.w(TAG, "callModel finished without resuming — forcing null resume")
                    // 状态若还没被置成错误,这里补一个:否则上层 `?: return` 会无声结束回合。
                    if (_state.value !is AgentState.Error) {
                        _state.value = AgentState.Error("模型请求未返回任何结果,已中止本回合", iteration)
                    }
                    cont.resume(null) {}
                }
            }
        }
        cont.invokeOnCancellation { job.cancel() }
    }

    /**
     * M6/防御:保证消息序列里每个 assistant.tool_calls 的 id 后面都有一条对应的 tool 结果。
     * 断点恢复、或多 tool_call 回合被杀导致部分结果缺失时,历史会非法(assistant 声明了工具调用却
     * 没有全部结果)→ HTTP 400。此处在【快照】上为缺失的 id 追加合成占位结果(不动真实 [messages])。
     */
    private fun sanitizeToolPairs(msgs: MutableList<org.json.JSONObject>) {
        var i = 0
        while (i < msgs.size) {
            val m = msgs[i]
            val calls = if (m.optString("role") == "assistant") m.optJSONArray("tool_calls") else null
            if (calls != null && calls.length() > 0) {
                // 收集本 assistant 之后、下一条非 tool 消息之前已有的 tool_call_id。
                val present = HashSet<String>()
                var j = i + 1
                while (j < msgs.size && msgs[j].optString("role") == "tool") {
                    present.add(msgs[j].optString("tool_call_id")); j++
                }
                // 为缺失的 id 在该段末尾插入合成占位结果。
                var insertAt = j
                for (k in 0 until calls.length()) {
                    val id = calls.optJSONObject(k)?.optString("id") ?: continue
                    if (id.isNotBlank() && id !in present) {
                        msgs.add(insertAt, org.json.JSONObject().apply {
                            put("role", "tool"); put("tool_call_id", id)
                            put("content", "[该工具调用未完成或结果丢失(可能上次被中断)]")
                        })
                        insertAt++
                    }
                }
                i = insertAt
            } else i++
        }
    }

    // ---- P1: Tool result compaction ----

    /**
     * Compact long tool result messages in-place on [msgs] before sending to the API.
     * Only compresses messages where role == "tool".
     * Does NOT modify the original [messages] list — only operates on the snapshot copy.
     */
    private fun compactToolResults(msgs: MutableList<org.json.JSONObject>) {
        if (!enableResultCompaction) return

        for (i in msgs.indices) {
            val msg = msgs[i]
            if (msg.optString("role") != "tool") continue

            val content = msg.optString("content", "")
            if (content.isEmpty()) continue

            val estimatedTokens = content.length / CHARS_PER_TOKEN
            if (estimatedTokens <= TURN_END_RESULT_CAP_TOKENS) continue

            val prefixChars = COMPACT_PREFIX_TOKENS * CHARS_PER_TOKEN
            val suffixChars = COMPACT_SUFFIX_TOKENS * CHARS_PER_TOKEN
            val truncatedChars = content.length - prefixChars - suffixChars

            val compacted = buildString {
                append(content.take(prefixChars))
                append("\n[...已截断 ${truncatedChars}字符，请用精确命令重新查询...]\n")
                append(content.takeLast(suffixChars))
            }
            // Create a NEW JSONObject to avoid modifying the original [messages] list
            val compactedMsg = org.json.JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", msg.optString("tool_call_id", ""))
                put("content", compacted)
            }
            msgs[i] = compactedMsg
            Log.d(TAG, "compactToolResults: truncated ${truncatedChars} chars in message[$i] " +
                    "(${estimatedTokens} tokens → ≤${TURN_END_RESULT_CAP_TOKENS} tokens)")
        }
    }

    // ---- P2: Prefix hash ----

    /** Compute SHA-256 hex digest of [input]. */
    private fun computeSHA256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Build the assistant role message JSON for history, including optional tool_calls. */
    private fun buildAssistantMessage(result: AgentStreamResult): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("role", "assistant")
            if (result.content.isNotEmpty()) {
                put("content", result.content)
            } else {
                // Some models return null content when only tool_calls
                put("content", org.json.JSONObject.NULL)
            }
            if (result.toolCalls.isNotEmpty()) {
                put("tool_calls", org.json.JSONArray().apply {
                    for (tc in result.toolCalls) {
                        put(org.json.JSONObject().apply {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", org.json.JSONObject().apply {
                                put("name", tc.name)
                                put("arguments", tc.arguments)
                            })
                            if (tc.thoughtSignature.isNotBlank()) {
                                put("extra_content", org.json.JSONObject().put("google", org.json.JSONObject().put("thought_signature", tc.thoughtSignature)))
                            }
                        })
                    }
                })
            }
        }
    }

    /** Extracts image paths and produces a multimodal JSONArray (or plain string if no images). */
    private fun buildMultimodalUserContent(text: String): Any {
        val regex = Regex("""###\s*([^(]+)\(图片,路径:([^)]+)\)""")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return text

        val cleanText = text.replace(Regex("""\n*---\n附件:[\s\S]*?---\n*"""), "").trim()
        val parts = org.json.JSONArray()
        if (cleanText.isNotBlank()) {
            parts.put(org.json.JSONObject().apply {
                put("type", "text")
                put("text", cleanText)
            })
        }
        for (m in matches) {
            val fileName = m.groupValues[1].trim()
            val path = m.groupValues[2].trim()
            val file = java.io.File(path)
            if (file.exists() && file.length() in 1..(12 * 1024 * 1024)) {
                try {
                    val bytes = file.readBytes()
                    val ext = fileName.substringAfterLast('.', "jpeg").lowercase()
                    val mime = if (ext == "png") "image/png" else if (ext == "webp") "image/webp" else "image/jpeg"
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    parts.put(org.json.JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", org.json.JSONObject().apply {
                            put("url", "data:$mime;base64,$b64")
                        })
                    })
                } catch (_: Exception) {}
            }
            parts.put(org.json.JSONObject().apply {
                put("type", "text")
                put("text", "[用户附带图片: $fileName，本地路径: $path。若需深入分析也可调用 describe_image 工具]")
            })
        }
        return if (parts.length() > 0) parts else text
    }
}
