package com.xincode.security

/**
 * Whether a command's effects can be undone.
 * IRREVERSIBLE commands always require preview + confirmation + snapshot.
 */
enum class Reversibility {
    REVERSIBLE,
    IRREVERSIBLE
}