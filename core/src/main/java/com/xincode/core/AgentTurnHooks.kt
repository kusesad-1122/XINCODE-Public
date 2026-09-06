package com.xincode.core

/**
 * 步骤C:Turn 生命周期钩(方案 docs/CODEX-HARNESS优化方案.md)。
 *
 * core 只负责在 Turn 起止处回调,不碰数据库、不发事件:
 * 落库(HarnessThreads)与下行事件(AgentServer)由拥有方(app)在装配点实现。
 * 所有回调异常都由调用处吞掉(只记日志),钩子永远不能掀翻主循环。
 */
interface AgentTurnHooks {
    /** 一轮开始(输入已落 history 之后、首次调模型之前)。 */
    suspend fun onTurnStart(input: String)

    /**
     * 一轮结束。
     * @param ok 是否正常完成(超时/异常/中断均为 false)
     * @param summary 本轮最后一条助手文本(截断),供落库“摘要继承”。
     */
    suspend fun onTurnFinish(ok: Boolean, summary: String)
}
