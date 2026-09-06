package com.xincode.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 步骤B:Thread —— 一条长期任务档案(视频 “CODEX 如何找到任务归属”)。
 *
 * Thread 保存整件工作的归属与历史;Turn 是其中一轮执行。
 * 例:修登录 Bug 是一条 Thread,T1 调查根因、T2 按证据改码是两个 Turn,T2 继承 T1 的结论。
 *
 * 状态字符串而非枚举序号:以后加状态不会让老数据错位(沿用 KanbanTaskEntity 规约)。
 */
@Entity(tableName = "harness_threads", indices = [Index("sessionId")])
data class HarnessThreadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 归属会话;0 = 不关联(后台/定时任务)。 */
    val sessionId: Long = 0,
    /** 任务一句话目标,如“修复登录 Bug”。 */
    val goal: String = "",
    /** active / done / archived */
    val status: String = STATUS_ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_DONE = "done"
        const val STATUS_ARCHIVED = "archived"
    }
}

/**
 * 步骤B:Turn —— Thread 里当前/历史的一轮执行。
 *
 * 新 Turn 只带“前 Turn 摘要+关键证据”,不再重灌全文,这是 token 下降的关键。
 * Fork(G 步)即以某已完成 Turn 为 parent 另起分支;parent 链即血缘。
 */
@Entity(
    tableName = "harness_turns",
    indices = [Index("threadId"), Index("parentTurnId")]
)
data class HarnessTurnEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val threadId: Long,
    /** 父 Turn id;0 = 本 Thread 首轮。 */
    val parentTurnId: Long = 0,
    /** running / waiting_tool / waiting_approval / done / failed / cancelled */
    val status: String = STATUS_RUNNING,
    /** 本轮用户输入(短存,长输入只留摘要,全文在 messages)。 */
    val input: String = "",
    /** 本轮结论摘要 —— 给下一轮继承,非全文。 */
    val summary: String = "",
    /** 关键证据(文件路径/错误指纹/版本号),给下一轮定位。 */
    val evidence: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_WAITING_TOOL = "waiting_tool"
        const val STATUS_WAITING_APPROVAL = "waiting_approval"
        const val STATUS_DONE = "done"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        /** 还占着执行位的状态(恢复/防重入用)。 */
        val OPEN_STATUSES = setOf(STATUS_RUNNING, STATUS_WAITING_TOOL, STATUS_WAITING_APPROVAL)
    }
}
