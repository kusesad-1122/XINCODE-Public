package com.xincode.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 步骤A:App Server 双向层(方案 docs/CODEX-HARNESS优化方案.md)。
 *
 * 对应视频链路:单向一问一答接口接不住长任务,App Server 负责连接外部产品
 * (UI/通知/定时任务)与内部核心运行系统,做双向持续对话。
 *
 * - 上行(产品→核心):[requestStart]/[requestInterrupt]/[requestInput]/[resolveApproval]。
 *   service 模块不依赖 app/core,拥有方(XincodeApplication)在 onCreate 注册回调。
 * - 下行(核心→产品):[events] 事件流 + [taskState] 状态流,UI/通知只订阅,不直调 core。
 * - 审批交会:[awaitApproval] 挂起等回执,[resolveApproval] 完成它;无回执=不执行。
 *
 * 全 additive 设计:不改任何现有调用链,不发版前保持行为一致。
 */
object AgentServer {

    /** 下行事件:核心运行中向产品推送的一切。 */
    sealed interface AgentServerEvent {
        /** 任务状态迁移。 */
        data class TaskStateChanged(val state: AgentTaskState) : AgentServerEvent
        /** 工具调用开始(只带名字+摘要,不带全参,防敏感参数进通知)。 */
        data class ToolCallStarted(val toolName: String, val preview: String) : AgentServerEvent
        /** 工具执行结束(摘要)。 */
        data class ToolResult(val toolName: String, val digest: String) : AgentServerEvent
        /** 文件改动(diff 摘要)。 */
        data class FileDiff(val path: String, val digest: String) : AgentServerEvent
        /** 审批请求:Turn 已真停,等回执。 */
        data class ApprovalRequested(
            val requestId: String,
            val toolName: String,
            val preview: String
        ) : AgentServerEvent
        /** 审批已裁决(回执落盘/广播用)。 */
        data class ApprovalResolved(val requestId: String, val approved: Boolean) : AgentServerEvent
        /** 普通通知文本(进度/错误等)。 */
        data class Notice(val text: String) : AgentServerEvent
    }

    /** 任务状态:IDLE/RUNNING/WAITING_TOOL/WAITING_APPROVAL/FINISHED。 */
    enum class AgentTaskState {
        IDLE,
        RUNNING,
        WAITING_TOOL,
        WAITING_APPROVAL,
        FINISHED
    }

    // ---- 下行 ----
    private val _events = MutableSharedFlow<AgentServerEvent>(extraBufferCapacity = 256)
    /** 下行事件流:无 replay,订阅者只收订阅之后的事(历史从 Room 读,不靠内存)。 */
    val events: SharedFlow<AgentServerEvent> = _events

    private val _taskState = MutableStateFlow(AgentTaskState.IDLE)
    val taskState: StateFlow<AgentTaskState> = _taskState

    /** 核心侧发射事件(非挂起;缓冲区满时丢最旧?不——直接返回 false,由调用方记日志)。 */
    fun emit(event: AgentServerEvent): Boolean = _events.tryEmit(event)

    /** 核心侧推进任务状态(同步广播 TaskStateChanged)。 */
    fun setTaskState(state: AgentTaskState) {
        _taskState.value = state
        _events.tryEmit(AgentServerEvent.TaskStateChanged(state))
    }

    // ---- 上行:拥有方注册 ----
    /** 有产品请求启动任务时被调用,返回 false 表示拥有方拒绝/正忙。 */
    var onStartRequest: ((prompt: String) -> Boolean)? = null
    /** 收到中断请求时被调用(通知栏按钮/超时/用户手势都走这里)。 */
    var onInterruptRequest: (() -> Unit)? = null
    /** 运行中追发新输入(打断/ steering),返回 false 表示无人接收。 */
    var onInputRequest: ((text: String) -> Boolean)? = null

    /** 上行:请求启动任务。无拥有方时返回 false。 */
    fun requestStart(prompt: String): Boolean = onStartRequest?.invoke(prompt) ?: false

    /** 上行:请求中断当前一切工作。无拥有方时返回 false。 */
    fun requestInterrupt(): Boolean {
        val hook = onInterruptRequest ?: return false
        hook()
        return true
    }

    /** 上行:运行中追发新输入。无拥有方时返回 false。 */
    fun requestInput(text: String): Boolean = onInputRequest?.invoke(text) ?: false

    // ---- 审批交会 ----
    private val pendingApprovals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** 生成审批请求 ID(调用方随后 emit ApprovalRequested)。 */
    fun newApprovalId(): String = UUID.randomUUID().toString()

    /**
     * 挂起等待该审批的回执。
     * @param timeoutMs 超时毫秒;<=0 表示无限等(由调用方 totalTimeout 兜底,审批可能第二天才回)。
     * @return true=同意 false=拒绝 null=超时/无回执(调用方必须按拒绝处理:不执行)。
     */
    suspend fun awaitApproval(requestId: String, timeoutMs: Long): Boolean? {
        val gate = CompletableDeferred<Boolean>()
        pendingApprovals[requestId] = gate
        try {
            return if (timeoutMs <= 0) gate.await()
            else withTimeoutOrNull(timeoutMs) { gate.await() }
        } finally {
            pendingApprovals.remove(requestId)
        }
    }

    /** 回填审批结果;无等待者/已超时返回 false(且不广播,避免迟到回执误导订阅者)。 */
    fun resolveApproval(requestId: String, approved: Boolean): Boolean {
        val gate = pendingApprovals.remove(requestId) ?: return false
        val done = gate.complete(approved)
        if (done) _events.tryEmit(AgentServerEvent.ApprovalResolved(requestId, approved))
        return done
    }
}
