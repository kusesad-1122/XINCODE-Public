package com.xincode.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Nous Research Portal 的 OAuth 设备授权流(与 GitHub 登录同一套机制)。
 *
 * 用户点「登录 Nous」→ 显示用户码并打开授权网页 → 轮询直到授权完成 → 拿到 access_token,
 * 之后即可直接调用 Nous 推理接口,无需手动申请 API Key。
 *
 * client_id 为公开标识符(设备流不使用 client_secret),可随开源代码分发。
 * 端点与参数对齐 Hermes 的实现:
 *   POST {portal}/api/oauth/device/code   → device_code / user_code / verification_uri…
 *   POST {portal}/api/oauth/token         → access_token(grant_type=device_code)
 */
object NousAuth {

    const val PORTAL_BASE_URL = "https://portal.nousresearch.com"
    const val INFERENCE_BASE_URL = "https://inference-api.nousresearch.com/v1"
    const val CLIENT_ID = "hermes-cli"
    const val SCOPE = "inference:invoke"

    private const val POLL_INTERVAL_CAP_SEC = 30

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        /** 已带上 user_code 的完整授权链接,打开即可(免手输用户码)。 */
        val verificationUriComplete: String,
        val interval: Int,
        val expiresIn: Int
    )

    /** 第 1 步:申请设备码与用户码。 */
    suspend fun requestDeviceCode(): Result<DeviceCode> = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scope", SCOPE)
                .build()
            val req = Request.Builder()
                .url("$PORTAL_BASE_URL/api/oauth/device/code")
                .header("Accept", "application/json")
                .post(body).build()

            http.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${resp.code}: ${txt.take(160)}"))
                }
                val j = JSONObject(txt)
                val device = j.optString("device_code")
                val user = j.optString("user_code")
                if (device.isBlank() || user.isBlank()) {
                    return@withContext Result.failure(Exception("设备码响应缺少必要字段"))
                }
                val uri = j.optString("verification_uri", "$PORTAL_BASE_URL/device")
                Result.success(
                    DeviceCode(
                        deviceCode = device,
                        userCode = user,
                        verificationUri = uri,
                        verificationUriComplete = j.optString("verification_uri_complete").ifBlank { uri },
                        interval = j.optInt("interval", 5),
                        expiresIn = j.optInt("expires_in", 900)
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 第 2 步:轮询直到用户在网页完成授权,返回 access_token。 */
    suspend fun pollForToken(
        deviceCode: String,
        interval: Int,
        expiresIn: Int,
        onTick: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        var wait = interval.coerceIn(1, POLL_INTERVAL_CAP_SEC)
        var elapsed = 0
        while (elapsed < expiresIn) {
            delay(wait * 1000L); elapsed += wait
            try {
                val body = FormBody.Builder()
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .add("client_id", CLIENT_ID)
                    .add("device_code", deviceCode)
                    .build()
                val req = Request.Builder()
                    .url("$PORTAL_BASE_URL/api/oauth/token")
                    .header("Accept", "application/json")
                    .post(body).build()

                val (code, txt) = http.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
                val j = runCatching { JSONObject(txt) }.getOrNull()

                if (code == 200) {
                    val token = j?.optString("access_token").orEmpty()
                    if (token.isNotBlank()) return@withContext Result.success(token)
                    return@withContext Result.failure(Exception("授权响应未包含 access_token"))
                }
                when (j?.optString("error")) {
                    "authorization_pending" -> onTick("等待网页授权…")
                    "slow_down" -> { wait = (wait + 1).coerceAtMost(POLL_INTERVAL_CAP_SEC); onTick("放慢轮询…") }
                    "expired_token" -> return@withContext Result.failure(Exception("授权码已过期,请重试"))
                    "access_denied" -> return@withContext Result.failure(Exception("已取消授权"))
                    else -> {
                        val desc = j?.optString("error_description").orEmpty()
                        if (desc.isNotBlank()) return@withContext Result.failure(Exception(desc))
                        onTick("等待中…")
                    }
                }
            } catch (_: Exception) {
                onTick("网络波动,重试…")
            }
        }
        Result.failure(Exception("授权超时,请重试"))
    }
}
