package com.xincode.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * usage 解析测试。
 *
 * 这块错了的表现是「统计悄悄少算」或者「页面永远空着」—— 不报错、不崩溃,
 * 用户只会觉得这功能坏了但说不出哪坏了。各家字段名又都不一样,靠肉眼比对迟早出事。
 *
 * 下面每个用例的 JSON 都照着各家真实返回写。
 */
class UsageParseTest {

    @Test
    fun parsesOpenAiShape() {
        val u = JSONObject("""{"prompt_tokens":1200,"completion_tokens":340,"total_tokens":1540}""")
        val p = UsageRecorder.parse(u)
        assertNotNull(p)
        assertEquals(1200L, p!!.input)
        assertEquals(340L, p.output)
        assertEquals(0L, p.cacheRead)
    }

    @Test
    fun parsesDeepSeekCacheFields() {
        // DeepSeek: prompt_tokens 是【含】缓存命中的,要扣掉才不会算两遍
        val u = JSONObject("""
            {"prompt_tokens":1000,"completion_tokens":200,
             "prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200}
        """.trimIndent())
        val p = UsageRecorder.parse(u)!!
        assertEquals("缓存命中要从输入里扣掉", 200L, p.input)
        assertEquals(800L, p.cacheRead)
    }

    @Test
    fun parsesGenericCachedTokens() {
        val u = JSONObject("""
            {"prompt_tokens":500,"completion_tokens":100,
             "prompt_tokens_details":{"cached_tokens":300}}
        """.trimIndent())
        val p = UsageRecorder.parse(u)!!
        assertEquals(200L, p.input)
        assertEquals(300L, p.cacheRead)
    }

    @Test
    fun anthropicInputDoesNotIncludeCache() {
        // Anthropic 的 input_tokens【不含】缓存,provider 层标了 input_includes_cache=false。
        // 扣了就会把本来就不含缓存的输入再砍一刀,统计直接归零。
        val u = JSONObject("""
            {"prompt_tokens":150,"completion_tokens":80,
             "cache_read_input_tokens":2000,"cache_creation_input_tokens":500,
             "input_includes_cache":false}
        """.trimIndent())
        val p = UsageRecorder.parse(u)!!
        assertEquals("Anthropic 的输入不该被扣减", 150L, p.input)
    }

    @Test
    fun parsesReasoningTokens() {
        val u = JSONObject("""
            {"prompt_tokens":100,"completion_tokens":900,
             "completion_tokens_details":{"reasoning_tokens":700}}
        """.trimIndent())
        assertEquals(700L, UsageRecorder.parse(u)!!.reasoning)
    }

    @Test
    fun allZeroUsageIsSkipped() {
        // 流式最后一帧常给个空 usage,记下来只会污染统计
        assertNull(UsageRecorder.parse(JSONObject("""{"prompt_tokens":0,"completion_tokens":0}""")))
        assertNull(UsageRecorder.parse(JSONObject("{}")))
    }

    @Test
    fun cacheOnlyUsageIsKept() {
        // 输入输出都是 0 但缓存读了一大堆 —— 这是真花过钱的,不能当空帧丢掉。
        // 之前的判断只看 input/output,这种记录会被静默扔掉。
        val u = JSONObject("""
            {"prompt_tokens":0,"completion_tokens":0,"prompt_cache_hit_tokens":5000}
        """.trimIndent())
        val p = UsageRecorder.parse(u)
        assertNotNull("只有缓存读的记录必须留下", p)
        assertEquals(5000L, p!!.cacheRead)
    }

    @Test
    fun netInputNeverGoesNegative() {
        // 有的网关会给出 cache_hit > prompt_tokens 的怪数据,扣完不能变负数
        val u = JSONObject("""
            {"prompt_tokens":100,"completion_tokens":50,"prompt_cache_hit_tokens":999}
        """.trimIndent())
        assertEquals(0L, UsageRecorder.parse(u)!!.input)
    }
}
