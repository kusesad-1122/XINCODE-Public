package com.xincode.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1-4 历史窗口裁剪回归。
 *
 * 重点不是"裁得掉"，而是**裁得安全**：从中间切会把 `assistant.tool_calls` 与其 `tool`
 * 结果拆散 → 整段历史非法 → HTTP 400。所以只允许在 user 边界切。
 */
class HistoryWindowTest {

    /** 构造 [rounds] 个回合，每回合 = user + assistant(带1个tool_calls) + tool。 */
    private fun history(rounds: Int): MutableList<JSONObject> {
        val list = mutableListOf<JSONObject>()
        list.add(JSONObject().put("role", "system").put("content", "SYS"))
        for (i in 1..rounds) {
            list.add(JSONObject().put("role", "user").put("content", "问题$i"))
            list.add(
                JSONObject().put("role", "assistant").put("content", "")
                    .put("tool_calls", JSONArray().put(
                        JSONObject().put("id", "call_$i").put("type", "function")
                            .put("function", JSONObject().put("name", "file_read").put("arguments", "{}"))
                    ))
            )
            list.add(JSONObject().put("role", "tool").put("tool_call_id", "call_$i").put("content", "结果$i"))
        }
        return list
    }

    /** 后缀自洽性：每个带 tool_calls 的 assistant，其后必须紧跟全部配对的 tool。 */
    private fun assertPairsIntact(list: List<JSONObject>) {
        var i = 0
        while (i < list.size) {
            val m = list[i]
            val calls = if (m.optString("role") == "assistant") m.optJSONArray("tool_calls") else null
            if (calls != null && calls.length() > 0) {
                val need = (0 until calls.length()).map { calls.getJSONObject(it).optString("id") }.toSet()
                val got = mutableSetOf<String>()
                var j = i + 1
                while (j < list.size && list[j].optString("role") == "tool") {
                    got.add(list[j].optString("tool_call_id")); j++
                }
                assertTrue("assistant@$i 的 tool_calls 没配对完整: need=$need got=$got", got.containsAll(need))
            }
            i++
        }
    }

    @Test
    fun underBudget_isNotTrimmed() {
        val msgs = history(5) // 1 + 15 = 16 条，远小于 60
        val before = msgs.size
        assertEquals(0, HistoryWindow.trim(msgs, maxMessages = 60, maxChars = 24_000))
        assertEquals(before, msgs.size)
    }

    @Test
    fun overMessageLimit_trimsAtUserBoundaryAndKeepsSystem() {
        val msgs = history(20) // 1 + 60 = 61 条
        val removed = HistoryWindow.trim(msgs, maxMessages = 20, maxChars = Int.MAX_VALUE)
        assertTrue("应该裁掉一些，实际 $removed", removed > 0)
        assertEquals("system 必须永远在", "system", msgs[0].optString("role"))
        assertTrue("裁后条数应进窗口: ${msgs.size}", msgs.size <= 20)
        assertPairsIntact(msgs)
    }

    @Test
    fun cutStartsAtUser_neverMidTurn() {
        val msgs = history(20)
        HistoryWindow.trim(msgs, maxMessages = 20, maxChars = Int.MAX_VALUE)
        // 紧邻 system 之后的第一条必须是一轮的起点(user)，不能是 tool / assistant
        assertEquals("user", msgs[1].optString("role"))
    }

    @Test
    fun overCharLimit_trimsByChars() {
        val msgs = mutableListOf<JSONObject>()
        msgs.add(JSONObject().put("role", "system").put("content", "SYS"))
        for (i in 1..10) {
            msgs.add(JSONObject().put("role", "user").put("content", "u".repeat(2000)))
            msgs.add(JSONObject().put("role", "assistant").put("content", "a".repeat(2000)))
        }
        val removed = HistoryWindow.trim(msgs, maxMessages = Int.MAX_VALUE, maxChars = 6000)
        assertTrue("按字符预算应当裁掉: $removed", removed > 0)
        val totalChars = msgs.drop(1).sumOf { it.optString("content").length }
        assertTrue("裁后字符数应进预算: $totalChars", totalChars <= 6000)
        assertEquals("user", msgs[1].optString("role"))
    }

    @Test
    fun noUserBoundary_keepsEverything() {
        // 只有 system + 一条 user + 大量 assistant（没有第二个可用 user 切点）
        val msgs = mutableListOf<JSONObject>()
        msgs.add(JSONObject().put("role", "system").put("content", "SYS"))
        msgs.add(JSONObject().put("role", "user").put("content", "唯一一句"))
        repeat(30) { msgs.add(JSONObject().put("role", "assistant").put("content", "x".repeat(1000))) }
        val removed = HistoryWindow.trim(msgs, maxMessages = 5, maxChars = 1000)
        assertEquals("找不到 user 边界时宁可不裁（安全兜底）", 0, removed)
    }

    @Test
    fun tinyHistory_isUntouched() {
        val msgs = history(1)
        assertEquals(0, HistoryWindow.trim(msgs, maxMessages = 1, maxChars = 1))
        assertEquals(4, msgs.size)
    }

    @Test
    fun systemPromptIsNeverDropped() {
        val msgs = history(20)
        HistoryWindow.trim(msgs, maxMessages = 2, maxChars = 10)
        assertEquals("system", msgs[0].optString("role"))
        assertEquals("SYS", msgs[0].optString("content"))
    }

    @Test
    fun repeatedTrimIsIdempotent() {
        val msgs = history(30)
        HistoryWindow.trim(msgs, maxMessages = 20, maxChars = Int.MAX_VALUE)
        val afterFirst = msgs.size
        val second = HistoryWindow.trim(msgs, maxMessages = 20, maxChars = Int.MAX_VALUE)
        assertEquals("已经进窗口后不该继续裁", 0, second)
        assertEquals(afterFirst, msgs.size)
    }

    @Test
    fun degenerateInputs_doNotCrash() {
        val empty = mutableListOf<JSONObject>()
        assertEquals(0, HistoryWindow.trim(empty))
        val onlySystem = mutableListOf(JSONObject().put("role", "system").put("content", "S"))
        assertEquals(0, HistoryWindow.trim(onlySystem))
        assertEquals(1, onlySystem.size)
        val msgs = history(3)
        assertEquals(0, HistoryWindow.trim(msgs, maxMessages = 0))
        assertEquals(0, HistoryWindow.trim(msgs, maxChars = 0))
    }

    @Test
    fun contractConstantsMatchContract() {
        assertEquals(60, AgentCoreContract.HISTORY_WINDOW_MAX_MESSAGES)
        assertEquals(24_000, AgentCoreContract.HISTORY_WINDOW_MAX_CHARS)
        assertTrue(AgentCoreContract.HISTORY_TRIM_AT_USER_BOUNDARY)
        // 60 条 = 30 回合，与桌面端 HISTORY_MAX_TURNS_DEFAULT 对齐
        assertEquals(30, AgentCoreContract.HISTORY_WINDOW_MAX_MESSAGES / 2)
    }
}
