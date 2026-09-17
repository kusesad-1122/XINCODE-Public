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

    /**
     * 明文 HTTP 被 Android 网络策略拦截（network-security-config 未放行该地址）。
     * 系统原文 "CLEARTEXT communication to ... not permitted by network security policy"
     * 对用户没有任何可操作性，必须换成能照着做的中文说明。
     */
    class CleartextBlockedError(cause: Throwable? = null) : ApiError(
        "明文 HTTP 被系统拦截：该地址不在应用的放行列表内。请改用 https:// 或安装已放行局域网地址的新版本",
        cause
    )

    /**
     * HTTPS 证书校验失败（自签证书 / 证书链不完整）。
     * 本地服务(LM Studio、自建反代)常挂自签证书，用户看到系统英文原文同样无从下手。
     */
    class TlsError(cause: Throwable? = null) : ApiError(
        "HTTPS 证书校验失败：本地服务的自签证书不被系统信任，请改用 http:// 或换用受信任证书",
        cause
    )

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
                        // Android 网络策略拦截明文 HTTP：必须排在其它规则之前，
                        // 否则会被 "connect"/"timeout" 之类的子串误判成普通网络错误。
                        msg.contains("CLEARTEXT", ignoreCase = true) -> CleartextBlockedError(e)
                        // 证书信任链问题:自签证书的典型报错原文
                        msg.contains("Trust anchor", ignoreCase = true) ||
                            msg.contains("SSLHandshake", ignoreCase = true) ||
                            msg.contains("CertPathValidator", ignoreCase = true) -> TlsError(e)
                        msg.contains("401") || msg.contains("403") -> AuthError(e)
                        msg.contains("timeout", ignoreCase = true) -> TimeoutError(e)
                        msg.contains("Unable to resolve host", ignoreCase = true) -> NetworkError(e)
                        msg.contains("connect", ignoreCase = true) -> NetworkError(e)
                        // 兜底文案不能是空串：IOException() 可以完全没有 message，
                        // 空文案在 UI 上就是一片空白，用户既看不懂也报不了。
                        else -> UnknownError(msg.ifBlank { "请求失败（未提供原因）" }, e)
                    }
                }
                is ApiError -> e // already classified
                else -> UnknownError(e.message?.takeIf { it.isNotBlank() } ?: "未知错误", e)
            }
        }
    }
}