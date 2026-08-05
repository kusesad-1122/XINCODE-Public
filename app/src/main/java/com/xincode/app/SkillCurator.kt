package com.xincode.app

import com.xincode.data.AppDatabase
import com.xincode.data.SkillEntity

/**
 * 技能 curator(受 Hermes curator 启发,但只做确定性清理,不开 LLM 开销)。
 *
 * 生命周期:active → stale → archived,按最近一次使用时间推进;一旦被 invoke_skill 命中,
 * [com.xincode.data.SkillDao.incrementUsage] 会把它复活回 active。pinned 的技能永不清理;
 * bundled 内置技能默认不清理(prune_builtins 开启才允许归档)。
 */
object SkillCurator {

    const val STATE_ACTIVE = "active"
    const val STATE_STALE = "stale"
    const val STATE_ARCHIVED = "archived"

    private const val KEY_ENABLED = "skill_curator_enabled"
    private const val KEY_STALE_DAYS = "skill_curator_stale_days"
    private const val KEY_ARCHIVE_DAYS = "skill_curator_archive_days"
    private const val KEY_PRUNE_BUILTINS = "skill_curator_prune_builtins"
    private const val KEY_LAST_RUN = "skill_curator_last_run"

    private const val DEFAULT_STALE_DAYS = 90L
    private const val DEFAULT_ARCHIVE_DAYS = 180L
    private const val RUN_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
    private const val DAY_MS = 24L * 60 * 60 * 1000

    data class SkillTransition(
        val id: Long,
        val name: String,
        val from: String,
        val to: String
    )

    /**
     * 纯函数:给定当前技能清单与时间,算出每个技能应该迁移到什么状态。
     * 返回需要变更的迁移列表;不变的不出现。
     */
    fun curatorTransitions(
        skills: List<SkillEntity>,
        now: Long,
        staleDays: Long = DEFAULT_STALE_DAYS,
        archiveDays: Long = DEFAULT_ARCHIVE_DAYS,
        pruneBuiltins: Boolean = false
    ): List<SkillTransition> {
        if (staleDays <= 0 || archiveDays <= staleDays) return emptyList()
        val out = mutableListOf<SkillTransition>()
        for (s in skills) {
            if (s.pinned) continue
            if (s.source == "bundled" && !pruneBuiltins) continue
            if (s.lastUsedAt <= 0) continue          // 从未使用过,不动
            if (s.state == STATE_ARCHIVED) continue   // 已归档,不反复归档
            val days = ((now - s.lastUsedAt).coerceAtLeast(0)) / DAY_MS
            val target = when {
                days >= archiveDays -> STATE_ARCHIVED
                days >= staleDays -> STATE_STALE
                else -> STATE_ACTIVE
            }
            if (target != s.state) {
                out += SkillTransition(s.id, s.name, s.state, target)
            }
        }
        return out
    }

    /**
     * 到点则跑一轮:默认 7 天一次,纯确定性迁移,失败不影响主流程。
     * @return 本次实际迁移条数;未到点或未启用返回 -1(便于区分"跑了但无事发生")。
     */
    suspend fun runIfDue(
        database: AppDatabase,
        now: Long = System.currentTimeMillis()
    ): Int {
        val settings = database.settingDao()
        val enabled = settings.get(KEY_ENABLED)?.toBooleanStrictOrNull() ?: true
        if (!enabled) return -1

        val lastRun = settings.get(KEY_LAST_RUN)?.toLongOrNull() ?: 0L
        if (lastRun > 0 && now - lastRun < RUN_INTERVAL_MS) return -1

        val staleDays = settings.get(KEY_STALE_DAYS)?.toLongOrNull() ?: DEFAULT_STALE_DAYS
        val archiveDays = settings.get(KEY_ARCHIVE_DAYS)?.toLongOrNull() ?: DEFAULT_ARCHIVE_DAYS
        val pruneBuiltins = settings.get(KEY_PRUNE_BUILTINS)?.toBooleanStrictOrNull() ?: false

        val transitions = curatorTransitions(
            skills = database.skillDao().getAll(),
            now = now,
            staleDays = staleDays,
            archiveDays = archiveDays,
            pruneBuiltins = pruneBuiltins
        )
        val dao = database.skillDao()
        transitions.forEach { t -> dao.setState(t.id, t.to, now) }
        settings.put(KEY_LAST_RUN, now.toString())
        return transitions.size
    }
}
