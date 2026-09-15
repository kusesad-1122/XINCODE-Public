package com.xincode.app

import android.util.Log
import com.xincode.core.AgentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable timeline events for the run monitor view.
 * Subscribes to [AgentCore.state] and records each state transition.
 *
 * 改名说明(M3-10):原 `WorkflowState` 只是「单次 Agent 运行的状态监视器」,
 * 没有任何编排引擎,名字会误导用户以为有工作流编排能力。现统一改名为
 * `RunMonitorState`,UI 同步叫「运行监控」。
 */
class RunMonitorState {
    companion object {
        private const val TAG = "RunMonitorState"
    }

    /** Single timeline entry. */
    data class TimelineEvent(
        val timestamp: Long = System.currentTimeMillis(),
        val type: EventType,
        val label: String,
        val detail: String = ""
    )

    enum class EventType {
        THINKING, CALLING_TOOL, WAITING_CONFIRM, EXECUTING, RESPONDING, ERROR, INTERRUPTED, IDLE
    }

    private val _events = MutableStateFlow<List<TimelineEvent>>(emptyList())
    val events: StateFlow<List<TimelineEvent>> = _events.asStateFlow()

    private val _currentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val currentState: StateFlow<AgentState> = _currentState.asStateFlow()

    private var iteration: Int = 0
    private var startTime: Long = 0L

    /** Called by the composable when a new AgentState is emitted. */
    fun onStateChange(state: AgentState) {
        _currentState.value = state
        when (state) {
            is AgentState.Idle -> {
                iteration = 0
                startTime = 0L
                addEvent(EventType.IDLE, "空闲", "")
            }
            is AgentState.Thinking -> {
                iteration = state.iteration
                if (startTime == 0L) startTime = System.currentTimeMillis()
                addEvent(EventType.THINKING, "第${state.iteration}轮 思考中", "请求模型…")
            }
            is AgentState.CallingTool -> {
                addEvent(EventType.CALLING_TOOL, "调用工具: ${state.toolName}", state.toolArgs)
            }
            is AgentState.WaitingConfirm -> {
                addEvent(EventType.WAITING_CONFIRM, "等待确认: ${state.toolName}", state.preview)
            }
            is AgentState.Executing -> {
                addEvent(EventType.EXECUTING, "执行: ${state.toolName}", "")
            }
            is AgentState.Responding -> {
                addEvent(EventType.RESPONDING, "完成回复", "${state.iteration}轮完成")
            }
            is AgentState.Error -> {
                addEvent(EventType.ERROR, "✗ ${state.message}", "after ${state.iteration} iterations")
                startTime = 0L
            }
            is AgentState.Interrupted -> {
                addEvent(EventType.INTERRUPTED, "已中断", "")
                startTime = 0L
            }
        }
    }

    private fun addEvent(type: EventType, label: String, detail: String) {
        val event = TimelineEvent(type = type, label = label, detail = detail)
        _events.value = _events.value + event
        Log.d(TAG, "Event: $label")
    }

    /** Clear timeline. */
    fun clear() {
        _events.value = emptyList()
        iteration = 0
        startTime = 0L
    }

    /** Serialize events to JSON for Room storage. */
    fun toJson(): String {
        val arr = org.json.JSONArray()
        _events.value.forEach { event ->
            val obj = org.json.JSONObject()
            obj.put("type", event.type.name)
            obj.put("label", event.label)
            obj.put("detail", event.detail)
            obj.put("iteration", iteration)
            arr.put(obj)
        }
        return arr.toString()
    }
}