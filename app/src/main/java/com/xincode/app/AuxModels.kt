package com.xincode.app

import android.util.Base64
import android.util.Log
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
    private const val TAG = "AuxModels"

    data class Task(val key: String, val label: String, val hint: String, val defaultModel: String)

    /** 可配置的委托任务清单(UI 与工具共用)。 */
    val TASKS: List<Task> = listOf(
        Task("vision", "视觉(看图)", "主模型看不了图时转交(OpenAI 兼容多模态)", "gpt-4o-mini"),
        Task("reason", "深度推理", "把难题转交更强的推理模型(如 deepseek-reasoner / o1)", "deepseek-reasoner"),
        Task("translate", "翻译", "转交擅长翻译的模型", ""),
        Task("transcribe", "语音转写", "Whisper 兼容端点", "whisper-1"),
        Task("image", "图像生成", "文生图专用端点(OpenAI /images/generations 兼容)", "gpt-image-1")
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Resolved(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val apiPathType: String = "openai",
        val extraHeadersJson: String = ""
    )

    /** 读取某 task 的委托配置;未配置返回 null。 */
    suspend fun resolve(db: AppDatabase, keystore: KeystoreProvider, key: String): Resolved? {
        val p = Profiles.currentId(db)
        val baseUrl = db.settingDao().get(Profiles.key(p, "aux_${key}_base_url"))?.trim().orEmpty()
        val keyEnc = db.settingDao().get(Profiles.key(p, "aux_${key}_api_key")).orEmpty()
        // 这里没单独填 URL/Key 时,退而看【功能模型配置】有没有给这个 key 指定一套已存的供应商配置。
        // 两套机制的优先级:手填的委托端点 > 功能模型配置 > 不可用。
        // 手填优先,是因为用户特意填了外部端点,多半就是要绕开主供应商。
        if (baseUrl.isBlank() || keyEnc.isBlank()) return resolveFromFunctionConfig(db, keystore, key)
        val model = db.settingDao().get(Profiles.key(p, "aux_${key}_model"))?.trim()
            ?.ifBlank { TASKS.firstOrNull { it.key == key }?.defaultModel ?: "" } ?: ""
        val apiPathType = db.settingDao().get(Profiles.key(p, "aux_${key}_api_path_type"))
            ?.trim()?.ifBlank { "openai" } ?: "openai"
        val apiKey = try {
            keystore.decrypt(Base64.decode(keyEnc, Base64.NO_WRAP))
        } catch (e: Exception) {
            // 解密失败说明存的密文已损坏(如 keystore 轮转/备份恢复)。
            // 绝不能把【密文原文】当明文 key 发出去:那一定 401,还把密文泄露到对端日志。
            Log.w(TAG, "aux task=$key api_key decrypt failed, treat as unconfigured: ${e.message}")
            return null
        }
        return Resolved(baseUrl, apiKey, model, apiPathType)
    }

    /** 从功能模型配置(fn_<key>_config_id / fn_<key>_model)取一套已保存的供应商配置。 */
    private suspend fun resolveFromFunctionConfig(
        db: AppDatabase, keystore: KeystoreProvider, key: String
    ): Resolved? {
        val p = Profiles.currentId(db)
        val id = db.settingDao().get(Profiles.key(p, "fn_${key}_config_id"))?.toLongOrNull() ?: 0L
        if (id <= 0) return null
        val cfg = db.providerConfigDao().getById(id) ?: return null
        if (cfg.baseUrl.isBlank() || cfg.apiKeyEnc.isBlank()) return null
        val model = db.settingDao().get(Profiles.key(p, "fn_${key}_model"))?.trim()?.ifBlank { null } ?: cfg.model
        val apiKey = try {
            keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.w(TAG, "aux task=$key fn config id=$id decrypt failed, treat as unconfigured: ${e.message}")
            return null
        }
        return Resolved(cfg.baseUrl, apiKey, model, cfg.apiPathType, cfg.extraHeadersJson)
    }

    suspend fun isConfigured(db: AppDatabase, key: String): Boolean {
        val p = Profiles.currentId(db)
        return (!db.settingDao().get(Profiles.key(p, "aux_${key}_base_url")).isNullOrBlank() &&
            !db.settingDao().get(Profiles.key(p, "aux_${key}_api_key")).isNullOrBlank()) ||
            // 功能模型配置指了一套也算已配置,否则 describe_image 这类服务门控工具不会暴露
            ((db.settingDao().get(Profiles.key(p, "fn_${key}_config_id"))?.toLongOrNull() ?: 0L) > 0)
    }

    /** 保存某 task 的委托配置(apiKey 空=不改)。 */
    suspend fun save(
        db: AppDatabase,
        keystore: KeystoreProvider,
        key: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        apiPathType: String = "openai"
    ) {
        val p = Profiles.currentId(db)
        db.settingDao().put(Profiles.key(p, "aux_${key}_base_url"), baseUrl.trim())
        db.settingDao().put(Profiles.key(p, "aux_${key}_model"), model.trim())
        db.settingDao().put(Profiles.key(p, "aux_${key}_api_path_type"), apiPathType.trim().ifBlank { "openai" })
        if (apiKey.isNotBlank()) {
            val enc = Base64.encodeToString(keystore.encrypt(apiKey), Base64.NO_WRAP)
            db.settingDao().put(Profiles.key(p, "aux_${key}_api_key"), enc)
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

            if (cfg.apiPathType == "responses") {
                val body = com.xincode.provider.ResponsesProtocol.buildRequest(
                    model = cfg.model.ifBlank { "gpt-4o-mini" },
                    messages = (0 until messages.length()).map { messages.getJSONObject(it) },
                    maxOutputTokens = 500
                )
                val endpoint = com.xincode.provider.ResponsesProtocol.endpoint(cfg.baseUrl)
                val req = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .applyExtraHeaders(cfg.extraHeadersJson)
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) return@use Result.failure(RuntimeException("HTTP ${resp.code}: ${respBody.take(200)}"))
                    val parsed = com.xincode.provider.ResponsesProtocol.extractResponse(JSONObject(respBody))
                    if (parsed.errorMessage != null) Result.failure(RuntimeException(parsed.errorMessage))
                    else if (parsed.content.isBlank()) Result.failure(RuntimeException("委托模型无有效返回"))
                    else Result.success(parsed.content.trim())
                }
            } else {
            val body = JSONObject().apply {
                put("model", cfg.model.ifBlank { "gpt-4o-mini" })
                put("messages", messages)
                put("stream", false)
            }
            val req = Request.Builder()
                .url("${cfg.baseUrl.trimEnd('/')}/v1/chat/completions")
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Content-Type", "application/json")
                .applyExtraHeaders(cfg.extraHeadersJson)
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
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun Request.Builder.applyExtraHeaders(value: String): Request.Builder {
        if (value.isBlank()) return this
        try {
            val headers = JSONObject(value)
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isNotBlank() && !headers.isNull(key)) header(key, headers.optString(key))
            }
        } catch (_: Exception) {
            // Optional headers are best-effort.
        }
        return this
    }
}
