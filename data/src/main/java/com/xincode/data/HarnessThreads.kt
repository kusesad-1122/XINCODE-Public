package com.xincode.data

/**
 * 步骤B:Thread/Turn 的写读口子(core 侧只调这里,不直调 DAO)。
 *
 * - [startThread]:新开一条任务档案。
 * - [startTurn]:开一轮;parent 自动挂到本 Thread 上一条 Turn(血缘不断)。
 * - [finishTurn]:收尾写回摘要+证据(下一轮继承的唯一口粮)。
 * - [buildInheritedContext]:把最近 N 轮 done Turn 压成“摘要+证据”文本,
 *   新 Turn 组装提示词时只带它,不带全文 —— B 步 token 下降的来源。
 */
class HarnessThreads(private val dao: HarnessThreadDao) {

    suspend fun startThread(sessionId: Long, goal: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertThread(
            HarnessThreadEntity(sessionId = sessionId, goal = goal, createdAt = now, updatedAt = now)
        )
    }

    /** 取该会话 active Thread,没有就按 goal 新建一条(调用方传本轮用户输入当 goal 即可)。 */
    suspend fun activeOrStart(sessionId: Long, goal: String): Long {
        return dao.activeThreadOfSession(sessionId)?.id ?: startThread(sessionId, goal)
    }

    suspend fun startTurn(threadId: Long, input: String): Long {
        val parent = dao.turnsOf(threadId).lastOrNull()?.id ?: 0L
        val now = System.currentTimeMillis()
        return dao.insertTurn(
            HarnessTurnEntity(threadId = threadId, parentTurnId = parent, input = input.take(500), createdAt = now, updatedAt = now)
        )
    }

    suspend fun finishTurn(id: Long, ok: Boolean, summary: String, evidence: String) {
        dao.finishTurn(
            id,
            if (ok) HarnessTurnEntity.STATUS_DONE else HarnessTurnEntity.STATUS_FAILED,
            summary.take(2000),
            evidence.take(1000)
        )
    }

    /**
     * 继承上下文:最近 [maxTurns] 轮 done/failed Turn 的摘要+证据,超 [maxChars] 从最老开始丢。
     * 返回空字符串表示无可继承(首轮)。
     */
    suspend fun buildInheritedContext(threadId: Long, maxTurns: Int = 5, maxChars: Int = 4000): String {
        val done = dao.turnsOf(threadId)
            .filter { it.status == HarnessTurnEntity.STATUS_DONE || it.status == HarnessTurnEntity.STATUS_FAILED }
            .takeLast(maxTurns.coerceAtLeast(1))
        if (done.isEmpty()) return ""
        val blocks = done.map { t ->
            val head = "【T${t.id}${if (t.status == HarnessTurnEntity.STATUS_FAILED) "/失败" else ""} ${t.input.take(120)}】"
            val body = t.summary.ifBlank { "(无摘要)" }
            val ev = t.evidence.takeIf { it.isNotBlank() }?.let { "\n证据:$it" } ?: ""
            "$head\n$body$ev"
        }
        // 超长从最老丢,至少保留最新一轮(继承不断档)。
        val kept = mutableListOf<String>()
        var total = 0
        for (b in blocks.asReversed()) {
            if (kept.isNotEmpty() && total + b.length > maxChars) break
            kept.add(0, b)
            total += b.length
        }
        return kept.joinToString("\n\n")
    }
}
