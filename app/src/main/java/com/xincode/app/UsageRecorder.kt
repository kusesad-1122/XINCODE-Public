package com.xincode.app

import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.UsageRecordEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 把每次模型调用的 usage 落进 [com.xincode.data.UsageRecordEntity]。
 *
 * 各家返回的字段名不统一,这里做一次归一:
 *   OpenAI      prompt_tokens / completion_tokens
 *   Anthropic   input_tokens / output_tokens(我们在 provider 层已映射成 OpenAI 形态)
 *   DeepSeek    额外给 prompt_cache_hit_tokens / prompt_cache_miss_tokens
 *   通用        prompt_tokens_details.cached_tokens
 *
 * 缓存命中要单独拆出来,否则成本估不准——缓存读通常只要正常输入价的十分之一。
 */
object UsageRecorder {

    /** 独立作用域:记账失败绝不能影响对话主流程,也不该阻塞调用线程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 保留天数。超过就清掉,免得这张表无限长。 */
    private const val TAG = "XincodeUsage"

    private const val RETAIN_DAYS = 60

    fun record(
        database: AppDatabase,
        usage: JSONObject,
        sessionId: Long,
        model: String,
        provider: String,
        source: String
    ) {
        val parsed = parse(usage)
        if (parsed == null) {
            // 全零 usage 很常见(流式最后一帧),不值得报警,但要能查
            Log.d(TAG, "usage skipped (all zero) source=$source")
            return
        }
        scope.launch {
            runCatching {
                database.usageRecordDao().insert(
                    UsageRecordEntity(
                        sessionId = sessionId,
                        model = model, provider = provider, source = source,
                        inputTokens = parsed.input, outputTokens = parsed.output,
                        cacheReadTokens = parsed.cacheRead, cacheWriteTokens = parsed.cacheWrite,
                        reasoningTokens = parsed.reasoning
                    )
                )
            }.onFailure {
                // 以前这里是光秃秃一个 runCatching,写库失败完全无声 ——
                // 表现出来就是「用量分析永远空着,但哪儿都不报错」,没法查。
                Log.w(TAG, "usage insert failed: ${it::class.java.simpleName}: ${it.message}")
            }
        }
    }

    /** 从一次调用的 usage 里解出要记的几个数。返回 null 表示这条不值得记。 */
    internal data class Parsed(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val reasoning: Long
    )

    /**
     * 解析 usage。抽成纯函数是为了能测 —— 各家字段名不一样,靠肉眼比对迟早出错,
     * 而错了的表现是「统计悄悄少算」,不会有任何报错。
     */
    internal fun parse(usage: JSONObject): Parsed? {
        val input = usage.optLong("prompt_tokens", 0)
            .takeIf { it > 0 } ?: usage.optLong("input_tokens", 0)
        val output = usage.optLong("completion_tokens", 0)
            .takeIf { it > 0 } ?: usage.optLong("output_tokens", 0)

        // 缓存命中:DeepSeek 的专有字段优先,其次通用的 details.cached_tokens
        val cacheRead = usage.optLong("prompt_cache_hit_tokens", 0)
            .takeIf { it > 0 }
            ?: usage.optJSONObject("prompt_tokens_details")?.optLong("cached_tokens", 0)
            ?: 0L
        val cacheWrite = usage.optLong("cache_creation_input_tokens", 0)
        val reasoning = usage.optJSONObject("completion_tokens_details")
            ?.optLong("reasoning_tokens", 0) ?: 0L

        // 全零就别记了:有些供应商在流式最后一帧给个空 usage,记下来只会污染统计。
        // 注意 Anthropic 那种「input 为 0 但缓存读了一大堆」的情况也要留下 —— 那是真花了钱的。
        if (input == 0L && output == 0L && cacheRead == 0L && cacheWrite == 0L) return null

        // 【关键】OpenAI / DeepSeek 的 prompt_tokens 是【含】缓存命中的
        // (DeepSeek 文档写明 prompt_tokens = cache_hit + cache_miss);
        // Anthropic 的 input_tokens 则【不含】,缓存是独立字段。
        // 不区分就会把缓存算两遍:总量虚高,命中率被压到实际值的一半左右,
        // 成本也跟着估高。provider 层用 input_includes_cache 显式标了语义。
        val includesCache = usage.optBoolean("input_includes_cache", true)
        val netInput = if (includesCache)
            (input - cacheRead - cacheWrite).coerceAtLeast(0)
        else input

        return Parsed(netInput, output, cacheRead, cacheWrite, reasoning)
    }

    /** 启动时清一次旧记录。 */
    fun prune(database: AppDatabase) {
        scope.launch {
            runCatching {
                val before = System.currentTimeMillis() - RETAIN_DAYS * 24L * 60 * 60 * 1000
                database.usageRecordDao().deleteBefore(before)
            }
        }
    }
}
