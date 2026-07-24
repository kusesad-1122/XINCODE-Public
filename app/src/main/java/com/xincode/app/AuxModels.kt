package com.xincode.app

import android.util.Base64
import com.xincode.data.AppDatabase
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 多任务【模型委托】框架(受 Hermes `auxiliary.<task>` 启发)。
 *
 * 当主模型缺某项能力(不多模态 / 推理弱 / 翻译差 / 不能转写)时,把这件事交给**单独配置**的副模型。
 * 每个 task 一套独立的 base_url / api_key(keystore 加密)/ model,存在 settings:`aux_<key>_base_url` 等。
 *
 * 通用文本委托走 [chat](OpenAI 兼容 /v1/chat/completions);视觉/语音有各自的媒体工具(describe_image / transcribe_audio)。
 */
object AuxModels {
    data class Task(val key: String, val label: String, val hint: String, val defaultModel: String)

    /** 可配置的委托任务清单(UI 与工具共用)。 */
    val TASKS: List<Task> = listOf(
        Task("vision", "视觉(看图)", "主模型看不了图时转交(OpenAI 兼容多模态)", "gpt-4o-mini"),
        Task("reason", "深度推理", "把难题转交更强的推理模型(如 deepseek-reasoner / o1)", "deepseek-reasoner"),
        Task("translate", "翻译", "转交擅长翻译的模型", ""),
        Task("transcribe", "语音转写", "Whisper 兼容端点", "whisper-1")
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Resolved(val baseUrl: String, val apiKey: String, val model: String)

    /** 读取某 task 的委托配置;未配置返回 null。 */
    suspend fun resolve(db: AppDatabase, keystore: KeystoreProvider, key: String): Resolved? {
        val baseUrl = db.settingDao().get("aux_${key}_base_url")?.trim().orEmpty()
        val keyEnc = db.settingDao().get("aux_${key}_api_key").orEmpty()
        if (baseUrl.isBlank() || keyEnc.isBlank()) return null
        val model = db.settingDao().get("aux_${key}_model")?.trim()
            ?.ifBlank { TASKS.firstOrNull { it.key == key }?.defaultModel ?: "" } ?: ""
        val apiKey = try { keystore.decrypt(Base64.decode(keyEnc, Base64.NO_WRAP)) } catch (_: Exception) { keyEnc }
        return Resolved(baseUrl, apiKey, model)
    }

    suspend fun isConfigured(db: AppDatabase, key: String): Boolean =
        !db.settingDao().get("aux_${key}_base_url").isNullOrBlank() &&
            !db.settingDao().get("aux_${key}_api_key").isNullOrBlank()

    /** 保存某 task 的委托配置(apiKey 空=不改)。 */
    suspend fun save(db: AppDatabase, keystore: KeystoreProvider, key: String, baseUrl: String, apiKey: String, model: String) {
        db.settingDao().put("aux_${key}_base_url", baseUrl.trim())
        db.settingDao().put("aux_${key}_model", model.trim())
        if (apiKey.isNotBlank()) {
            val enc = Base64.encodeToString(keystore.encrypt(apiKey), Base64.NO_WRAP)
            db.settingDao().put("aux_${key}_api_key", enc)
        }
    }

    /** 通用文本委托:把 [userText] 发给 task 的副模型,返回其文本回复。 */
    suspend fun chat(
        db: AppDatabase, keystore: KeystoreProvider, key: String,
        systemPrompt: String?, userText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val cfg = resolve(db, keystore, key)
            ?: return@withContext Result.failure(IllegalStateException("未配置委托模型: $key"))
        try {
            val messages = JSONArray()
            if (!systemPrompt.isNullOrBlank()) messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
            messages.put(JSONObject().put("role", "user").put("content", userText))
            val body = JSONObject().apply {
                put("model", cfg.model.ifBlank { "gpt-4o-mini" })
                put("messages", messages)
                put("stream", false)
            }
            val req = Request.Builder()
                .url("${cfg.baseUrl.trimEnd('/')}/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@use Result.failure(RuntimeException("HTTP ${resp.code}: ${respBody.take(200)}"))
                val text = JSONObject(respBody).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                if (text.isBlank()) Result.failure(RuntimeException("委托模型无有效返回"))
                else Result.success(text.trim())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
