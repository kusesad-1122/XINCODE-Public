package com.xincode.app

import com.xincode.data.AppDatabase

/**
 * Hermes-⑤ 精编两文件记忆(受 Hermes MemoryStore 启发)。
 *
 * 与 XINCODE 已有的 `memories` FTS 表(海量、可检索的会话沉淀)互补:这里维护**两小段**被
 * 冻结进系统提示的高价值文本——
 *  - USER.md(耐久画像:用户是谁、偏好、对 agent 行为的期望),字数封顶 [USER_CAP]
 *  - MEMORY.md(近况:当前在做的事、临时上下文),字数封顶 [MEMORY_CAP]
 *
 * 存储:复用 key-value settings 表(无需新表/迁移)。条目用 [DELIM] 分隔。
 * 由「后台复盘分身」(Hermes-①)通过 save_memory 工具增/改/删,而非规则抽取。
 */
object CuratedMemory {
    const val KEY_USER = "curated.user"       // 耐久用户模型
    const val KEY_MEMORY = "curated.memory"   // 当前近况
    const val USER_CAP = 1375
    const val MEMORY_CAP = 2200
    private const val DELIM = "\n§\n"

    fun capFor(target: String): Int = if (target == "user") USER_CAP else MEMORY_CAP

    /**
     * 精编记忆也按项目隔离:全局(projectId=0)用基础 key,项目内用 `key.<pid>`。
     * 供 [CuratedMemoryScreen] 直读直写,故设为 public。
     */
    fun keyFor(target: String): String {
        val base = if (target == "user") KEY_USER else KEY_MEMORY
        val pid = com.xincode.tools.WorkspaceContext.projectId
        return if (pid == 0L) base else "$base.$pid"
    }

    suspend fun read(db: AppDatabase, target: String): String =
        db.settingDao().get(keyFor(target)) ?: ""

    private fun entries(raw: String): MutableList<String> =
        if (raw.isBlank()) mutableListOf()
        else raw.split(DELIM).map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    /** 追加一条(若已存在近似条目则跳过),写回并按字数封顶(超出丢最旧)。 */
    suspend fun add(db: AppDatabase, target: String, entry: String): String {
        val clean = entry.trim()
        if (clean.isEmpty()) return "空条目,忽略"
        val list = entries(read(db, target))
        if (list.any { it.equals(clean, ignoreCase = true) }) return "已存在相同条目"
        list.add(clean)
        writeCapped(db, target, list)
        return "已记入 ${target}(现 ${list.size} 条)"
    }

    /** 用 new 替换首个包含 oldSubstr 的条目;找不到则作为新增。 */
    suspend fun replace(db: AppDatabase, target: String, oldSubstr: String, new: String): String {
        val list = entries(read(db, target))
        val idx = list.indexOfFirst { it.contains(oldSubstr, ignoreCase = true) }
        if (idx < 0) { list.add(new.trim()); writeCapped(db, target, list); return "未找到匹配,已作为新条目追加" }
        list[idx] = new.trim()
        writeCapped(db, target, list)
        return "已替换 ${target} 第 ${idx + 1} 条"
    }

    /** 删除首个包含 substr 的条目。 */
    suspend fun remove(db: AppDatabase, target: String, substr: String): String {
        val list = entries(read(db, target))
        val idx = list.indexOfFirst { it.contains(substr, ignoreCase = true) }
        if (idx < 0) return "未找到可删除的匹配条目"
        list.removeAt(idx)
        writeCapped(db, target, list)
        return "已删除 ${target} 第 ${idx + 1} 条"
    }

    private suspend fun writeCapped(db: AppDatabase, target: String, list: MutableList<String>) {
        val cap = capFor(target)
        // 从尾部保留(新条目优先),累计不超过 cap 字数。
        val kept = ArrayDeque<String>()
        var total = 0
        for (e in list.asReversed()) {
            val add = e.length + DELIM.length
            if (total + add > cap && kept.isNotEmpty()) break
            kept.addFirst(e); total += add
        }
        db.settingDao().put(keyFor(target), kept.joinToString(DELIM))
    }
}
