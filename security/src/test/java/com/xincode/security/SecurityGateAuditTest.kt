package com.xincode.security

import com.xincode.data.AuditLogDao
import com.xincode.data.AuditLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计读回 / 防篡改哈希链 / A-DEAD-2 权限求交 的安全闸门测试。
 * 风格对齐 [SecurityGateTest]:gate() 工厂 + decide(g, tool, args, mode) 辅助。
 *
 * 关键修复验证:
 *  - M3-7:getAuditTrail() 在「有 DAO」时能从 DAO 读回(修复前只读内存列表 → 永远返回空)。
 *  - M3-7:verifyAuditChain() 正常写入应 ok;篡改任意一条 payload 必须能定位断链。
 *  - A-DEAD-2:设置收窄的权威档后,原本 allow 的操作被拒;未设置时行为不变。
 */
class SecurityGateAuditTest {

    /** 内存假 DAO:实现 AuditLogDao 接口,纯内存存储,支持测试篡改。 */
    class FakeAuditLogDao : AuditLogDao {
        private val rows = mutableListOf<AuditLogEntity>()
        private var nextId = 1L

        override suspend fun insert(entry: AuditLogEntity): Long {
            val id = nextId++
            synchronized(rows) { rows.add(entry.copy(id = id)) }
            return id
        }

        override suspend fun getRecent(limit: Int): List<AuditLogEntity> =
            synchronized(rows) { rows.sortedByDescending { it.timestamp }.take(limit).toList() }

        override suspend fun getAll(): List<AuditLogEntity> =
            synchronized(rows) { rows.sortedBy { it.id }.toList() }

        override suspend fun deleteAll() = synchronized(rows) { rows.clear() }

        /** 测试辅助:破坏第 index 条记录的 decision(其余哈希链仍按原值算,必现断链)。 */
        fun tamper(index: Int, newDecision: String) = synchronized(rows) {
            val e = rows[index]
            rows[index] = e.copy(decision = newDecision)
        }

        fun size(): Int = synchronized(rows) { rows.size }
    }

    private fun gate(dao: AuditLogDao? = null) = SecurityGateImpl(dao)
    private fun shell(cmd: String) = "{\"command\":\"$cmd\"}"

    private fun decide(g: SecurityGateImpl, tool: String, args: String, mode: PermissionMode): Decision {
        val c = g.classify(tool, args)
        return g.decide(c, mode)
    }

    /** 等待异步审计写入落地(单线程 executor 串行)。 */
    private fun waitFor(dao: FakeAuditLogDao, n: Int, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (dao.size() < n && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private fun auditSome(g: SecurityGateImpl, mode: PermissionMode, count: Int) {
        for (i in 1..count) {
            val c = g.classify("file_read", "{\"path\":\"a$i.txt\"}")
            g.audit(c, g.decide(c, mode), "ok$i")
        }
    }

    // ===== M3-7:getAuditTrail() 修复(有 DAO 时能读回) =====

    @Test fun getAuditTrail_readsFromDao_whenDaoPresent() {
        val dao = FakeAuditLogDao()
        val g = gate(dao)
        g.setPermissionMode(PermissionMode.ALLOW_ALL)
        auditSome(g, PermissionMode.ALLOW_ALL, 3)
        waitFor(dao, 3)

        val trail = g.getAuditTrail()
        assertEquals(3, trail.size)
        assertTrue(trail.any { it.toolArgs.contains("a1.txt") })
        assertTrue(trail.any { it.toolArgs.contains("a3.txt") })
    }

    @Test fun getAuditTrail_empty_whenNoWrites() {
        val dao = FakeAuditLogDao()
        val g = gate(dao)
        assertTrue(g.getAuditTrail().isEmpty())
    }

    // ===== M3-7:防篡改哈希链 =====

    @Test fun verifyAuditChain_normal_isOk() {
        val dao = FakeAuditLogDao()
        val g = gate(dao)
        g.setPermissionMode(PermissionMode.ALLOW_ALL)
        auditSome(g, PermissionMode.ALLOW_ALL, 3)
        waitFor(dao, 3)

        val v = g.verifyAuditChain()
        assertTrue(v.ok)
        assertNull(v.brokenAt)
    }

    @Test fun verifyAuditChain_detectsTamperedPayload() {
        val dao = FakeAuditLogDao()
        val g = gate(dao)
        g.setPermissionMode(PermissionMode.ALLOW_ALL)
        auditSome(g, PermissionMode.ALLOW_ALL, 3)
        waitFor(dao, 3)

        // 篡改第 1 条(decision),哈希链必断。
        dao.tamper(0, "Denied: tampered by attacker")
        val v = g.verifyAuditChain()
        assertFalse(v.ok)
        assertEquals(0, v.brokenAt)
    }

    @Test fun verifyAuditChain_detectsMidChainTamper() {
        val dao = FakeAuditLogDao()
        val g = gate(dao)
        g.setPermissionMode(PermissionMode.ALLOW_ALL)
        auditSome(g, PermissionMode.ALLOW_ALL, 4)
        waitFor(dao, 4)

        // 篡改第 3 条,断链应定位在 index 2(基于按 id 升序的链序)。
        dao.tamper(2, "Denied: tampered middle")
        val v = g.verifyAuditChain()
        assertFalse(v.ok)
        assertEquals(2, v.brokenAt)
    }

    // ===== A-DEAD-2:权限求交接线 =====

    @Test fun authority_unset_behavior_unchanged() {
        val dao = FakeAuditLogDao()
        val g = gate(dao)
        g.setPermissionMode(PermissionMode.ALLOW_ALL)
        // 未设置权威档:file_write 到 /etc/passwd 在 ALLOW_ALL 下应为 Allow(与现状一致)。
        val d = decide(g, "file_write", "{\"path\":\"/etc/passwd\",\"content\":\"x\"}", PermissionMode.ALLOW_ALL)
        assertTrue(d is Decision.Allow)
    }

    @Test fun authority_narrows_allow_to_denied() {
        val dao = FakeAuditLogDao()
        val g = gate(dao)
        g.setPermissionMode(PermissionMode.ALLOW_ALL)
        g.setAuthorityProfile(PermissionProfile.readOnlyWorkspace("/workspace"))
        // 原本 ALLOW_ALL 下会 Allow 的 file_write,因超出只读围栏被拒(fail-closed)。
        val d = decide(g, "file_write", "{\"path\":\"/etc/passwd\",\"content\":\"x\"}", PermissionMode.ALLOW_ALL)
        assertTrue(d is Decision.Denied)
    }

    @Test fun authority_readOnly_denies_write_even_inside_workspace() {
        val dao = FakeAuditLogDao()
        val g = gate(dao)
        g.setPermissionMode(PermissionMode.ALLOW_ALL)
        g.setAuthorityProfile(PermissionProfile.readOnlyWorkspace("/workspace"))
        val d = decide(g, "file_write", "{\"path\":\"/workspace/x.txt\",\"content\":\"x\"}", PermissionMode.ALLOW_ALL)
        assertTrue(d is Decision.Denied)
    }

    @Test fun authority_allows_read_within_fence() {
        val dao = FakeAuditLogDao()
        val g = gate(dao)
        g.setPermissionMode(PermissionMode.ASK)
        g.setAuthorityProfile(PermissionProfile.readOnlyWorkspace("/workspace"))
        // file_read 在围栏内:base=Allow,围栏允许 → 仍 Allow。
        val d = decide(g, "file_read", "{\"path\":\"/workspace/a.txt\"}", PermissionMode.ASK)
        assertTrue(d is Decision.Allow)
    }
}
