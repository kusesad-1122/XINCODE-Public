package com.xincode.app

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
    private const val RETAIN_DAYS = 60

    fun record(
        database: AppDatabase,
        usage: JSONObject,
        sessionId: Long,
        model: String,
        provider: String,
        source: String
    ) {
        scope.launch {
            runCatching {
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

                // 全零就别记了:有些供应商在流式最后一帧给个空 usage,记下来只会污染统计
                if (input == 0L && output == 0L) return@runCatching

                database.usageRecordDao().insert(
                    UsageRecordEntity(
                        sessionId = sessionId,
                        model = model, provider = provider, source = source,
                        inputTokens = input, outputTokens = output,
                        cacheReadTokens = cacheRead, cacheWriteTokens = cacheWrite,
                        reasoningTokens = reasoning
                    )
                )
            }
        }
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
