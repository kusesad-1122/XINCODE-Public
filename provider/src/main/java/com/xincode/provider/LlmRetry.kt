package com.xincode.provider

import kotlinx.coroutines.delay as coroutineDelay
import okhttp3.Headers
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException

/**
 * 契约 §4 重试退避 —— 纯逻辑、可单测,不依赖 Android / OkHttp 运行时以外的东西。
 *
 * 常量与 [AGENT-CORE-CONTRACT.md §4] / §附录「常量总表」逐字一致(由 `core` 端的
 * `AgentCoreContractTest` 与桌面端 `toolLimits.ts` 机械校验不漂移)。
 *
 * **模块依赖方向说明**:`provider` 模块当前 `build.gradle.kts` 仅依赖 `:data`、`:security`、
 * okhttp、kotlinx-coroutines,**不依赖 `:core`**(且 `core` 也不反向依赖 `provider`)。
 * 因此本文件独立声明契约常量,未复用 `com.xincode.core.AgentCoreContract` 的常量。
 * 若未来 `provider` 需要反向依赖 `core`,应改为引用 core 的常量并删除这里的重复声明,
 * 以免两端常量悄悄漂移。
 */
object LlmRetry {

    // ───────────────────────── §4.1 退避公式常量 ─────────────────────────

    /** BASE_DELAY_MS:退避基数(毫秒)。 */
    const val RETRY_BASE_DELAY_MS = 500L

    /** MAX_DELAY_MS:单次退避上限(毫秒)。 */
    const val RETRY_MAX_DELAY_MS = 30_000L

    /** JITTER_RATIO:随机抖动比例 [0,1)。 */
    const val RETRY_JITTER_RATIO = 0.25

    /** MAX_ATTEMPTS:最大尝试次数(含首次)。 */
    const val RETRY_MAX_ATTEMPTS = 3

    // ───────────────────────── §4.3 max_tokens 溢出收缩 ─────────────────────────

    /** FLOOR_OUTPUT_TOKENS:收缩下限,无法再缩时停止前进。 */
    const val FLOOR_OUTPUT_TOKENS = 3000

    /**
     * 退避延迟(毫秒)。[attempt] 为 1-based 序号(第几次尝试,从 1 计)。
     *
     *     delay(n) = min(BASE × 2^(n-1), MAX) × (1 + random() × JITTER)
     *
     * [random] 返回 [0.0,1.0),默认 [Math.random];单测注入确定性值以免真睡。
     */
    fun delayMillisFor(attempt: Int, random: () -> Double = { Math.random() }): Long {
        require(attempt >= 1) { "attempt 必须 >= 1,实际 $attempt" }
        // 用位移算 2^(n-1) 时先护住溢出:shift 过大时乘积会超过 Long 上限,
        // 直接取退避上限(反正随后也会被 cap 到 MAX_DELAY_MS)。
        val shift = attempt - 1
        val exp = if (shift >= 55) RETRY_MAX_DELAY_MS else RETRY_BASE_DELAY_MS * (1L shl shift)
        val capped = exp.coerceAtMost(RETRY_MAX_DELAY_MS)
        val r = random().coerceIn(0.0, 1.0)
        return (capped * (1.0 + r * RETRY_JITTER_RATIO)).toLong()
    }

    /**
     * 可重试判定(契约 §4.2 / §4.4)。
     * 网络错误 / 408 / 409 / 429 / 5xx → 可重试;
     * 400 / 401 / 403 / 404 / 内容审核 → 不可重试(重试无意义或越权)。
     *
     * 内容审核拒绝在各家通常表现为 HTTP 400/403(或 200 + finish_reason=content_filter)。
     * 前者在此自然判为不可重试;后者是 2xx,根本不会进入重试分支,故均不会被重试。
     */
    fun isRetryable(error: ApiError): Boolean = when (error) {
        is ApiError.NetworkError -> true   // 连接/超时/重置/DNS 等网络层异常
        // 明文被网络策略拦截属于【本地策略】问题:重试多少次结果都一样,不能算网络抖动。
        is ApiError.CleartextBlockedError -> false
        // 证书不被信任是配置问题,重试不会让证书变得可信
        is ApiError.TlsError -> false
        is ApiError.TimeoutError -> true   // 连接或读取超时(OkHttp SocketTimeoutException)
        is ApiError.ServerError -> true    // 5xx
        is ApiError.AuthError -> false     // 401/403:鉴权失败,重试无意义
        is ApiError.RequestError -> error.code in setOf(408, 409, 429)
        is ApiError.ParseError -> false    // 响应体解析失败,同输入必再失败
        is ApiError.UnknownError -> false  // fail-closed:未知错误不自动重试
    }

