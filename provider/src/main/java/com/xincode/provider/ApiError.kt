package com.xincode.provider

import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Typed API errors for user-facing display.
 * Each variant carries a Chinese message suitable for direct UI rendering.
 */
sealed class ApiError(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** 401 / 403 — key invalid or access denied */
    class AuthError(cause: Throwable? = null) : ApiError("鉴权失败", cause)

    /** Socket timeout — connection or read timed out */
    class TimeoutError(cause: Throwable? = null) : ApiError("连接超时", cause)

    /** DNS / socket / no route — device has no connectivity */
    class NetworkError(cause: Throwable? = null) : ApiError("网络错误", cause)

    /** JSON parse failure on response body or SSE line */
    class ParseError(cause: Throwable? = null) : ApiError("响应解析异常", cause)

    /** 5xx — server-side failure */
    class ServerError(code: Int, cause: Throwable? = null) : ApiError("服务器错误 ($code)", cause)

    /** Catch-all for unclassified errors */
    class UnknownError(message: String, cause: Throwable? = null) : ApiError(message, cause)

    companion object {
        /**
         * Classifies a [Throwable] into the most specific [ApiError] variant.
         * @param e          the caught exception
         * @param httpCode   optional HTTP status code (when available from response)
         */
        fun from(e: Throwable, httpCode: Int? = null): ApiError {
            // HTTP status code classification (takes priority when available)
            if (httpCode != null) {
                return when (httpCode) {
                    401, 403 -> AuthError(e)
                    in 500..599 -> ServerError(httpCode, e)
                    else -> UnknownError("HTTP $httpCode", e)
                }
            }

            // Exception type classification
            return when (e) {
                is SocketTimeoutException -> TimeoutError(e)
                is UnknownHostException -> NetworkError(e)
                is ConnectException -> NetworkError(e)
                is JSONException -> ParseError(e)
                is IOException -> {
                    val msg = e.message ?: ""
                    when {
                        msg.contains("401") || msg.contains("403") -> AuthError(e)
                        msg.contains("timeout", ignoreCase = true) -> TimeoutError(e)
                        msg.contains("Unable to resolve host", ignoreCase = true) -> NetworkError(e)
                        msg.contains("connect", ignoreCase = true) -> NetworkError(e)
                        else -> UnknownError(msg, e)
                    }
                }
                is ApiError -> e // already classified
                else -> UnknownError(e.message ?: "未知错误", e)
            }
        }
    }
}