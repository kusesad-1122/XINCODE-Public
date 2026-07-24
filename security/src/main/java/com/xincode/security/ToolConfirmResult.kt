package com.xincode.security

/**
 * User's response to a tool confirmation card.
 */
enum class ToolConfirmResult {
    /** User rejected the tool call. */
    DENY,
    /** User allows this one call only. */
    ALLOW_ONCE,
    /** User allows all calls of this tool type for this session. */
    ALWAYS_ALLOW
}