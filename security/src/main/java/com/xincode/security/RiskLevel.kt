package com.xincode.security

/**
 * Risk classification for a command.
 * Determines whether the command is banned, dangerous, or normal.
 */
enum class RiskLevel {
    /** Fatally banned — always rejected, no override possible. */
    FATAL_BANNED,
    /** Dangerous — requires confirmation unless in ALLOW_ALL mode. */
    DANGEROUS,
    /** Normal — follows the current permission mode. */
    NORMAL
}
