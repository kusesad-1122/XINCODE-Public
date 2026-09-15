package com.xincode.security

/**
 * Security gate: classifies commands, determines risk level,
 * and decides whether to allow/deny/confirm based on permission mode.
 */
interface SecurityGate {

    /** Set the current permission mode. Persisted by caller. */
    fun setPermissionMode(mode: PermissionMode)

    /** Get the current permission mode. */
    fun getPermissionMode(): PermissionMode

    /**
     * Classify a tool call into a [GateCommand].
     * For shell_exec, parses the command string. For su_exec, always IRREVERSIBLE+SYSTEM.
     */
    fun classify(toolName: String, toolArgs: String): GateCommand

    /**
     * Classify a command/operation into a [RiskLevel].
     * Used by decide() to determine FATAL_BANNED / DANGEROUS / NORMAL.
     */
    fun classifyRisk(command: String): RiskLevel

    /**
     * Decide what to do with a classified command, considering current permission mode.
     */
    fun decide(cmd: GateCommand, mode: PermissionMode): Decision

    /** Generate a dry-run preview for a command. */
    fun preview(cmd: GateCommand): String

    /** Record an audit entry for every gate decision. */
    fun audit(cmd: GateCommand, decision: Decision, result: String?)

    /** Retrieve audit trail. */
    fun getAuditTrail(): List<AuditEntry>

    /** gap-12:注入持久化的 allow/deny 权限规则快照(default no-op,便于测试替身)。 */
    fun setPermissionRules(rules: List<com.xincode.data.PermissionRuleEntity>) {}

    /** A-DEAD-2:设置权威权限围栏(默认 no-op,不设置 = 不影响现有行为)。 */
    fun setAuthorityProfile(profile: PermissionProfile?) {}
}

/** 审计哈希链校验结果。 */
data class AuditChainVerification(
    /** 链是否完整(无篡改、无断链)。没有 DAO 时视为无数据可篡改,返回 ok=true。 */
    val ok: Boolean,
    /** 断链/被篡改的位置(0 基);ok=true 时为 null。 */
    val brokenAt: Int? = null
)

/** Immutable audit record. */
data class AuditEntry(
    val timestamp: Long,
    val toolName: String,
    val toolArgs: String,
    val capability: Capability,
    val reversibility: Reversibility,
    val decision: String,
    val result: String?
)