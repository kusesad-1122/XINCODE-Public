package com.xincode.provider

/**
 * 用户填写的 base_url 规范化。
 *
 * 为什么需要这一步：供应商配置是一个自由文本输入框，用户真实会填出这些形态 ——
 *
 *     192.168.1.5:11434        「本地推理服务」最常见写法，但没有 scheme
 *     localhost:11434
 *     api.deepseek.com
 *     https://api.openai.com/v1/
 *
 * 缺少 scheme 时 OkHttp 直接抛 "Expected URL scheme 'http' or 'https' but no scheme
 * was found"，用户看到的现象只是「死活连不上」，而根因仅仅少写了 7 个字符。
 *
 * 补 scheme 的规则（按「最可能的意图」推断，不做猜测性改写）：
 *   1. 已带 scheme（含 "://"）                  -> 原样保留
 *   2. 回环/模拟器宿主（localhost、127.、[::1]、10.0.2.2） -> http
 *   3. 带端口（host:port）                      -> http（自建服务带端口绝大多数是明文）
 *   4. 私网 IPv4（10.、192.168.、172.16-31.、169.254.）  -> http
 *   5. 其余（公网域名）                          -> https
 *
 * 纯函数，便于单测覆盖；不要在别处再实现一份。
 */
object EndpointUrl {

    fun normalize(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        // 已带 scheme：不动（包含用户自填的 https:// 或其它 scheme）
        if (trimmed.contains("://")) return trimmed

        val hostPort = trimmed.substringBefore('/')
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringBefore(']') + "]"
        } else {
            hostPort.substringBefore(':')
        }
        val port = hostPort.substringAfterLast(':', "")
            .takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

        val scheme = when {
            isLoopback(host) -> "http"
            port != null -> "http"
            isPrivateIpv4(host) -> "http"
            else -> "https"
        }
        return "$scheme://$trimmed"
    }

    private fun isLoopback(host: String): Boolean {
        val h = host.lowercase()
        return h == "localhost" || h.startsWith("localhost.") ||
            h.startsWith("127.") || h == "[::1]" || h == "10.0.2.2"
    }

    private fun isPrivateIpv4(host: String): Boolean {
        if (host.startsWith("10.")) return true
        if (host.startsWith("192.168.")) return true
        if (host.startsWith("169.254.")) return true
        if (host.startsWith("172.")) {
            val second = host.substringAfter('.').substringBefore('.').toIntOrNull()
            return second != null && second in 16..31
        }
        return false
    }
}