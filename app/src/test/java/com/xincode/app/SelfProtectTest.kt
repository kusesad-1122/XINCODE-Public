package com.xincode.app

import com.xincode.tools.SelfProtect
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 自我保护:AI 不能动 App 自己的运行时数据。
 *
 * 事故复盘:有用户让 AI 自行安装技能,AI 动到了 `databases/`,下次启动
 * `SQLiteCantOpenDatabaseException ... xincode.db is not readable`,
 * 数据本身一个字节没坏,坏的只是权限位 —— 但启动流程打不开就只能重建空库,
 * 会话/身份卡/供应商配置/记忆全部清零。
 *
 * 这组测试同时守两头:该拦的一定拦住,**不该拦的一定别拦**。
 * 内置 Ubuntu 环境住在 `files/ubuntu`,部署时本来就要 root 去 chmod 整棵树 ——
 * 保护写得太宽会把那个功能一起废掉,而那种破坏是悄无声息的。
 */
class SelfProtectTest {

    private val pkgDir = "/data/user/0/com.xincode.app"

    @Before fun setUp() { SelfProtect.appDataDir = pkgDir }
    @After fun tearDown() { SelfProtect.appDataDir = "" }

    @Test
    fun databasesDirIsProtected() {
        assertTrue(SelfProtect.isProtected("$pkgDir/databases"))
        assertTrue(SelfProtect.isProtected("$pkgDir/databases/xincode.db"))
        assertTrue(SelfProtect.isProtected("$pkgDir/databases/xincode.db-wal"))
        assertTrue(SelfProtect.isProtected("$pkgDir/shared_prefs/x.xml"))
        assertNotNull(SelfProtect.refuse("$pkgDir/databases/xincode.db"))
    }

    @Test
    fun traversalCannotSneakIn() {
        // files/../databases 规范化之后还是 databases。
        assertTrue(SelfProtect.isProtected("$pkgDir/files/../databases/xincode.db"))
    }

    @Test
    fun ubuntuEnvironmentStaysWritable() {
        // 内置 Ubuntu 部署要往 files/ubuntu 写、还要 root chmod 整棵树。
        // 这里一旦误拦,环境功能直接废掉。
        assertFalse(SelfProtect.isProtected("$pkgDir/files/ubuntu/bin/sh"))
        assertNull(SelfProtect.refuse("$pkgDir/files/ubuntu/bin/sh"))
        assertNull(SelfProtect.refuseCommand("chmod -R 755 $pkgDir/files/ubuntu"))
    }

    @Test
    fun workspaceIsNeverProtected() {
        assertFalse(SelfProtect.isProtected("/storage/emulated/0/XINCODE/a.md"))
        assertNull(SelfProtect.refuse("/storage/emulated/0/XINCODE/rooms/x/plan.md"))
    }

    @Test
    fun shellCommandTouchingDatabasesIsRefused() {
        // 事故里就是这类命令。两种私有目录写法都要认。
        assertNotNull(SelfProtect.refuseCommand("chmod 000 $pkgDir/databases/xincode.db"))
        assertNotNull(SelfProtect.refuseCommand("su -c 'chown root /data/data/com.xincode.app/databases/xincode.db'"))
        assertNotNull(SelfProtect.refuseCommand("rm -rf /data/user/0/com.xincode.app/shared_prefs"))
    }

    @Test
    fun ordinaryShellCommandsPassThrough() {
        assertNull(SelfProtect.refuseCommand("ls -la /storage/emulated/0/XINCODE"))
        assertNull(SelfProtect.refuseCommand("id"))
        assertNull(SelfProtect.refuseCommand("cat /proc/version"))
    }

    @Test
    fun commandTraversalIsNormalizedAndRefused() {
        // 命令里的 files/../databases 压平后还是 databases,不能靠字面 contains 蒙混过关。
        assertNotNull(SelfProtect.refuseCommand("rm -rf $pkgDir/files/../databases/xincode.db"))
        assertNotNull(SelfProtect.refuseCommand("cat $pkgDir/./shared_prefs/x.xml"))
    }

    @Test
    fun commandSeparatorVariantsAreRefused() {
        // 反斜杠与双斜杠写法规范化后同样命中,不分平台。
        assertNotNull(SelfProtect.refuseCommand("rm -rf $pkgDir\\databases\\xincode.db"))
        assertNotNull(SelfProtect.refuseCommand("rm -rf \"$pkgDir//databases//xincode.db\""))
    }

    @Test
    fun protectedPathSeparatorVariantsStayProtected() {
        assertTrue(SelfProtect.isProtected("$pkgDir\\databases\\xincode.db"))
        assertTrue(SelfProtect.isProtected("$pkgDir//databases//xincode.db"))
        // 分隔符写法变了也不许误伤正常区。
        assertFalse(SelfProtect.isProtected("$pkgDir\\files\\ubuntu\\bin\\sh"))
    }

    @Test
    fun guardIsInertUntilAppDirIsInjected() {
        // 没注入路径时必须【完全不拦】—— 空字符串前缀会匹配到所有绝对路径,
        // 那样单测和任何没走 Application 初始化的入口都会被莫名其妙地拒。
        SelfProtect.appDataDir = ""
        assertFalse(SelfProtect.isProtected("$pkgDir/databases/xincode.db"))
        assertNull(SelfProtect.refuseCommand("chmod 000 $pkgDir/databases/xincode.db"))
    }
}
