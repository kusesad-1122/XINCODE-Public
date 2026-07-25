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
 * GitHub OAuth「设备流」(Device Authorization Grant)——让用户像 Operit 那样「直接登录账户」,
 * 免手动去建 Personal Access Token:
 *   1) [requestDeviceCode] 拿到 user_code + 授权网址;
 *   2) 用户在手机浏览器打开网址、输入 user_code、点授权;
 *   3) [pollForToken] 轮询直到拿到 access_token。
 *
 * 需要一个已注册的 GitHub OAuth App 的 client_id(设备流的 client_id 是【公开】的,不含密钥,
 * 放进 App/仓库没有泄露风险)。注册:GitHub → Settings → Developer settings → OAuth Apps →
 * New OAuth App,并在该 App 里勾选 "Enable Device Flow"。
 */
object GithubAuth {

    /**
     * XINCODE 内置的 GitHub OAuth App Client ID —— 由项目维护者【注册一次】,所有用户共用。
     *
     * 普通用户【不需要】自己去注册 OAuth App:点「登录 GitHub」即可走设备流授权。
     * 这与 GitHub CLI(gh)、Claude Code 等的做法一致。
     *
     * 为什么可以直接写进开源代码:设备授权流(Device Flow)【不使用 client_secret】,
     * client_id 本身是公开标识符,不构成凭据泄露——gh CLI 的 client_id 同样是公开的。
     * 真正的凭据是用户授权后拿到的 access_token,那只存在用户自己的设备上。
     *
     * 留空时,UI 会退回到「让用户自填 Client ID」的模式(自建 OAuth App 的高级用户可用)。
     */
    const val DEFAULT_CLIENT_ID = ""

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Int
    )

    /** 第 1 步:申请设备码 + 用户码。scope 默认 repo(clone/push 私有仓)+ read:user(取用户名)。 */
    suspend fun requestDeviceCode(clientId: String, scope: String = "repo read:user"): Result<DeviceCode> =
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder().add("client_id", clientId).add("scope", scope).build()
                val req = Request.Builder().url("https://github.com/login/device/code")
                    .header("Accept", "application/json").post(body).build()
                http.newCall(req).execute().use { resp ->
                    val txt = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return@withContext Result.failure(Exception("HTTP ${resp.code}: ${txt.take(160)}"))
                    val j = JSONObject(txt)
                    if (j.has("error")) return@withContext Result.failure(Exception(j.optString("error_description", j.optString("error"))))
                    Result.success(
                        DeviceCode(
                            deviceCode = j.getString("device_code"),
                            userCode = j.getString("user_code"),
                            verificationUri = j.optString("verification_uri", "https://github.com/login/device"),
                            interval = j.optInt("interval", 5),
                            expiresIn = j.optInt("expires_in", 900)
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 第 3 步:轮询直到授权完成,返回 access_token。[onTick] 用于回报进度。 */
    suspend fun pollForToken(
        clientId: String,
        deviceCode: String,
        interval: Int,
        expiresIn: Int,
        onTick: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        var wait = interval.coerceAtLeast(1)
        var elapsed = 0
        while (elapsed < expiresIn) {
            delay(wait * 1000L); elapsed += wait
            try {
                val body = FormBody.Builder()
                    .add("client_id", clientId)
                    .add("device_code", deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build()
                val req = Request.Builder().url("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json").post(body).build()
                val j = http.newCall(req).execute().use { resp -> JSONObject(resp.body?.string().orEmpty()) }
                val token = j.optString("access_token", "")
                if (token.isNotBlank()) return@withContext Result.success(token)
                when (j.optString("error")) {
                    "authorization_pending" -> onTick("等待网页授权…")
                    "slow_down" -> { wait += 5; onTick("放慢轮询…") }
                    "expired_token" -> return@withContext Result.failure(Exception("授权码已过期,请重试"))
                    "access_denied" -> return@withContext Result.failure(Exception("已取消授权"))
                    else -> onTick("等待中…")
                }
            } catch (_: Exception) {
                onTick("网络波动,重试…")
            }
        }
        Result.failure(Exception("授权超时,请重试"))
    }

    /** 用 token 取登录名(自动回填 git user.name)。 */
    suspend fun fetchLogin(token: String): String? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("https://api.github.com/user")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSONObject(resp.body?.string().orEmpty()).optString("login").ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }
}
