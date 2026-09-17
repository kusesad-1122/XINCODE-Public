package com.xincode.app

import com.xincode.provider.ApiError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownServiceException

/**
 * 「连接失败原因」展示函数的行为约束。
 *
 * 背景：供应商配置页此前用 getOrDefault(emptyList()) 吞掉了真实错误，
 * 界面只显示「拉取失败或无可用模型」—— 用户分不清是网络不通、明文被拦、
 * 还是地址填错，也就无从自查。现在错误要透出，且**永远不能是空串**。
 *
 * 跑法：`./gradlew :app:testDebugUnitTest --tests '*ProviderConnectErrorTest'`
 */
class ProviderConnectErrorTest {

    @Test
    fun cleartextBlocked_showsActionableChineseMessage() {
        val err = ApiError.CleartextBlockedError()
        val text = describeConnectError(err)
        assertFalse("不能是空串", text.isBlank())
        assertTrue("必须说清是什么问题，实际: $text", text.contains("明文"))
    }

    @Test
    fun typedApiError_keepsItsOwnMessage() {
        val text = describeConnectError(ApiError.TimeoutError())
        assertTrue("超时错误要如实显示，实际: $text", text.contains("超时"))
    }

    @Test
    fun throwableWithoutMessage_fallsBackToClassName_notBlank() {
        val text = describeConnectError(IOException())
        assertFalse("无 message 时也不能空白（空白提示等于没提示）", text.isBlank())
        assertTrue("应退回到类名，实际: $text", text.contains("IOException"))
    }

    @Test
    fun blankMessage_alsoFallsBackToClassName() {
        val text = describeConnectError(IOException("   "))
        assertFalse("只有空白的 message 同样要兜底", text.isBlank())
        assertTrue("应退回到类名，实际: $text", text.contains("IOException"))
    }

    @Test
    fun rawCleartextError_afterClassification_isReadable() {
        // OkHttp 在明文被拦时抛的原始异常，经过分类后交给 UI 的文案必须可读
        val raw = UnknownServiceException(
            "CLEARTEXT communication to 192.168.1.5 not permitted by network security policy"
        )
        val text = describeConnectError(ApiError.from(raw))
        assertFalse(text.isBlank())
        assertTrue("不应把系统英文原文直接甩给用户，实际: $text", !text.startsWith("CLEARTEXT"))
    }
}