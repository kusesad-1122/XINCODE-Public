package com.xincode.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局运行统计(进程级):子智能体累计 token / 运行次数等无法从主会话消息表直接算出的量。
 * 主会话的上下文 token、各类工具调用次数则从 Room 消息表实时统计(见 [StatsScreen])。
 */
object AgentStats {
    /** 子智能体累计消耗 token(prompt+completion,跨所有 dispatch)。 */
    var subAgentTokens by mutableStateOf(0L)
        private set
    /** 子智能体累计运行次数。 */
    var subAgentRuns by mutableStateOf(0)
        private set

    // L2 修复:@Synchronized —— 最多 4 个子智能体并发回填,非原子 RMW 会丢计数。
    @Synchronized
    fun addSubAgentRun(promptTokens: Long, completionTokens: Long) {
        subAgentRuns += 1
        subAgentTokens += (promptTokens.coerceAtLeast(0) + completionTokens.coerceAtLeast(0))
    }

    fun reset() { subAgentTokens = 0L; subAgentRuns = 0 }
}
