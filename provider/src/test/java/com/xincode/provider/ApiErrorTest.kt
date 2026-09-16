package com.xincode.provider

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * ApiError 分类单元测试 —— 这条链路直接决定"要不要重试 / 怎么提示用户"。
 * 覆盖：正常分类、边界（无 detail 的 HTTP 错误）、异常输入（message 为空）。
 *
 * 跑法：`./gradlew :provider:testDebugUnitTest --tests '*ApiErrorTest'`
 */
class ApiErrorTest {

    @Test
    fun httpCodes_mapToDedicatedVariants() {
        assertTrue(ApiError.from(IOException("HTTP 401"), 401) is ApiError.AuthError)
        assertTrue(ApiError.from(IOException("HTTP 403"), 403) is ApiError.AuthError)
        assertTrue(ApiError.from(IOException("HTTP 500"), 500) is ApiError.ServerError)
        assertTrue(ApiError.from(IOException("HTTP 503"), 503) is ApiError.ServerError)
        assertTrue(ApiError.from(IOException("HTTP 404"), 404) is ApiError.RequestError)
        assertTrue(ApiError.from(IOException("HTTP 422"), 422) is ApiError.RequestError)
    }

    @Test
    fun ioExceptionTypes_areClassifiedBeforeGenericIo() {
        assertTrue(ApiError.from(SocketTimeoutException()) is ApiError.TimeoutError)
        assertTrue(ApiError.from(UnknownHostException()) is ApiError.NetworkError)
        assertTrue(ApiError.from(ConnectException()) is ApiError.NetworkError)
        assertTrue(ApiError.from(JSONException("bad json")) is ApiError.ParseError)
    }

    @Test
    fun requestError_keepsServerDetail() {
        val e = ApiError.from(IOException("HTTP 400: model xxx is not supported"), 400)
        assertTrue(e is ApiError.RequestError)
        val r = e as ApiError.RequestError
        assertEquals(400, r.code)
        assertTrue("必须原样带上服务器原文，实际: ${r.detail}", r.detail.contains("model xxx is not supported"))
        assertTrue("展示文案应含原文，实际: ${r.message}", e.message!!.contains("model xxx"))
    }

    @Test
    fun boundary_httpCodeWithoutDetail_isStillUsable() {
        val e = ApiError.from(IOException("HTTP 400"), 400)
        assertTrue(e is ApiError.RequestError)
        assertEquals("", (e as ApiError.RequestError).detail)
        assertTrue("文案不应为空", e.message!!.isNotBlank())
    }

    @Test
    fun alreadyClassifiedError_passesThrough() {
        val original = ApiError.AuthError()
        assertSame(original, ApiError.from(original))
    }

    @Test
    fun exception_nullMessage_stillProducesReadableText() {
        val e = ApiError.from(RuntimeException())
        assertTrue("无 message 的异常也必须有可读文案，实际: '${e.message}'", !e.message.isNullOrBlank())
    }

    @Test
    fun exception_ioExceptionWithoutMessage_mustNotRenderBlank() {
        val e = ApiError.from(IOException())
        assertFalse(
            "IOException 无 message 时不能产出空文案（UI 会显示一片空白），实际: '${e.message}'",
            e.message.isNullOrBlank()
        )
    }

    @Test
    fun exception_unknownHttpCode_isNotSwallowed() {
        val e = ApiError.from(IOException("HTTP 302: redirect"), 302)
        assertTrue("非 4xx/5xx 状态码也必须有类型，实际: ${e::class.java.simpleName}", e is ApiError.UnknownError)
        assertTrue(e.message!!.contains("302"))
    }
}
