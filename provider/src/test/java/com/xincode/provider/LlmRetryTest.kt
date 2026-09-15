package com.xincode.provider

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRetryTest {

    private val JSON = "application/json".toMediaType()

    private fun bodyText(req: Request): String {
        val buf = Buffer()
        req.body!!.writeTo(buf)
        return buf.readUtf8()
    }

    // ───────────────────────── §4.1 退避公式 ─────────────────────────

    @Test
    fun delayFormulaFollowsContract() {
        // delay(n) = min(500 * 2^(n-1), 30000) * (1 + 0 * 0.25)
        assertEquals(500L, LlmRetry.delayMillisFor(1, random = { 0.0 }))
        assertEquals(1000L, LlmRetry.delayMillisFor(2, random = { 0.0 }))
        assertEquals(2000L, LlmRetry.delayMillisFor(3, random = { 0.0 }))
    }

    @Test
    fun delayAppliesJitterAndCap() {
        // random = 1.0 → factor 1 + 1.0*0.25 = 1.25
        assertEquals(625L, LlmRetry.delayMillisFor(1, random = { 1.0 }))
        assertEquals(1250L, LlmRetry.delayMillisFor(2, random = { 1.0 }))
        // attempt 7: 500*2^6 = 32000 → 上限 30000, * 1.25 = 37500
        assertEquals(37500L, LlmRetry.delayMillisFor(7, random = { 1.0 }))
        // 零抖动时硬上限生效
        assertEquals(30000L, LlmRetry.delayMillisFor(100, random = { 0.0 }))
    }

    // ───────────────────────── §4.2 可重试判定 ─────────────────────────

    @Test
    fun retryableClassification() {
        assertTrue(LlmRetry.isRetryable(ApiError.NetworkError()))
        assertTrue(LlmRetry.isRetryable(ApiError.TimeoutError()))
        assertTrue(LlmRetry.isRetryable(ApiError.ServerError(503)))
        assertTrue(LlmRetry.isRetryable(ApiError.RequestError(408, "x")))
        assertTrue(LlmRetry.isRetryable(ApiError.RequestError(409, "x")))
        assertTrue(LlmRetry.isRetryable(ApiError.RequestError(429, "x")))

        assertFalse(LlmRetry.isRetryable(ApiError.AuthError()))
        assertFalse(LlmRetry.isRetryable(ApiError.RequestError(400, "x")))
        assertFalse(LlmRetry.isRetryable(ApiError.RequestError(404, "x")))
        assertFalse(LlmRetry.isRetryable(ApiError.ParseError()))
        assertFalse(LlmRetry.isRetryable(ApiError.UnknownError("boom")))
    }

    // ───────────────────────── §4.3 Retry-After ─────────────────────────

    @Test
    fun retryAfterHeaderParsed() {
        assertEquals(30_000L, LlmRetry.retryAfterMillis(Headers.headersOf("Retry-After", "30")))
        assertNull(LlmRetry.retryAfterMillis(Headers.headersOf()))
        assertNull(LlmRetry.retryAfterMillis(Headers.headersOf("Retry-After", "not-a-number")))
    }

    // ───────────────────────── §4.3 max_tokens 溢出 ─────────────────────────

    @Test
    fun detectOverflowHeuristics() {
        assertTrue(LlmRetry.detectMaxTokensOverflow("""{"error":{"message":"max_tokens is too large"}}"""))
        assertTrue(LlmRetry.detectMaxTokensOverflow("requested max_output_tokens exceeds the model limit"))
        assertFalse(LlmRetry.detectMaxTokensOverflow("""{"error":{"message":"model not supported"}}"""))
        assertFalse(LlmRetry.detectMaxTokensOverflow("max_tokens parameter is required but no overflow"))
        assertFalse(LlmRetry.detectMaxTokensOverflow(null))
    }

    @Test
    fun shrinkTargetsFloor() {
        assertEquals(4000, LlmRetry.shrinkMaxTokens(8000))
        assertEquals(3000, LlmRetry.shrinkMaxTokens(4000))
        assertEquals(3000, LlmRetry.shrinkMaxTokens(6000))
        assertNull(LlmRetry.shrinkMaxTokens(3000))   // 已到下限,无法再缩
        assertNull(LlmRetry.shrinkMaxTokens(100))
    }

    @Test
    fun shrinkRequestRewritesMaxTokens() {
        val req = Request.Builder()
            .url("https://api.example/v1/chat/completions")
            .post(JSONObject().put("model", "x").put("max_tokens", 8000).toString().toRequestBody(JSON))
            .build()
        val shrunk = LlmRetry.shrinkRequestMaxTokens(req)!!
        assertEquals(4000, JSONObject(bodyText(shrunk)).getInt("max_tokens"))

        // 无 max_tokens 字段 → null(无需收缩)
        val noField = Request.Builder()
            .url("https://api.example/v1/chat/completions")
            .post(JSONObject().put("foo", 1).toString().toRequestBody(JSON))
            .build()
        assertNull(LlmRetry.shrinkRequestMaxTokens(noField))

        // 已在下限 → null(不再前进)
        val atFloor = Request.Builder()
            .url("https://api.example/v1/chat/completions")
            .post(JSONObject().put("max_tokens", 3000).toString().toRequestBody(JSON))
            .build()
        assertNull(LlmRetry.shrinkRequestMaxTokens(atFloor))
    }

    // ───────────────────────── withRetry 执行器 ─────────────────────────

    private class RetryableErr : Exception()
    private class FatalErr : Exception()

    @Test
    fun withRetrySucceedsAfterRetries() = runBlocking {
        val delays = mutableListOf<Long>()
        var lastAttempt = 0
        val result = LlmRetry.withRetry(
            attempts = 3,
            random = { 0.0 },
            delay = { delays.add(it) },
            isRetryable = { it is RetryableErr }
        ) { attempt ->
            lastAttempt = attempt
            if (attempt < 2) throw RetryableErr() else "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, lastAttempt)              // 第 2 次成功
        assertEquals(listOf(500L), delays)        // attempt1 退避 500ms(零抖动)
    }

    @Test
    fun withRetryExhaustsThenThrows() = runBlocking {
        val delays = mutableListOf<Long>()
        var count = 0
        try {
            LlmRetry.withRetry(
                attempts = 3,
                random = { 0.0 },
                delay = { delays.add(it) },
                isRetryable = { it is RetryableErr }
            ) { count++; throw RetryableErr() }
        } catch (e: RetryableErr) { /* 预期 */ }
        assertEquals(3, count)                     // 共 3 次尝试(= MAX_ATTEMPTS)
        assertEquals(listOf(500L, 1000L), delays)  // 2 次退避
    }

    @Test
    fun withRetryDoesNotRetryFatal() = runBlocking {
        val delays = mutableListOf<Long>()
        var count = 0
        try {
            LlmRetry.withRetry(
                attempts = 3,
                random = { 0.0 },
                delay = { delays.add(it) },
                isRetryable = { false }
            ) { count++; throw FatalErr() }
        } catch (e: FatalErr) { /* 预期 */ }
        assertEquals(1, count)
        assertTrue(delays.isEmpty())
    }
}
