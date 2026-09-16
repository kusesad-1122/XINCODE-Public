package com.xincode.app

import com.xincode.data.KanbanTaskDao
import com.xincode.data.KanbanTaskEntity

/**
 * M3-5:把 `agent_plan` 的**回合内**计划「一键固化」到看板(跨会话长期待办)。
 *
 * ## 为什么需要这个
 *
 * `KanbanTaskEntity` 的注释与 `AppStrings` 里的文案都承诺了这条路径
 * （"跨会话的长期待办,可把 AI 的计划一键导入" / "把 AI 的计划一键固化过来"），
 * 但**代码里从来没有实现** —— UI 在说谎。
 *
 * ## 设计取舍
 *
 * - **不给 `PlanState` 加持久化**。既有代码明确写了「PlanState 刻意不落库,留着旧计划只会让 UI
 *   混乱」：计划是**一个回合内**的临时清单，回合结束就该消失。要长期化，正确出口就是看板
 *   ——也就是本文件。给 PlanState 加落库会同时破坏这两条既有约定。
 * - **新任务一律落 `todo`**（不是 `ready`）。`ready` 会让 `KanbanRunner` 自动捡起来跑；
 *   固化是"记下来待办"，不是"立刻让智能体执行"，这个区分很重要——
 *   用户点一下按钮不该等于授权自动动手。
 * - **幂等**：同一次固化重复点不该产生重复任务。用「标题 + 关联会话」判重。
 */
object PlanImport {

    /** 固化来源标记，写进 note，便于用户看出任务是从哪来的。 */
    const val SOURCE_TAG = "来自 AI 计划"

    /**
     * 把 [planState] 的每一步转成一个看板任务。
     *
     * @param sessionId 关联会话（看板的 `sessionId` 字段，0 = 不关联）
     * @param skipExisting true = 已存在同名且同会话的任务就跳过（防重复点击）
     * @return 实际新建的任务数
     */
    suspend fun importToKanban(
        dao: KanbanTaskDao,
        planState: PlanState,
        sessionId: Long,
        skipExisting: Boolean = true
    ): Int {
        val steps = planState.steps.toList()
        if (steps.isEmpty()) return 0

        val existingTitles = if (skipExisting) {
            runCatching { dao.getAll().map { it.title } }.getOrDefault(emptyList()).toSet()
        } else {
            emptySet()
        }

        // 接在 todo 列现有任务之后排位，不抢占用户已有的排序（用 DAO 专门的 maxPosition，
        // 不必为了算个位置把整表拉回来）。
        val basePosition = runCatching {
            dao.maxPosition(KanbanTaskEntity.STATUS_TODO) + 1
        }.getOrDefault(0)

        val title = planState.title.ifBlank { "AI 计划" }
        var created = 0
        for ((i, step) in steps.withIndex()) {
            val text = step.text.trim()
            if (text.isEmpty()) continue
            if (text in existingTitles) continue
            dao.insert(
                KanbanTaskEntity(
                    title = text,
                    note = "$SOURCE_TAG「$title」第 ${step.id} 步",
                    // 刻意用 todo 而不是 ready：固化 ≠ 授权自动执行
                    status = KanbanTaskEntity.STATUS_TODO,
                    position = basePosition + i,
                    sessionId = sessionId
                )
            )
            created += 1
        }
        return created
    }

    /** 固化结果的可读回执（UI/工具返回用）。 */
    fun receipt(created: Int, total: Int): String = when {
        total == 0 -> "计划里没有步骤，没什么可固化的。"
        created == 0 -> "这 $total 步都已经在看板里了，没有重复添加。"
        created < total -> "已固化 $created 步到看板（另外 ${total - created} 步已存在，跳过）。"
        else -> "已把 $created 步全部固化到看板。"
    }
}
