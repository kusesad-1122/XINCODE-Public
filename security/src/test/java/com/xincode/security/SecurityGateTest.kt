package com.xincode.security

import com.xincode.data.PermissionRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * gap-12/13/14/15 安全闸门决策单测(纯逻辑,可 FAIL)。
 */
class SecurityGateTest {

    private fun gate() = SecurityGateImpl(null)
    private fun shell(cmd: String) = "{\"command\":\"$cmd\"}"

    private fun decide(g: SecurityGateImpl, tool: String, args: String, mode: PermissionMode): Decision {
        val c = g.classify(tool, args)
        return g.decide(c, mode)
    }

    // 允许全部(全自动):危险 + 不可逆操作不再直接放行 —— 按契约 §5.3 红线,至少确认一次。
    // 这是有意的语义变更:旧语义为"危险命令也直接放行",与 §5.3 冲突,现改为 NeedConfirm。
    @Test fun allowAll_dangerous_needsConfirm() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("rm -rf /data/data/x"), PermissionMode.ALLOW_ALL) is Decision.NeedConfirm)
    }

    @Test fun allowAll_normal_allows() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("echo hi"), PermissionMode.ALLOW_ALL) is Decision.Allow)
    }

    @Test fun fatal_alwaysDenied() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("rm -rf /"), PermissionMode.ALLOW_ALL) is Decision.Denied)
    }

    // gap-13:ASK 下只读安全命令自动放行,非安全命令需确认。
    @Test fun ask_safeCommand_allows() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("ls -la"), PermissionMode.ASK) is Decision.Allow)
        assertTrue(decide(g, "shell_exec", shell("git status"), PermissionMode.ASK) is Decision.Allow)
    }

    @Test fun ask_chainWithUnsafe_needsConfirm() {
        val g = gate()
        assertTrue(decide(g, "shell_exec", shell("ls && rm foo"), PermissionMode.ASK) is Decision.NeedConfirm)
    }

    @Test fun ask_readOnlyTool_allows() {
        val g = gate()
        assertTrue(decide(g, "file_read", "{\"path\":\"a.txt\"}", PermissionMode.ASK) is Decision.Allow)
        assertTrue(decide(g, "grep", "{\"pattern\":\"x\"}", PermissionMode.ASK) is Decision.Allow)
    }

    // gap-15:只读模式放行只读、拒绝写。
    @Test fun readOnly_denies_write_allows_read() {
        val g = gate()
        assertTrue(decide(g, "file_write", "{\"path\":\"a\",\"content\":\"b\"}", PermissionMode.READ_ONLY) is Decision.Denied)
        assertTrue(decide(g, "file_read", "{\"path\":\"a\"}", PermissionMode.READ_ONLY) is Decision.Allow)
    }

    // gap-12:deny 规则即使 ALLOW_ALL 也拒;allow 规则跳过确认;deny > allow。
    @Test fun rule_deny_overrides_allowAll() {
        val g = gate()
        g.setPermissionRules(listOf(PermissionRuleEntity(action = "deny", toolFilter = "shell_exec", pattern = "*rm*", createdAt = 0)))
        assertTrue(decide(g, "shell_exec", shell("rm foo"), PermissionMode.ALLOW_ALL) is Decision.Denied)
    }

    @Test fun rule_allow_skips_confirm_in_ask() {
        val g = gate()
        g.setPermissionRules(listOf(PermissionRuleEntity(action = "allow", toolFilter = "shell_exec", pattern = "*npm*", createdAt = 0)))
        assertTrue(decide(g, "shell_exec", shell("npm install"), PermissionMode.ASK) is Decision.Allow)
    }

    // ===== §5.4 解析差异防御:引号/转义/IFS 绕过必须判定为不安全 =====

    // 'rm' '-rf' '/' —— 加引号后子串匹配会漏,但 tokenize 后应还原成 rm -rf /。
    @Test fun bypass_quotedTokens_isFatal() {
        val g = gate()
        assertEquals(RiskLevel.FATAL_BANNED, g.classifyRisk("'rm' '-rf' '/'"))
        assertTrue(decide(g, "shell_exec", shell("'rm' '-rf' '/'"), PermissionMode.ASK) is Decision.Denied)
        assertTrue(decide(g, "shell_exec", shell("'rm' '-rf' '/'"), PermissionMode.ALLOW_ALL) is Decision.Denied)
    }

    // r''m -rf / —— 空引号拼接,词法上仍是 rm。
    @Test fun bypass_emptyQuoteConcat_isFatal() {
        val g = gate()
        assertEquals(RiskLevel.FATAL_BANNED, g.classifyRisk("r''m -rf /"))
        assertTrue(decide(g, "shell_exec", shell("r''m -rf /"), PermissionMode.ALLOW_ALL) is Decision.Denied)
    }

    // rm${IFS}-rf${IFS}/ —— IFS 拼接,没有空格但语义仍是 rm -rf /。
    @Test fun bypass_ifsConcat_isFatal() {
        val g = gate()
        assertEquals(RiskLevel.FATAL_BANNED, g.classifyRisk("rm\${IFS}-rf\${IFS}/"))
        assertTrue(decide(g, "shell_exec", shell("rm\${IFS}-rf\${IFS}/"), PermissionMode.ALLOW_ALL) is Decision.Denied)
    }

    // ; rm -rf / —— 命令分隔符后面的致命命令也要命中。
    @Test fun bypass_semicolon_prefix_isFatal() {
        val g = gate()
        assertEquals(RiskLevel.FATAL_BANNED, g.classifyRisk("; rm -rf /"))
        assertTrue(decide(g, "shell_exec", shell("; rm -rf /"), PermissionMode.ALLOW_ALL) is Decision.Denied)
    }

    // ls | xargs rm —— 管道拼接不得被静默放行(至少确认)。
    @Test fun bypass_pipe_concat_notAllowed() {
        val g = gate()
        val d = decide(g, "shell_exec", shell("ls | xargs rm"), PermissionMode.ALLOW_ALL)
        assertTrue(d is Decision.NeedConfirm)
    }

    // ALLOW_ALL 下 shell_exec 执行 dd 写块设备:必须不是直接放行(应为 Denied/NeedConfirm)。
    @Test fun allowAll_dd_blockDevice_notAllowed() {
        val g = gate()
        val d = decide(g, "shell_exec", shell("dd if=/dev/zero of=/dev/block/sda"), PermissionMode.ALLOW_ALL)
        assertTrue(d !is Decision.Allow)
    }

    // ===== 回归:正常命令不得被误判为 FATAL_BANNED,且在 ALLOW_ALL 下直接放行 =====

    @Test fun regression_normalCommands_notFatal_and_allowed() {
        val g = gate()
        val normals = listOf(
            "gradle assembleDebug",
            "adb devices",
            "git status",
            "ls -la",
            "cat a.txt",
            "find . -name '*.kt'",
            "grep -rn foo ."
        )
        for (cmd in normals) {
            assertEquals("命令应被判 NORMAL: $cmd", RiskLevel.NORMAL, g.classifyRisk(cmd))
            val d = decide(g, "shell_exec", shell(cmd), PermissionMode.ALLOW_ALL)
            assertTrue("ALLOW_ALL 下应直接放行: $cmd -> $d", d is Decision.Allow)
        }
    }
}