    /**
     * 读取 429 的 `Retry-After` 头(契约 §4.3):优先用它,忽略退避公式。
     * 仅支持 delta-seconds 形式(LLM 429 几乎都是这个);HTTP-date 形式罕见且解析成本高,
     * 解析失败则回退到退避公式(返回 null)。
     */
    fun retryAfterMillis(headers: Headers?): Long? {
        val raw = headers?.get("Retry-After")?.trim() ?: return null
        return raw.toLongOrNull()?.let { it * 1000L }
    }

    /**
     * 识别「max_tokens 溢出」错误体(契约 §4.3)。
     * 不同供应商文案不一,这里用启发式:必须同时出现 max_tokens / max_output_tokens 字段名
     * 与任一溢出关键词,避免把普通的 "max tokens" 提及误判为溢出。
     */
    fun detectMaxTokensOverflow(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val b = body.lowercase()
        val hasField = b.contains("max_tokens") || b.contains("max_output_tokens")
        if (!hasField) return false
        return b.contains("exceed") || b.contains("too large") || b.contains("maximum") ||
            b.contains("must be") || b.contains("over the limit") || b.contains("greater than") ||
            b.contains("less than") || b.contains("not allowed")
    }

    /**
     * 收缩 max_tokens(契约 §4.3):目标 = max(FLOOR_OUTPUT_TOKENS, current / 2)。
     * 无法再缩(目标不比当前小,即已到下限)时返回 null,表示不再前进。
     */
    fun shrinkMaxTokens(current: Int): Int? {
        if (current <= 0) return null
        val target = maxOf(FLOOR_OUTPUT_TOKENS, current / 2)
        return if (target < current) target else null
    }

    /**
     * 给定一个请求,原地收缩其请求体里的 `max_tokens` / `max_output_tokens` 后返回新请求;
     * 无该字段、无法解析、或已缩到不能再缩时返回 null(调用方应停止收缩)。
     * 用于 [executeWithRetry] 的溢出重试——不计入 [RETRY_MAX_ATTEMPTS] 的额外一次。
     */
    fun shrinkRequestMaxTokens(request: Request): Request? {
        val body = request.body ?: return null
        val buffer = Buffer()
        runCatching { body.writeTo(buffer) }.onFailure { return null }
        val text = buffer.readUtf8()
        val json = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return null
        val key = when {
            json.has("max_tokens") -> "max_tokens"
            json.has("max_output_tokens") -> "max_output_tokens"
            else -> return null
        }
        val current = json.optInt(key, -1)
        val shrunk = shrinkMaxTokens(current) ?: return null
        json.put(key, shrunk)
        val contentType = body.contentType()
        val newBody = json.toString().toRequestBody(contentType)
        val newHeaders = request.headers.newBuilder().removeAll("Content-Length").build()
        return request.newBuilder().headers(newHeaders).method(request.method, newBody).build()
    }

    /**
     * 通用可注入重试执行器(契约 §4 的 withRetry 风格)。
     *
     * [block] 正常返回即成功;抛异常时,若 [isRetryable] 返回 true 且未到 [attempts] 上限,
     * 按退避公式 sleep 后重试;否则原样上抛。
     *
     * [delay] 默认真睡([delay]);单测注入 no-op 以免真睡。[random] 同理可注入以固定抖动。
     */
    suspend fun <R> withRetry(
        attempts: Int = RETRY_MAX_ATTEMPTS,
        random: () -> Double = { Math.random() },
        delay: suspend (Long) -> Unit = { coroutineDelay(it) },
        isRetryable: (Throwable) -> Boolean,
        block: suspend (attempt: Int) -> R
    ): R {
        var last: Throwable? = null
        repeat(attempts) { idx ->
            val attempt = idx + 1
            try {
                return block(attempt)
            } catch (e: Throwable) {
                last = e
                if (attempt < attempts && isRetryable(e)) {
                    delay(delayMillisFor(attempt, random))
                } else {
                    throw e
                }
            }
        }
        throw last ?: IllegalStateException("withRetry: 没有任何尝试被执行")
    }
}
