package com.xincode.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移植自 Codex execpolicy 语义的回归单测。
 * 跑法:`./gradlew :security:testDebugUnitTest`。
 */
class ExecPolicyTest {

    @Test
    fun maxWins_forbiddenBeatsPromptBeatsAllow() {
        val policy = ExecPolicy()
            .addPrefixRule(listOf("rm"), ExecVerdict.FORBIDDEN, "删文件默认禁")
            .addPrefixRule(listOf("rm", "-i"), ExecVerdict.PROMPT, "交互式可问")
        // "rm -i x" 同时命中两条 → 最严(FORBIDDEN)胜。
        val got = policy.check(listOf("rm", "-i", "x"))
        assertEquals(ExecVerdict.FORBIDDEN, got.verdict)
        assertEquals(2, got.matchedRules.size)
    }

    @Test
    fun prefixRequiresFullLength() {
        val policy = ExecPolicy().addPrefixRule(listOf("git", "push"), ExecVerdict.PROMPT)
        assertTrue(policy.check(listOf("git", "push", "origin")).isMatch())
        // 前缀不够长不算命中。
        assertFalse(policy.check(listOf("git")).isMatch())
    }

    @Test
    fun alternativesMatchAny() {
        val policy = ExecPolicy().addPrefixRuleAlts(
            "git",
            listOf(PatternToken.Alternatives(listOf("push", "pull"))),
            ExecVerdict.PROMPT
        )
        assertTrue(policy.check(listOf("git", "push")).isMatch())
        assertTrue(policy.check(listOf("git", "pull")).isMatch())
        // 前缀命中即算(多余尾参不影响,执行时原样透传)。
        assertTrue(policy.check(listOf("git", "push", "--force", "x")).isMatch())
        assertFalse(policy.check(listOf("git", "clone")).isMatch())
    }

    @Test
    fun heuristicsFallback_onlyWhenNothingMatches() {
        val policy = ExecPolicy().addPrefixRule(listOf("ls"), ExecVerdict.ALLOW)
        val fallbackHit = policy.check(listOf("unknown_cmd"), { ExecVerdict.FORBIDDEN })
        assertEquals(ExecVerdict.FORBIDDEN, fallbackHit.verdict)
        assertFalse(fallbackHit.isMatch()) // 纯回退不算真实命中
        val realHit = policy.check(listOf("ls"), { ExecVerdict.FORBIDDEN })
        assertEquals(ExecVerdict.ALLOW, realHit.verdict)
        assertTrue(realHit.isMatch())
    }

    @Test
    fun noMatchNoFallback_defaultsPrompt() {
        // 与 Codex 不同但有意为之:Codex 此时 panic(要求调用方必给回退);
        // 这里默认 PROMPT(进确认框),对应既有闸门“拿不准就问”的哲学。
        val got = ExecPolicy().check(listOf("whatever"))
        assertEquals(ExecVerdict.PROMPT, got.verdict)
        assertFalse(got.isMatch())
    }

    @Test
    fun verdictMapsToExistingDecision() {
        assertTrue(ExecVerdict.ALLOW.toDecision("ok") is Decision.Allow)
        assertTrue(ExecVerdict.PROMPT.toDecision("why", "preview") is Decision.NeedConfirm)
        assertTrue(ExecVerdict.FORBIDDEN.toDecision("no") is Decision.Denied)
    }

    @Test
    fun networkHostNormalization() {
        assertEquals("example.com", normalizeNetworkHost("Example.COM."))
        assertEquals("example.com", normalizeNetworkHost("example.com:443"))
        assertEquals("::1", normalizeNetworkHost("[::1]"))
        try {
            normalizeNetworkHost("https://example.com/x")
            throw AssertionError("带 scheme 必须拒")
        } catch (_: IllegalArgumentException) {
        }
        try {
            normalizeNetworkHost("*.example.com")
            throw AssertionError("通配符必须拒")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun networkProtocolAliases() {
        assertEquals(NetworkProtocol.HTTPS, NetworkProtocol.parse("https_connect"))
        assertEquals(NetworkProtocol.HTTPS, NetworkProtocol.parse("http-connect"))
    }

    @Test
    fun compiledDomains_allowUnblocksForbiddenBlocksPromptIgnores() {
        val policy = ExecPolicy()
            .addNetworkRule("a.com", NetworkProtocol.HTTPS, ExecVerdict.ALLOW)
            .addNetworkRule("b.com", NetworkProtocol.HTTPS, ExecVerdict.FORBIDDEN)
            .addNetworkRule("c.com", NetworkProtocol.HTTPS, ExecVerdict.PROMPT)
            .addNetworkRule("a.com", NetworkProtocol.HTTPS, ExecVerdict.FORBIDDEN) // 后禁前允→进黑
        val (allowed, denied) = policy.compiledNetworkDomains()
        assertFalse(allowed.contains("a.com"))
        assertTrue(denied.containsAll(listOf("a.com", "b.com")))
        assertFalse(allowed.contains("c.com") || denied.contains("c.com"))
    }

    @Test
    fun overlayMergesWithoutOverride() {
        val base = ExecPolicy().addPrefixRule(listOf("ls"), ExecVerdict.ALLOW)
        val overlay = ExecPolicy().addPrefixRule(listOf("rm"), ExecVerdict.FORBIDDEN)
        val merged = base.mergeOverlay(overlay)
        assertEquals(ExecVerdict.ALLOW, merged.check(listOf("ls")).verdict)
        assertEquals(ExecVerdict.FORBIDDEN, merged.check(listOf("rm", "-rf")).verdict)
        // 原对象不动。
        assertFalse(base.check(listOf("rm")).isMatch())
    }

    @Test
    fun exampleSelfCheck_catchesMissingAndOverbroad() {
        val policy = ExecPolicy().addPrefixRule(listOf("git", "push"), ExecVerdict.PROMPT)
        policy.validateExamples(
            match = listOf(listOf("git", "push")),
            notMatch = listOf(listOf("git", "clone"))
        )
        try {
            policy.validateExamples(listOf(listOf("git", "clone")), emptyList())
            throw AssertionError("漏规则必须炸")
        } catch (_: IllegalArgumentException) {
        }
        try {
            policy.validateExamples(emptyList(), listOf(listOf("git", "push", "x")))
            throw AssertionError("过宽必须炸")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun tokenizeCommand_handlesQuotes() {
        assertEquals(listOf("git", "commit", "-m", "hello world"), tokenizeCommand("git commit -m \"hello world\""))
        assertEquals(listOf("echo", "a b"), tokenizeCommand("echo 'a b'"))
        assertEquals(listOf("ls", "-la"), tokenizeCommand("  ls   -la  "))
    }

    @Test
    fun allowedPrefixes_listsAllowOnly() {
        val policy = ExecPolicy()
            .addPrefixRule(listOf("ls"), ExecVerdict.ALLOW)
            .addPrefixRule(listOf("rm"), ExecVerdict.FORBIDDEN)
        assertEquals(listOf(listOf("ls")), policy.getAllowedPrefixes())
    }
}
