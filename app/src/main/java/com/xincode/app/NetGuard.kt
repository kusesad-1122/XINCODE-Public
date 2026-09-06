package com.xincode.app

import java.net.InetAddress
import java.net.URI

/**
 * 出站请求守卫:在线插件/图标只允许 http(s) 指向公网主机。
 * 拒绝 localhost、环回、私有、链路本地与保留地址,防止利用 Agent 刺探内网拓扑(SSRF)。
 */
object NetGuard {

    /** 校验并返回 host;不合法时抛 [IllegalArgumentException]。 */
    fun validate(urlStr: String): String {
        val uri = try {
            URI(urlStr.trim())
        } catch (e: Exception) {
            throw IllegalArgumentException("URL 无法解析: $urlStr")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("仅允许 http/https 协议: $urlStr")
        }
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("URL 缺少主机名: $urlStr")

        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host == "localhost.localdomain"
        ) {
            throw IllegalArgumentException("禁止指向本地/内网主机: $host")
        }

        // 文本形式的内网段先拦一道(不依赖 DNS)
        if (host.startsWith("127.") || host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("169.254.") || host.startsWith("0.0.0.0") || host == "255.255.255.255"
        ) {
            throw IllegalArgumentException("禁止指向私有/保留地址: $host")
        }
        if (host.startsWith("172.")) {
            val second = host.substringAfter("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) {
                throw IllegalArgumentException("禁止指向私有地址: $host")
            }
        }

        // 再按解析结果拦一道(域名指向内网 IP 也算)
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            throw IllegalArgumentException("主机无法解析: $host")
        }
        if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress || addr.isMulticastAddress
        ) {
            throw IllegalArgumentException("禁止指向内网/保留地址: $host")
        }
        return host
    }
}
