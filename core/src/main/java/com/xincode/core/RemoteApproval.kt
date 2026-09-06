package com.xincode.core

/**
 * 步骤E:远端审批桥(方案 docs/CODEX-HARNESS优化方案.md,对应视频 approval request)。
 *
 * 拥有方实现,通常经 AgentServer rendezvous(通知栏批复/自动化规则)回填。
 * core 只认这个接口,不依赖 service 模块,模块图不变。
 */
interface RemoteApprovalBridge {
    /**
     * 等待远端回执。
     * @return true=远端同意 false=远端拒绝 null=无回执(超时/无人等待,调用方继续等本地)。
     */
    suspend fun awaitRemote(toolName: String, preview: String, timeoutMs: Long): Boolean?
}
