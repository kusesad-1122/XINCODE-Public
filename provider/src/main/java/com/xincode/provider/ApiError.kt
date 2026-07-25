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

    /**
     * 4xx(400/404/422 等)——请求本身被供应商拒绝。
     * [detail] 是【服务器返回的原文原因】(如 "model xxx is not supported"、"unsupported parameter: tools")。
     * 必须原样透出:此前这类错误被压成一句 "HTTP 400",用户和模型都无从判断问题出在哪。
     */
    class RequestError(val code: Int, val detail: String = "", cause: Throwable? = null) :
        ApiError(if (detail.isBlank()) "请求被拒绝 ($code)" else "请求被拒绝 ($code):$detail", cause)

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
                // 调用方通常把服务器返回的原因塞在 message 里(形如 "HTTP 400: model xxx not supported")。
                // 把冒号后的原文抽出来带上——丢掉它等于让用户对着一句 "HTTP 400" 干瞪眼。
                val detail = (e.message ?: "")
                    .substringAfter("HTTP $httpCode:", "")
                    .trim()
                    .ifBlank { (e.message ?: "").takeIf { it.isNotBlank() && !it.equals("HTTP $httpCode", true) }.orEmpty() }
                    .take(300)
                return when (httpCode) {
                    401, 403 -> AuthError(e)
                    in 500..599 -> ServerError(httpCode, e)
                    in 400..499 -> RequestError(httpCode, detail, e)
                    else -> UnknownError(if (detail.isBlank()) "HTTP $httpCode" else "HTTP $httpCode:$detail", e)
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