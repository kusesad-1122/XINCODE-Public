package com.xincode.app

import com.xincode.data.SkillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillCuratorTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun skill(
        id: Long,
        name: String,
        lastUsedAt: Long,
        state: String = SkillCurator.STATE_ACTIVE,
        source: String = "agent",
        pinned: Boolean = false
    ) = SkillEntity(
        id = id, name = name, description = "", content = "",
        source = source, useCount = 1, lastUsedAt = lastUsedAt, state = state, pinned = pinned
    )

    @Test
    fun activeMovesToStaleThenArchivedByInactivity() {
        val transitions = SkillCurator.curatorTransitions(
            skills = listOf(
                skill(1, "旧技能", now - 100 * day),
                skill(2, "半旧技能", now - 120 * day),
                skill(3, "新技能", now - 10 * day)
            ),
            now = now,
            staleDays = 90,
            archiveDays = 180
        )
        assertEquals(
            mapOf(1L to SkillCurator.STATE_STALE, 2L to SkillCurator.STATE_STALE),
            transitions.associate { it.id to it.to }
        )
    }

    @Test
    fun archivedAfterLongInactivityAndRecentRevives() {
        val transitions = SkillCurator.curatorTransitions(
            skills = listOf(
                skill(1, "老到归档", now - 200 * day),
                skill(2, "刚用回", now - 1 * day, state = SkillCurator.STATE_STALE)
            ),
            now = now,
            staleDays = 90,
            archiveDays = 180
        )
        assertEquals(
            mapOf(1L to SkillCurator.STATE_ARCHIVED, 2L to SkillCurator.STATE_ACTIVE),
            transitions.associate { it.id to it.to }
        )
    }

    @Test
    fun pinnedBundledAndUnusedAreProtected() {
        val transitions = SkillCurator.curatorTransitions(
            skills = listOf(
                skill(1, "固定技能", now - 300 * day, pinned = true),
                skill(2, "内置技能", now - 300 * day, source = "bundled"),
                skill(3, "从未使用", 0L)
            ),
            now = now,
            staleDays = 90,
            archiveDays = 180
        )
        assertTrue(transitions.isEmpty())

        // prune_builtins 打开后,内置技能也会进入清理
        val withBuiltins = SkillCurator.curatorTransitions(
            skills = listOf(skill(2, "内置技能", now - 300 * day, source = "bundled")),
            now = now,
            staleDays = 90,
            archiveDays = 180,
            pruneBuiltins = true
        )
        assertEquals(SkillCurator.STATE_ARCHIVED, withBuiltins.single().to)
    }

    @Test
    fun archivedStateIsNotTouchedAgain() {
        val transitions = SkillCurator.curatorTransitions(
            skills = listOf(
                skill(1, "已归档", now - 400 * day, state = SkillCurator.STATE_ARCHIVED)
            ),
            now = now,
            staleDays = 90,
            archiveDays = 180
        )
        assertTrue(transitions.isEmpty())
    }
}
