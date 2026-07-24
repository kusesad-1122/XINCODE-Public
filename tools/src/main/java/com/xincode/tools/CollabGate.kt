package com.xincode.tools

/**
 * 「协作模式」总开关。开启后:系统提示强化「主脑+子智能体」协作 —— 对复杂/可并行的任务
 * 优先用 dispatch_agents 把子任务分派给子智能体并行处理,再汇总。默认关闭(普通对话)。
 */
object CollabGate {
    @Volatile
    var enabled: Boolean = false
}
