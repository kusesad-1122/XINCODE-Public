package com.xincode.app

import android.util.Base64
import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hermes-⑥ `transcribe_audio` —— 语音转写工具(信封化)。
 *
 * 把本地音频文件 POST 到 OpenAI 兼容的 `/v1/audio/transcriptions`(OpenAI / Groq Whisper 等),
 * 返回 {success, transcript, provider, error} 语义。转写端点默认复用活跃 provider 的 baseUrl+key,
 * 也可在设置里单独配 `stt_base_url` / `stt_api_key` / `stt_model`。
 *
 * 服务门控(Hermes-③):未配置任何可用端点时不把本工具发给模型。
 */
class TranscribeAudioTool(
    private val database: AppDatabase,
    private val keystore: KeystoreProvider
) : Tool {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    override val name = "transcribe_audio"
    override val description =
        "把一个本地音频文件转写成文字(mp3/m4a/wav/ogg/webm 等)。传 path=音频文件路径。" +
        "返回转写文本。用户发来语音、或你需要读取一段录音内容时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply { put("type", "string"); put("description", "本地音频文件绝对路径") })
            put("language", JSONObject().apply { put("type", "string"); put("description", "可选:语言提示,如 zh/en") })
        })
        put("required", JSONArray(listOf("path")))
    }

    // Hermes-③ 门控:无任何可用端点(aux_transcribe_* / stt_* / 活跃 provider 皆无)时不暴露。
    override fun isAvailable(): Boolean = kotlinx.coroutines.runBlocking {
        try {
            if (!database.settingDao().get("aux_transcribe_api_key").isNullOrBlank()) return@runBlocking true
            if (!database.settingDao().get("stt_api_key").isNullOrBlank()) return@runBlocking true
            database.providerConfigDao().getActive() != null
        } catch (_: Exception) { false }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"]?.trim().orEmpty()
        val file = File(path)
        if (path.isEmpty() || !file.exists() || !file.isFile)
            return@withContext envelope(false, "", "", "文件不存在: $path")

        // 解析端点:优先独立 stt_* 设置,否则回退活跃 provider。
        val (baseUrl, apiKey, model) = resolveEndpoint()
            ?: return@withContext envelope(false, "", "", "未配置转写端点")

        return@withContext try {
            val mediaType = "application/octet-stream".toMediaType()
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                .addFormDataPart("model", model)
                .apply { params["language"]?.takeIf { it.isNotBlank() }?.let { addFormDataPart("language", it) } }
                .build()
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@use envelope(false, "", model, "HTTP ${resp.code}: ${respBody.take(200)}")
                val text = try { JSONObject(respBody).optString("text", respBody) } catch (_: Exception) { respBody }
                envelope(true, text.trim(), model, "")
            }
        } catch (e: Exception) {
            envelope(false, "", model, e.message ?: "转写失败")
        }
    }

    private suspend fun resolveEndpoint(): Triple<String, String, String>? {
        // 优先「模型委托」页配的转写副模型(aux_transcribe_*)。
        val auxKeyEnc = database.settingDao().get("aux_transcribe_api_key")
        if (!auxKeyEnc.isNullOrBlank()) {
            val base = database.settingDao().get("aux_transcribe_base_url").orEmpty().ifBlank { "https://api.openai.com" }
            val model = database.settingDao().get("aux_transcribe_model").orEmpty().ifBlank { "whisper-1" }
            val key = try { keystore.decrypt(Base64.decode(auxKeyEnc, Base64.NO_WRAP)) } catch (_: Exception) { auxKeyEnc }
            return Triple(base, key, model)
        }
        val sttKeyEnc = database.settingDao().get("stt_api_key")
        if (!sttKeyEnc.isNullOrBlank()) {
            val base = database.settingDao().get("stt_base_url").orEmpty().ifBlank { "https://api.openai.com" }
            val model = database.settingDao().get("stt_model").orEmpty().ifBlank { "whisper-1" }
            val key = try { keystore.decrypt(Base64.decode(sttKeyEnc, Base64.NO_WRAP)) } catch (_: Exception) { sttKeyEnc }
            return Triple(base, key, model)
        }
        val cfg = database.providerConfigDao().getActive() ?: return null
        val key = try { keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP)) } catch (_: Exception) { "" }
        return Triple(cfg.baseUrl, key, "whisper-1")
    }

    private fun envelope(success: Boolean, transcript: String, provider: String, error: String): ToolResult {
        val env = JSONObject().apply {
            put("success", success); put("transcript", transcript)
            put("provider", provider); put("error", error)
        }
        return if (success) ToolResult.Success(env.toString()) else ToolResult.Error(error.ifBlank { "转写失败" })
    }
}
