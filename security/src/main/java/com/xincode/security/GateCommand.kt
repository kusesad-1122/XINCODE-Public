package com.xincode.security

/**
 * A command intercepted by the security gate, with classification metadata.
 *
 * Holds tool call fields inline to avoid depending on provider/core modules (no circular deps).
 */
data class GateCommand(
    /** Tool name (e.g. "shell_exec", "file_write"). */
    val toolName: String,
    /** Raw JSON arguments string from the model. */
    val toolArgs: String,
    /** Classified capability category. UNKNOWN if indeterminate. */
    val capability: Capability,
    /** Whether the command's effects are reversible. IRREVERSIBLE if indeterminate. */
    val reversibility: Reversibility,
    /** Human-readable explanation of the classification. */
    val why: String
)

/**
 * Result of the security gate decision.
 */
sealed class Decision {
    /** Command is allowed to execute without user interaction. */
    data class Allow(val reason: String) : Decision()

    /** Command requires user confirmation before execution. Must show preview. */
    data class NeedConfirm(
        val reason: String,
        val preview: String  // dry-run preview of what will happen
    ) : Decision()

    /** Command is denied. Reason will be fed back to the model. */
    data class Denied(val reason: String) : Decision()
}