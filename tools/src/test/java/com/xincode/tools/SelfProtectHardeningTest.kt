package com.xincode.tools

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 自我保护(问题 3):变量拼接指向受保护目录的命令必须拦住,且不能误伤正常区。
 *
 * 复现此前漏判:`chmod $(echo /data/data/com.xincode.app)/databases` 里的 `)` 把路径切开,
 * 字面 contains 匹配不到 `$base/databases`,于是放行。现复用 security 的 flattenForPathMatch
 * 通道(剥掉 $()/${}/反引号/分隔符,保留内部路径),覆盖这类绕过。
 */
class SelfProtectHardeningTest {

    private val pkgDir = "/data/data/com.xincode.app"

    @Before fun setUp() { SelfProtect.appDataDir = pkgDir }
    @After fun tearDown() { SelfProtect.appDataDir = "" }

    @Test fun variableConcat_splitByParen_isRefused() {
        assertNotNull(SelfProtect.refuseCommand("chmod $(echo /data/data/com.xincode.app)/databases"))
    }

    @Test fun variableConcat_plainSubstitution_isRefused() {
        assertNotNull(SelfProtect.refuseCommand("chmod $(echo /data/data/com.xincode.app/databases)"))
    }

    @Test fun braceVarExpansion_isRefused() {
        assertNotNull(SelfProtect.refuseCommand("chmod \${X}/databases/xincode.db"))
    }

    @Test fun userZeroPathForm_isRefused() {
        // /data/user/0/<pkg> 写法也要认。
        assertNotNull(SelfProtect.refuseCommand("chown root \$(echo /data/user/0/com.xincode.app)/shared_prefs"))
    }

    @Test fun ubuntuEnvStillAllowed() {
        // files/ubuntu 不在锁死区,部署时要 root 写整棵树,绝不能误伤。
        assertNull(SelfProtect.refuseCommand("chmod -R 755 /data/data/com.xincode.app/files/ubuntu"))
    }

    @Test fun workspaceStillAllowed() {
        assertNull(SelfProtect.refuseCommand("ls -la /storage/emulated/0/XINCODE"))
    }
}
