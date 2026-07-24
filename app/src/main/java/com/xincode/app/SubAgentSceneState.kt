package com.xincode.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 「智能体指挥室」像素动画的实时状态(Application 级)。由 [SubAgentTool] 在派活/执行/完成时更新,
 * [SubAgentScene] 读取渲染:能看到主脑 + 每个子智能体各自领了什么活、处于准备/执行/完成/失败。
 */
class SubAgentSceneState {
    enum class Status { PREPARING, RUNNING, DONE, FAILED }
    /**
     * @param activity 实时动作(执行中不断刷新的输出尾部,让用户点进去能看到它正在做什么)。
     * @param result   最终结论/产出(完成或失败后写入)。
     */
    data class Worker(
        val agent: String,
        val task: String,
        val status: Status,
        val activity: String = "",
        val result: String = ""
    )

    val workers: SnapshotStateList<Worker> = mutableStateListOf()
    /** 面板是否显示。 */
    var visible by mutableStateOf(false)
    /** 主脑是否还在"指挥/汇总"(有 worker 在跑时为 true)。 */
    var brainBusy by mutableStateOf(false)

    /** 开一场:登记所有分配,全部置为"准备"。 */
    fun begin(items: List<Pair<String, String>>) {
        workers.clear()
        items.forEach { workers.add(Worker(it.first, it.second, Status.PREPARING)) }
        visible = items.isNotEmpty()
        brainBusy = items.isNotEmpty()
    }

    fun setStatus(index: Int, status: Status) {
        if (index in workers.indices) workers[index] = workers[index].copy(status = status)
    }

    /** 执行中不断刷新的实时动作(输出尾部)。 */
    fun setActivity(index: Int, activity: String) {
        if (index in workers.indices) workers[index] = workers[index].copy(activity = activity)
    }

    /** 完成/失败后的最终结论。 */
    fun setResult(index: Int, result: String) {
        if (index in workers.indices) workers[index] = workers[index].copy(result = result)
    }

    /** 全部跑完:主脑停止忙碌,但保留最终画面直到下次 begin 或用户关闭。 */
    fun finishAll() { brainBusy = false }

    fun close() { workers.clear(); visible = false; brainBusy = false }
}
