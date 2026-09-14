package com.xincode.app

import com.xincode.core.AgentCoreContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 契约 §0.1:常量表必须与 AGENT-CORE-CONTRACT.md §附录「常量总表」以及桌面端
 * `src/services/toolLimits.ts` 逐字一致。本测试是防止双端漂移的机械手段 ——
 * 任何一端改了值,另一端跑这个测试就会红。
 */
class AgentCoreContractTest {

    // ───────────── §1 工具并发语义 ─────────────
    @Test fun maxParallelTools() = assertEquals(8, AgentCoreContract.MAX_PARALLEL_TOOLS)

    @Test fun concurrencySafeTools_whitelist() {
        val expected = setOf(
            "file_read", "list_dir", "glob", "grep", "code_graph",
            "current_time", "describe_image", "get_memory_by_title", "recall_memory",
            "transcribe_audio", "translate_text", "web_fetch", "web_search"
        )
        assertEquals(expected, AgentCoreContract.CONCURRENCY_SAFE_TOOLS)
    }

    // ───────────── §2 结果三层截断 ─────────────
    @Test fun maxResultCharsPerTool() = assertEquals(50_000, AgentCoreContract.MAX_RESULT_CHARS_PER_TOOL)
    @Test fun maxResultCharsPerMessage() = assertEquals(200_000, AgentCoreContract.MAX_RESULT_CHARS_PER_MESSAGE)
    @Test fun offloadThresholdChars() = assertEquals(50_000, AgentCoreContract.OFFLOAD_THRESHOLD_CHARS)
    @Test fun toolSummaryMaxChars() = assertEquals(50, AgentCoreContract.TOOL_SUMMARY_MAX_CHARS)
    @Test fun resultHeadRatio() = assertEquals(0.6, AgentCoreContract.RESULT_HEAD_RATIO, 1e-9)
    @Test fun resultTailRatio() = assertEquals(0.3, AgentCoreContract.RESULT_TAIL_RATIO, 1e-9)
    @Test fun offloadRetentionDays() = assertEquals(7L, AgentCoreContract.OFFLOAD_RETENTION_DAYS)
    @Test fun offloadDirName() = assertEquals("tool-results", AgentCoreContract.OFFLOAD_DIR_NAME)

    @Test fun offloadExempt_isFileRead() {
        assertEquals("file_read", AgentCoreContract.EXEMPT_OFFLOAD_TOOL)
        assertEquals(setOf("file_read"), AgentCoreContract.OFFLOAD_EXEMPT_TOOLS)
    }

    // ───────────── §3 单轮预算 ─────────────
    @Test fun maxToolCallsPerTurn() = assertEquals(25, AgentCoreContract.MAX_TOOL_CALLS_PER_TURN)
    @Test fun maxTurnsDefault() = assertEquals(60, AgentCoreContract.MAX_TURNS_DEFAULT)
    @Test fun turnTokenBudgetRatio() = assertEquals(0.5, AgentCoreContract.TURN_TOKEN_BUDGET_RATIO, 1e-9)
    @Test fun maxConsecutiveTruncations() = assertEquals(3, AgentCoreContract.MAX_CONSECUTIVE_TRUNCATIONS)
    @Test fun maxRepeatedToolErrors() = assertEquals(3, AgentCoreContract.MAX_REPEATED_TOOL_ERRORS)

    // ───────────── 旧 P1 compactToolResults 常量(共存) ─────────────
    @Test fun turnEndResultCapTokens() = assertEquals(3_000, AgentCoreContract.TURN_END_RESULT_CAP_TOKENS)
    @Test fun resultCompactCharsPerToken() = assertEquals(4, AgentCoreContract.RESULT_COMPACT_CHARS_PER_TOKEN)
    @Test fun resultCompactPrefixTokens() = assertEquals(1_500, AgentCoreContract.RESULT_COMPACT_PREFIX_TOKENS)
    @Test fun resultCompactSuffixTokens() = assertEquals(1_000, AgentCoreContract.RESULT_COMPACT_SUFFIX_TOKENS)

    // ───────────── 与桌面端 toolLimits.ts 的对齐检查 ─────────────
    @Test fun parityWithDesktopToolLimits() {
        // 桌面端: DEFAULT_MAX_RESULT_SIZE_CHARS=50_000 / MAX_TOOL_RESULTS_PER_MESSAGE_CHARS=200_000 / TOOL_SUMMARY_MAX_LENGTH=50
        assertEquals(50_000, AgentCoreContract.OFFLOAD_THRESHOLD_CHARS)
        assertEquals(200_000, AgentCoreContract.MAX_RESULT_CHARS_PER_MESSAGE)
        assertEquals(50, AgentCoreContract.TOOL_SUMMARY_MAX_CHARS)
        // 桌面端没有单工具 50000 的显式常量,但落盘阈值同为 50000,单工具硬上限与其一致
        assertEquals(AgentCoreContract.OFFLOAD_THRESHOLD_CHARS, AgentCoreContract.MAX_RESULT_CHARS_PER_TOOL)
        assertFalse("file_read 必须豁免 L3,否则会陷入二次读死循环",
            AgentCoreContract.OFFLOAD_EXEMPT_TOOLS.contains("file_read").not())
    }
}
