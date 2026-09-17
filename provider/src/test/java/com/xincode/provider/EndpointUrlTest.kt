package com.xincode.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * base_url 规范化单元测试。
 *
 * 背景：供应商配置是自由文本输入，用户填 "192.168.1.5:11434" 这类【无 scheme】地址时，
 * OkHttp 直接抛 "Expected URL scheme"，用户看到的现象只是「死活连不上」。
 *
 * 这里的规则必须稳定：补错 scheme 会把本来可用的配置改坏，比不补更糟。
 *
 * 跑法：`./gradlew :provider:testDebugUnitTest --tests '*EndpointUrlTest'`
 */
class EndpointUrlTest {

    @Test
    fun missingScheme_lanIpWithPort_getsHttp() {
        // 最常见形态：局域网自建服务。带端口的自建服务基本都是明文。
        assertEquals("http://192.168.1.5:11434", EndpointUrl.normalize("192.168.1.5:11434"))
        assertEquals("http://10.0.0.8:8080", EndpointUrl.normalize("10.0.0.8:8080"))
        assertEquals("http://172.16.5.5:8000", EndpointUrl.normalize("172.16.5.5:8000"))
    }

    @Test
    fun missingScheme_lanIpWithoutPort_getsHttp() {
        assertEquals("http://192.168.1.5", EndpointUrl.normalize("192.168.1.5"))
        assertEquals("http://10.1.2.3", EndpointUrl.normalize("10.1.2.3"))
        assertEquals("http://172.31.0.1", EndpointUrl.normalize("172.31.0.1"))
    }

    @Test
    fun missingScheme_loopbackAndEmulatorHost_getHttp() {
        assertEquals("http://localhost:11434", EndpointUrl.normalize("localhost:11434"))
        assertEquals("http://localhost", EndpointUrl.normalize("localhost"))
        assertEquals("http://127.0.0.1:8787", EndpointUrl.normalize("127.0.0.1:8787"))
        assertEquals("http://10.0.2.2:8080", EndpointUrl.normalize("10.0.2.2:8080"))
    }

    @Test
    fun missingScheme_publicDomain_getsHttps() {
        assertEquals("https://api.deepseek.com", EndpointUrl.normalize("api.deepseek.com"))
        assertEquals("https://api.openai.com", EndpointUrl.normalize("api.openai.com"))
    }

    @Test
    fun missingScheme_hostnameWithPort_isTreatedAsSelfHosted() {
        assertEquals("http://my-nas.local:5000", EndpointUrl.normalize("my-nas.local:5000"))
    }

    @Test
    fun existingScheme_isPreservedVerbatim() {
        assertEquals("https://api.openai.com/v1", EndpointUrl.normalize("https://api.openai.com/v1"))
        assertEquals("http://192.168.1.5:11434", EndpointUrl.normalize("http://192.168.1.5:11434"))
        // 非 http(s) 的自定义 scheme 也不能被篡改
        assertEquals("ftp://example.com", EndpointUrl.normalize("ftp://example.com"))
    }

    @Test
    fun trailingSlashesAndSpaces_areTrimmed() {
        assertEquals("https://api.openai.com", EndpointUrl.normalize("https://api.openai.com/"))
        assertEquals("http://192.168.1.5:11434", EndpointUrl.normalize("http://192.168.1.5:11434///"))
        assertEquals("https://api.openai.com/v1", EndpointUrl.normalize("  https://api.openai.com/v1/  "))
    }

    @Test
    fun path_isPreserved() {
        assertEquals("http://192.168.1.5:11434/v1", EndpointUrl.normalize("192.168.1.5:11434/v1"))
        assertEquals("https://api.moonshot.cn/v1", EndpointUrl.normalize("api.moonshot.cn/v1"))
    }

    @Test
    fun loopbackDetection_isCaseInsensitive() {
        assertEquals("http://LOCALHOST:11434", EndpointUrl.normalize("LOCALHOST:11434"))
    }

    @Test
    fun ipv6_loopback_isHandled() {
        assertEquals("http://[::1]:11434", EndpointUrl.normalize("[::1]:11434"))
        assertEquals("http://[::1]", EndpointUrl.normalize("[::1]"))
    }

    @Test
    fun emptyBlankOrBareSlash_returnsEmpty() {
        assertEquals("", EndpointUrl.normalize(""))
        assertEquals("", EndpointUrl.normalize("   "))
        assertEquals("", EndpointUrl.normalize("/"))
    }

    @Test
    fun boundary_172OutsidePrivateRange_getsHttps() {
        // 172.32 不在私网段(16-31)内，不能误判成明文
        assertEquals("https://172.32.0.1", EndpointUrl.normalize("172.32.0.1"))
    }

    @Test
    fun idempotent_applyingTwiceChangesNothing() {
        val once = EndpointUrl.normalize("192.168.1.5:11434/v1")
        assertEquals(once, EndpointUrl.normalize(once))
    }
}