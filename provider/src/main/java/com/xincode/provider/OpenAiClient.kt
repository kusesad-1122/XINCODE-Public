package com.xincode.provider

import android.util.Base64
import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.security.KeystoreProvider
import com.xincode.provider.HttpCacheProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * @param functionKey 非空时,这个 client 走【功能模型配置】而不是当前活跃配置,
 *   用于让上下文压缩、子智能体、定时任务、Goal 裁判等各用各的模型。
 *   键名与 app 层 `FunctionModels` 约定一致:`fn_<key>_config_id` / `fn_<key>_model`。
 *   做成构造参数而不是可变字段,是因为 OpenAiClient 以单例注入居多,
 *   可变字段会让并发调用互相串配置。需要独立模型的地方各 new 一个即可——
 *   这个类很轻,只持有 database/keystore 引用,HTTP client 是进程级共享的。
 */
class OpenAiClient(
    private val database: AppDatabase,
    private val keystore: KeystoreProvider,
    private val functionKey: String? = null,
    /**
     * 非空时,这个 client 绑定【某个会话】的模型覆盖:
     * sessions.modelProviderConfigId 指定供应商配置,currentModelId 指定模型。
     * 用于实现「一个对话内切换其他厂商模型」——每个会话各自 new 一个 client,互不串配置。
     */
    private val sessionIdOverride: Long? = null
) {
    companion object {
        private const val TAG = "XincodeProvider"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .cache(HttpCacheProvider.get())
        .build()

    // Streaming client: longer read timeout to survive token generation pauses
    private val streamingHttpClient = httpClient.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .cache(HttpCacheProvider.get())
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    // -- config ---------------------------------------------------------------

    private data class ResolvedConfig(
        val baseUrl: String,
        val model: String,
        val apiKey: String,
        val apiPathType: String,
        val extraHeadersJson: String = "",
        val contextWindow: Int = 0,
        val autoCompactThresholdPercent: Int = 85,
        // 能力声明。目前只有 supportsToolCall 直接影响请求体(false 时不发 tools);
        // 其余三个供上层做匹配提示。
        val supportsVision: Boolean = false,
        val supportsAudio: Boolean = false,
        val supportsVideo: Boolean = false,
        val supportsToolCall: Boolean = true
    )

    /** gap-08:把 provider 配置里的 extra_headers(JSON 对象)verbatim 注入请求(可覆盖默认头)。 */
    private fun Request.Builder.applyExtraHeaders(extraHeadersJson: String): Request.Builder {
        if (extraHeadersJson.isBlank()) return this
        try {
            val obj = JSONObject(extraHeadersJson)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k, "")
                if (k.isNotBlank()) this.header(k, v) // header() 覆盖同名默认头
            }
        } catch (_: Exception) { /* 非法 JSON 忽略,不影响请求 */ }
        return this
    }

    /**
     * base_url 是否已经以「版本段」结尾(/v1、/v2、/v4、/v1beta…)。
     *
     * 各家文档给的 base_url 五花八门:
     *   - 不带版本:https://api.deepseek.com          → 需补 /v1/chat/completions
     *   - 带 /v1  :https://api.openai.com/v1         → 只需补 /chat/completions
     *   - 带 /v4  :https://open.bigmodel.cn/api/paas/v4(智谱)→ 只需补 /chat/completions
     * 无脑拼 "/v1/chat/completions" 会产出 /v1/v1/... 或 /v4/v1/...,直接 404/400。
     */
    private fun hasVersionSegment(base: String): Boolean =
        Regex("/v\\d+[a-zA-Z0-9]*$").containsMatchIn(base)

    private fun trimBase(baseUrl: String): String = baseUrl.trim().trimEnd('/')

    /**
     * 按 base_url 是否自带版本段,拼出正确的端点——带版本就只接资源路径,不带才补 /v1。
     * 这样「带不带 /v1」「用 /v4 的智谱」都能一次配通。
     */
    private fun chatEndpoint(baseUrl: String, apiPathType: String): String {
        // custom = 用户提供完整 URL,原样使用(不追加任何东西)。
        if (apiPathType == "custom") return trimBase(baseUrl)
        val base = trimBase(baseUrl)
        val versioned = hasVersionSegment(base)
        return base + when (apiPathType) {
            "anthropic" -> if (versioned) "/messages" else "/v1/messages"
            "responses" -> if (versioned) "/responses" else "/v1/responses"  // gap-07 OpenAI Responses 后端
            else -> if (versioned) "/chat/completions" else "/v1/chat/completions"
        }
    }

    private suspend fun resolveConfig(): Result<ResolvedConfig> {
        return try {
            val cfgDao = database.providerConfigDao()
            // 功能模型配置:指定了就用指定的那套。指定的配置被删掉时【回落到活跃配置】,
            // 而不是让这个功能直接报错——删一个供应商不该顺带把定时任务也弄挂。
            var modelOverride = ""
            val assigned = if (functionKey != null) {
                // 多 Profile:非默认 profile 的键带 p{N}. 前缀。这段逻辑必须与 app 层的
                // Profiles.key() 保持一致 —— provider 模块不能反向依赖 app,所以在这里复刻。
                val pid = database.settingDao().get("profile_current_id")?.toLongOrNull() ?: 0L
                val pfx = if (pid > 0) "p$pid." else ""
                val id = database.settingDao().get("${pfx}fn_${functionKey}_config_id")?.toLongOrNull() ?: 0L
                modelOverride = database.settingDao().get("${pfx}fn_${functionKey}_model")?.trim().orEmpty()
                if (id > 0) cfgDao.getById(id) else null
            } else null

            val sessionOverride = if (assigned == null) resolveSessionOverride(cfgDao) else null
            val active = assigned ?: sessionOverride?.first ?: cfgDao.getActive()
                ?: return Result.failure(IllegalStateException("未找到活跃配置，请先在供应商配置中创建"))
            val baseUrl = active.baseUrl.ifBlank {
                return Result.failure(IllegalStateException("base_url 未配置"))
            }
            val effectiveOverride = sessionOverride?.second?.ifBlank { null } ?: modelOverride.ifBlank { null }
            val model = effectiveOverride?.ifBlank { null } ?: active.model.ifBlank {
                return Result.failure(IllegalStateException("model 未配置"))
            }
            val apiKey = keystore.decrypt(Base64.decode(active.apiKeyEnc, Base64.NO_WRAP))
            Result.success(ResolvedConfig(
                baseUrl.trimEnd('/'), model, apiKey, active.apiPathType,
                extraHeadersJson = active.extraHeadersJson,
                contextWindow = active.contextWindow,
                autoCompactThresholdPercent = active.autoCompactThresholdPercent,
                supportsVision = active.supportsVision,
                supportsAudio = active.supportsAudio,
                supportsVideo = active.supportsVideo,
                supportsToolCall = active.supportsToolCall
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 读会话级模型覆盖:返回 (供应商配置, 模型 id)。
     * 会话没配覆盖、或配置已被删除时返回 null(回落到活跃配置),不让对话直接崩。
     */
    private suspend fun resolveSessionOverride(
        cfgDao: com.xincode.data.ProviderConfigDao
    ): Pair<com.xincode.data.ProviderConfigEntity, String>? {
        val sessionId = sessionIdOverride ?: return null
        val session = database.sessionDao().getById(sessionId) ?: return null
        val providerId = session.modelProviderConfigId
        val model = session.currentModelId?.trim().orEmpty()
        if (providerId == null || providerId <= 0L) {
            // Legacy/session-picker state may contain only a model override. Keep it while
            // following the active provider instead of silently reverting to that provider's
            // default model.
            if (model.isBlank()) return null
            val active = cfgDao.getActive() ?: return null
            return active to model
        }
        val cfg = cfgDao.getById(providerId) ?: return null
        return cfg to model
    }

    // -- model list ------------------------------------------------------------

    /**
     * Fetches available model IDs from GET /v1/models.
     * Takes raw [baseUrl] and [apiKey] directly — no Room/Keystore dependency,
     * so it can be called before saving config.
     */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            // 与 chatEndpoint 同一套规则:base_url 自带版本段(/v1、/v4…)时只接 /models。
            val b = trimBase(baseUrl)
            val url = if (hasVersionSegment(b)) "$b/models" else "$b/v1/models"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            Log.d(TAG, "→ GET $url")

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "← ${response.code} ${responseBody.take(300)}")

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    ApiError.from(IOException("HTTP ${response.code}"), httpCode = response.code)
                )
            }

            val dataArray = JSONObject(responseBody).optJSONArray("data")
                ?: return@withContext Result.success(emptyList())

            val models = mutableListOf<String>()
            for (i in 0 until dataArray.length()) {
                val id = dataArray.optJSONObject(i)?.optString("id", "") ?: ""
                if (id.isNotBlank()) models.add(id)
            }

            Log.i(TAG, "✓ Listed ${models.size} models")
            Result.success(models.sorted())
        } catch (e: Exception) {
            Log.e(TAG, "✗ listModels failed: ${e.message}", e)
            Result.failure(ApiError.from(e))
        }
    }

    // -- non-streaming --------------------------------------------------------

    /**
     * Sends a chat completion request and returns the model's response text.
     * Reads base_url + model from Room, api_key from Keystore.
     */
    suspend fun chat(userMessage: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cfg = resolveConfig().getOrElse { return@withContext Result.failure(it) }

            if (cfg.apiPathType == "responses") {
                val body = ResponsesProtocol.buildRequest(
                    model = cfg.model,
                    messages = listOf(
                        JSONObject().put("role", "system").put("content", "You are a helpful assistant."),
                        JSONObject().put("role", "user").put("content", userMessage)
                    )
                )
                val endpoint = ResponsesProtocol.endpoint(cfg.baseUrl)
                val request = Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .applyExtraHeaders(cfg.extraHeadersJson)
                    .post(body.toString().toRequestBody(JSON))
                    .build()

                Log.d(TAG, "→ POST $endpoint model=${cfg.model} (responses)")
                httpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "← ${response.code} ${responseBody.take(500)}")
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            ApiError.from(IOException("HTTP ${response.code}"), httpCode = response.code)
                        )
                    }
                    val parsed = ResponsesProtocol.extractResponse(JSONObject(responseBody))
                    if (parsed.errorMessage != null) {
                        return@withContext Result.failure(ApiError.from(IOException(parsed.errorMessage)))
                    }
                    if (parsed.content.isBlank()) {
                        return@withContext Result.failure(IllegalStateException("Responses 返回为空"))
                    }
                    Log.i(TAG, "✓ Responses response: ${parsed.content.take(200)}")
                    return@withContext Result.success(parsed.content)
                }
            }

            val body = JSONObject().apply {
                put("model", cfg.model)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "You are a helpful assistant.")
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    })
                })
                put("stream", false)
            }

            val request = Request.Builder()
                .url(chatEndpoint(cfg.baseUrl, cfg.apiPathType))
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Content-Type", "application/json")
                .applyExtraHeaders(cfg.extraHeadersJson)
                .post(body.toString().toRequestBody(JSON))
                .build()

            Log.d(TAG, "→ POST ${chatEndpoint(cfg.baseUrl, cfg.apiPathType)} model=${cfg.model}")

            val response = httpClient.newCall(request).execute()

            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "← ${response.code} ${responseBody.take(500)}")

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    ApiError.from(IOException("HTTP ${response.code}"), httpCode = response.code)
                )
            }

            val json = JSONObject(responseBody)
            // Log usage metrics (DeepSeek prompt caching)
            if (json.has("usage")) {
                val u = json.getJSONObject("usage")
                Log.i("CacheUsage", "prompt_tokens=${u.optInt("prompt_tokens", -1)}, cache_hit=${u.optInt("prompt_cache_hit_tokens", -1)}, cache_miss=${u.optInt("prompt_cache_miss_tokens", -1)}")
            }
            val content = json
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Log.i(TAG, "✓ Response: ${content.take(200)}")
            Result.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "✗ Request failed: ${e.message}", e)
            Result.failure(ApiError.from(e))
        }
    }

    // -- streaming SSE --------------------------------------------------------

    /**
     * Sends a streaming chat completion request (stream=true).
     * Parses SSE line-by-line, calling [onToken] for each content delta.
     * [onComplete] is called when the stream finishes normally.
     * [onError] is called on any failure (HTTP, network, parse).
     */
    suspend fun chatStream(
        userMessage: String,
        systemPrompt: String = "You are a helpful assistant.",
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onComplete: () -> Unit,
        onError: suspend (ApiError) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                val cfg = resolveConfig().getOrElse {
                    onError(ApiError.from(it))
                    return@withContext
                }

                if (cfg.apiPathType == "responses") {
                    chatStreamResponses(cfg, userMessage, systemPrompt, onToken, onReasoning, onComplete, onError)
                    return@withContext
                }

                val body = JSONObject().apply {
                    put("model", cfg.model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage)
                        })
                    })
                    put("stream", true)
                    put("stream_options", JSONObject().apply {
                        put("include_usage", true)
                    })
                }

                val request = Request.Builder()
                    .url(chatEndpoint(cfg.baseUrl, cfg.apiPathType))
                    .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .applyExtraHeaders(cfg.extraHeadersJson)
                    .post(body.toString().toRequestBody(JSON))
                    .build()

                Log.d(TAG, "→ SSE POST ${chatEndpoint(cfg.baseUrl, cfg.apiPathType)} model=${cfg.model} stream=true")

                response = streamingHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val errorJson = try {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message")
                    } catch (e: Exception) { null }
                    val msg = errorJson ?: errorBody.take(200)
                    val apiErr = ApiError.from(IOException("HTTP ${response.code}: $msg"), httpCode = response.code)
                    onError(apiErr)
                    return@withContext
                }

                val source = response.body?.source()
                    ?: run {
                        onError(ApiError.from(IOException("Response body is null")))
                        return@withContext
                    }

                var tokenCount = 0
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val token = parseSseLine(line)
                    if (token != null) {
                        tokenCount++
                        Log.d(TAG, "  token #$tokenCount: $token")
                        onToken(token)
                    } else {
                        // Check usage metrics (final chunk)
                        parseSseUsage(line)
                        // Check for reasoning-only chunks
                        val r = parseSseReasoning(line)
                        if (r != null) onReasoning(r)
                    }
                }

                Log.i(TAG, "✓ SSE stream complete: $tokenCount tokens received")
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "✗ SSE stream failed: ${e.message}", e)
                onError(ApiError.from(e))
            } finally {
                response?.close()
            }
        }
    }

    private suspend fun chatStreamResponses(
        cfg: ResolvedConfig,
        userMessage: String,
        systemPrompt: String,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onComplete: () -> Unit,
        onError: suspend (ApiError) -> Unit
    ) {
        var response: okhttp3.Response? = null
        try {
            val body = ResponsesProtocol.buildRequest(
                model = cfg.model,
                messages = listOf(
                    JSONObject().put("role", "system").put("content", systemPrompt),
                    JSONObject().put("role", "user").put("content", userMessage)
                ),
                stream = true
            )
            val endpoint = ResponsesProtocol.endpoint(cfg.baseUrl)
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Content-Type", "application/json")
                .applyExtraHeaders(cfg.extraHeadersJson)
                .post(body.toString().toRequestBody(JSON))
                .build()

            Log.d(TAG, "→ SSE POST $endpoint model=${cfg.model} (responses)")
            response = streamingHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                val message = try {
                    JSONObject(errorBody).optJSONObject("error")?.optString("message")
                } catch (_: Exception) { null } ?: errorBody.take(200)
                onError(ApiError.from(IOException("HTTP ${response.code}: $message"), httpCode = response.code))
                return
            }
            val source = response.body?.source() ?: run {
                onError(ApiError.from(IOException("Response body is null")))
                return
            }
            val parser = ResponsesStreamParser(onToken, onReasoning)
            while (!source.exhausted()) {
                parser.accept(source.readUtf8Line() ?: break)
            }
            val result = parser.result()
            if (result.errorMessage != null) {
                onError(ApiError.from(IOException(result.errorMessage)))
                return
            }
            if (result.truncated) {
                onError(ApiError.from(IOException("Responses SSE stream truncated")))
                return
            }
            Log.i(TAG, "✓ Responses SSE complete: ${result.content.length} chars")
            onComplete()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "✗ Responses SSE failed: ${t.message}", t)
            onError(ApiError.from(t as? Exception ?: IOException("流式请求异常: ${t::class.java.simpleName}: ${t.message}")))
        } finally {
            response?.close()
        }
    }

    /**
     * Streaming chat completion with full message history and tool support.
     *
     * @param messages  Full conversation history as JSONObjects with role/content/tool_calls/tool_call_id.
     * @param tools     OpenAI-compatible tools JSON array (empty array = no tools).
     * @param onToken   Called for each content delta (for real-time UI display).
     * @param onComplete Called when stream finishes, with accumulated content and any tool_calls.
     * @param onError   Called on any failure.
     */
    suspend fun agentStream(
        messages: List<JSONObject>,
        tools: JSONArray,
        temperature: Float = 1.0f,
        thinkingEnabled: Boolean = false,
        thinkingLevel: Int = 2,
        maxTokens: Int? = null,   // gap-09
        topP: Float? = null,      // gap-09
        responseFormat: JSONObject? = null, // gap-19 结构化输出 response_format
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit = {},
        onComplete: (AgentStreamResult) -> Unit,
        onError: suspend (ApiError) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            var response: okhttp3.Response? = null
            try {
                val cfg = resolveConfig().getOrElse {
                    onError(ApiError.from(it))
                    return@withContext
                }

                // gap-06:Anthropic 原生 /v1/messages 走独立分支(body/鉴权/SSE 均不同)。
                if (cfg.apiPathType == "anthropic") {
                    agentStreamAnthropic(cfg, messages, tools, temperature, thinkingEnabled,
                        maxTokens, topP, onToken, onReasoning, onComplete, onError)
                    return@withContext
                }
                // gap-07:OpenAI Responses /v1/responses 走独立分支。
                if (cfg.apiPathType == "responses") {
                    agentStreamResponses(cfg, messages, tools, temperature, thinkingEnabled, thinkingLevel,
                        maxTokens, topP,
                        responseFormat, onToken, onReasoning, onComplete, onError)
                    return@withContext
                }

                val body = JSONObject().apply {
                    put("model", cfg.model)
                    put("messages", JSONArray(messages))
                    // 供应商配置里关掉「模型支持 ToolCall」就不发 tools:有些网关收到不认识的
                    // tools 字段直接 400,关掉是让这类端点至少能聊天的唯一出路。
                    if (tools.length() > 0 && cfg.supportsToolCall) {
                        put("tools", tools)
                    }
                    put("temperature", temperature)
                    if (maxTokens != null && maxTokens > 0) put("max_tokens", maxTokens)
                    if (topP != null) put("top_p", topP)
                    if (responseFormat != null) put("response_format", responseFormat) // gap-19
                    put("stream", true)
                    put("stream_options", JSONObject().apply {
                        put("include_usage", true)
                    })
                    // DeepSeek thinking control (root-level, not extra_body)
                    if (thinkingEnabled) {
                        put("thinking", JSONObject().apply {
                            put("type", "enabled")
                        })
                        // reasoning_effort 的合法取值只有 low / medium / high。
                        // 此前 UI 的「Max」档直接发 "max",绝大多数供应商会直接 400
                        // (实测报错:Input should be 'low', 'medium' or 'high')。
                        // UI 档位保持不变,这里把 Max 归并到 high 发送。
                        val effort = when (thinkingLevel) {
                            0 -> "low"
                            1 -> "medium"
                            else -> "high"   // 2=High、3、4(Max)统一发 high
                        }
                        put("reasoning_effort", effort)
                        Log.d(TAG, "thinking=enabled reasoning_effort=$effort")
                    }
                    // 关闭思考时【不发】任何字段。thinking 是 DeepSeek 私有扩展,
                    // 以前连 disabled 也无条件发,严格校验的网关会直接 400 拒掉整个请求
                    // ——表现成「这个供应商配好了却完全用不了」,且报错跟思考毫无关系。
                    else {
                        Log.d(TAG, "thinking=disabled (omitting field)")
                    }
                }

                // 隐私:绝不把请求体(含 system/用户 prompt)写进日志——曾用 Log.wtf 打印前 500 字,已移除。

                val request = Request.Builder()
                    .url(chatEndpoint(cfg.baseUrl, cfg.apiPathType))
                    .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .applyExtraHeaders(cfg.extraHeadersJson) // gap-08
                    .post(body.toString().toRequestBody(JSON))
                    .build()

                Log.d(TAG, "→ Agent SSE POST ${chatEndpoint(cfg.baseUrl, cfg.apiPathType)} model=${cfg.model} tools=${tools.length()}")

                response = streamingHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val errorJson = try {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message")
                    } catch (e: Exception) { null }
                    val msg = errorJson ?: errorBody.take(200)
                    onError(ApiError.from(IOException("HTTP ${response.code}: $msg"), httpCode = response.code))
                    return@withContext
                }

                val source = response.body?.source()
                    ?: run {
                        onError(ApiError.from(IOException("Response body is null")))
                        return@withContext
                    }

                var tokenCount = 0
                val contentBuf = StringBuilder()
                // Accumulate tool_calls by index (tool_calls arrive in chunks across SSE events)
                val tcAcc = mutableMapOf<Int, ToolCallAccumulator>()
                var lastUsage: org.json.JSONObject? = null
                // 是否见到了「本次响应正常结束」的标记([DONE] 或带 finish_reason 的 chunk)。
                // 没见到就退出循环 = 连接被掐断,拿到的是半截回复。
                var sawTerminator = false

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    // Capture usage from final SSE chunk (DeepSeek cache stats)
                    val usage = parseSseUsage(line)
                    if (usage != null) lastUsage = usage
                    if (sseHasFinishReason(line)) sawTerminator = true
                    val parsed = parseSseLineAgent(line, tcAcc)
                    when (parsed) {
                        is SseAgentEvent.Token -> {
                            tokenCount++
                            contentBuf.append(parsed.text)
                            onToken(parsed.text)
                        }
                        is SseAgentEvent.Reasoning -> {
                            onReasoning(parsed.text)
                        }
                        is SseAgentEvent.Done -> sawTerminator = true
                        is SseAgentEvent.Skip -> { /* comment, empty, unparseable */ }
                    }
                }

                // Build final tool_calls list from accumulator
                val toolCalls = tcAcc.values
                    .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                    .map { ToolCall(id = it.id, name = it.name, arguments = it.argsBuf.toString()) }

                if (!sawTerminator) Log.w(TAG, "⚠ Agent SSE truncated: $tokenCount tokens, no [DONE]/finish_reason")
                Log.i(TAG, "✓ Agent SSE complete: $tokenCount tokens, ${toolCalls.size} tool_calls")
                onComplete(AgentStreamResult(
                    content = contentBuf.toString(), toolCalls = toolCalls, usage = lastUsage,
                    truncated = !sawTerminator
                ))
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c   // 用户点「停止」:必须原样上抛,否则取消语义丢失
            } catch (t: Throwable) {
                // 必须接到 Throwable:OOM / StackOverflowError 属于 Error,catch(Exception) 接不住,
                // 而上层 callModel 的 continuation 只由 onComplete/onError 唤醒 —— 漏一个就永久挂起。
                Log.e(TAG, "✗ Agent SSE failed: ${t.message}", t)
                onError(ApiError.from(t as? Exception ?: IOException("流式请求异常: ${t::class.java.simpleName}: ${t.message}")))
            } finally {
                response?.close()
            }
        }
    }

    // -- gap-06 Anthropic Messages 原生协议 --------------------------------------

    /**
     * Anthropic /v1/messages 原生流式:顶层 system、max_tokens 必填、content blocks、
     * x-api-key + anthropic-version 鉴权;SSE 解析 content_block_delta(text/input_json)与 message_delta。
     * 对外行为(onToken/onReasoning/onComplete/onError + AgentStreamResult)与 OpenAI 路径一致,
     * AgentCore 工具回环无需感知协议差异。
     */
    private suspend fun agentStreamAnthropic(
        cfg: ResolvedConfig,
        messages: List<JSONObject>,
        tools: JSONArray,
        temperature: Float,
        thinkingEnabled: Boolean,
        maxTokens: Int?,
        topP: Float?,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onComplete: (AgentStreamResult) -> Unit,
        onError: suspend (ApiError) -> Unit
    ) {
        var response: okhttp3.Response? = null
        try {
            val (system, anthMessages) = buildAnthropicMessages(messages)
            // Hermes-③ prompt 缓存:system 与最近若干条消息打 cache_control 断点,长对话复用缓存前缀省费。
            applyAnthropicCacheControl(anthMessages)
            val body = JSONObject().apply {
                put("model", cfg.model)
                put("max_tokens", if (maxTokens != null && maxTokens > 0) maxTokens else 4096) // 必填
                // system 作为 content-block 数组并打 cache_control(ephemeral),稳定前缀跨轮命中缓存。
                if (system.isNotBlank()) put("system", JSONArray().put(JSONObject().apply {
                    put("type", "text"); put("text", system)
                    put("cache_control", JSONObject().put("type", "ephemeral"))
                }))
                put("messages", anthMessages)
                if (tools.length() > 0 && cfg.supportsToolCall) put("tools", convertToolsToAnthropic(tools))
                put("temperature", temperature)
                if (topP != null) put("top_p", topP)
                if (thinkingEnabled) {
                    put("thinking", JSONObject().apply {
                        put("type", "enabled"); put("budget_tokens", 4096)
                    })
                }
                put("stream", true)
            }

            val request = Request.Builder()
                // 必须走 chatEndpoint:base_url 自带 /v1 时硬拼会变成 /v1/v1/messages → 404。
                // chat_completions 分支早就修了,这两条分支之前漏掉。
                .url(chatEndpoint(cfg.baseUrl, "anthropic"))
                .addHeader("x-api-key", cfg.apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .applyExtraHeaders(cfg.extraHeadersJson) // gap-08
                .post(body.toString().toRequestBody(JSON))
                .build()

            Log.d(TAG, "→ Anthropic SSE POST ${cfg.baseUrl}/v1/messages model=${cfg.model} tools=${tools.length()}")
            response = streamingHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                val msg = try { JSONObject(errBody).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    ?: errBody.take(200)
                onError(ApiError.from(IOException("HTTP ${response.code}: $msg"), httpCode = response.code))
                return
            }
            val source = response.body?.source() ?: run {
                onError(ApiError.from(IOException("Response body is null"))); return
            }

            val contentBuf = StringBuilder()
            // index → (id, name, argsBuf) for tool_use blocks
            val toolBlocks = HashMap<Int, Triple<String, String, StringBuilder>>()
            var inputTokens = 0
            var outputTokens = 0
            // Anthropic 的正常收尾是 message_stop;没见到就说明流被掐断(见 AgentStreamResult.truncated)。
            var sawTerminator = false
            var cacheReadTokens = 0
            var cacheWriteTokens = 0

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val evt = try { JSONObject(payload) } catch (_: Exception) { continue }
                when (evt.optString("type")) {
                    "message_start" -> {
                        val u = evt.optJSONObject("message")?.optJSONObject("usage")
                        inputTokens = u?.optInt("input_tokens", 0) ?: 0
                        // 之前完全没取这两个,导致 Anthropic 的缓存统计恒为 0
                        cacheReadTokens = u?.optInt("cache_read_input_tokens", 0) ?: 0
                        cacheWriteTokens = u?.optInt("cache_creation_input_tokens", 0) ?: 0
                    }
                    "content_block_start" -> {
                        val idx = evt.optInt("index", 0)
                        val block = evt.optJSONObject("content_block")
                        if (block?.optStr("type") == "tool_use") {
                            toolBlocks[idx] = Triple(block.optStr("id"), block.optStr("name"), StringBuilder())
                        }
                    }
                    "content_block_delta" -> {
                        val idx = evt.optInt("index", 0)
                        val delta = evt.optJSONObject("delta")
                        when (delta?.optStr("type")) {
                            "text_delta" -> {
                                val t = delta.optStr("text")
                                if (t.isNotEmpty()) { contentBuf.append(t); onToken(t) }
                            }
                            "thinking_delta" -> {
                                val t = delta.optStr("thinking")
                                if (t.isNotEmpty()) onReasoning(t)
                            }
                            "input_json_delta" -> {
                                // 不能用 optString:partial_json 为 null 时会 append 字面 "null",
                                // 直接毁掉正在拼装的参数 JSON。
                                toolBlocks[idx]?.third?.append(delta.optStr("partial_json"))
                            }
                        }
                    }
                    "message_delta" -> {
                        outputTokens = evt.optJSONObject("usage")?.optInt("output_tokens", outputTokens) ?: outputTokens
                    }
                    "message_stop" -> sawTerminator = true
                    "error" -> {
                        val m = evt.optJSONObject("error")?.optString("message") ?: "anthropic stream error"
                        onError(ApiError.from(IOException(m)))
                        return
                    }
                }
            }

            val toolCalls = toolBlocks.values
                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                .map { ToolCall(id = it.first, name = it.second, arguments = it.third.toString().ifBlank { "{}" }) }

            // 映射为 OpenAI 形态的 usage,便于下游 token 统计复用。
            val usage = JSONObject().apply {
                put("prompt_tokens", inputTokens)
                put("completion_tokens", outputTokens)
                put("total_tokens", inputTokens + outputTokens)
                put("cache_read_input_tokens", cacheReadTokens)
                put("cache_creation_input_tokens", cacheWriteTokens)
                // 关键语义标记:Anthropic 的 input_tokens【不含】缓存部分,缓存是独立字段。
                // 而 OpenAI/DeepSeek 的 prompt_tokens 是【含】缓存的。两者混在一起统计,
                // 缓存命中率会被算错一倍——下游 UsageRecorder 靠这个标记决定要不要扣减。
                put("input_includes_cache", false)
            }
            if (!sawTerminator) Log.w(TAG, "⚠ Anthropic SSE truncated: no message_stop")
            Log.i(TAG, "✓ Anthropic SSE complete: ${contentBuf.length} chars, ${toolCalls.size} tool_use")
            onComplete(AgentStreamResult(
                content = contentBuf.toString(), toolCalls = toolCalls, usage = usage,
                truncated = !sawTerminator
            ))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "✗ Anthropic SSE failed: ${t.message}", t)
            onError(ApiError.from(t as? Exception ?: IOException("流式请求异常: ${t::class.java.simpleName}: ${t.message}")))
        } finally {
            response?.close()
        }
    }

    // -- gap-07 OpenAI Responses /v1/responses 原生协议 --------------------------

    /**
     * OpenAI Responses API /v1/responses 流式:与 chat_completions 不同——请求用 `input` 条目数组
     * (role 消息 / function_call / function_call_output),tools 为扁平 {type:function,name,...},
     * max_tokens→max_output_tokens,结构化输出走 text.format(gap-19)。
     * SSE 事件:response.output_text.delta / response.reasoning_summary_text.delta /
     * response.output_item.added(function_call)/ response.function_call_arguments.delta / response.completed。
     * 对外行为(onToken/onReasoning/onComplete/onError + AgentStreamResult)与其余路径一致。
     */
    private suspend fun agentStreamResponses(
        cfg: ResolvedConfig,
        messages: List<JSONObject>,
        tools: JSONArray,
        temperature: Float,
        thinkingEnabled: Boolean,
        thinkingLevel: Int,
        maxTokens: Int?,
        topP: Float?,
        responseFormat: JSONObject?,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onComplete: (AgentStreamResult) -> Unit,
        onError: suspend (ApiError) -> Unit
    ) {
        var response: okhttp3.Response? = null
        try {
            val body = ResponsesProtocol.buildRequest(
                model = cfg.model,
                messages = messages,
                tools = if (cfg.supportsToolCall) tools else JSONArray(),
                temperature = temperature,
                maxOutputTokens = maxTokens,
                topP = topP,
                responseFormat = responseFormat,
                stream = true,
                thinkingEnabled = thinkingEnabled,
                thinkingLevel = thinkingLevel
            )

            val endpoint = ResponsesProtocol.endpoint(cfg.baseUrl)
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Content-Type", "application/json")
                .applyExtraHeaders(cfg.extraHeadersJson) // gap-08
                .post(body.toString().toRequestBody(JSON))
                .build()

            Log.d(TAG, "→ Responses SSE POST $endpoint model=${cfg.model} tools=${tools.length()}")
            response = streamingHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                val msg = try { JSONObject(errBody).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    ?: errBody.take(200)
                onError(ApiError.from(IOException("HTTP ${response.code}: $msg"), httpCode = response.code))
                return
            }
            val source = response.body?.source() ?: run {
                onError(ApiError.from(IOException("Response body is null"))); return
            }

            val parser = ResponsesStreamParser(onToken, onReasoning)
            while (!source.exhausted()) {
                parser.accept(source.readUtf8Line() ?: break)
            }

            val result = parser.result()
            if (result.errorMessage != null) {
                onError(ApiError.from(IOException(result.errorMessage)))
                return
            }
            if (result.truncated) {
                Log.w(TAG, "⚠ Responses SSE truncated: no response.completed")
            }
            Log.i(TAG, "✓ Responses SSE complete: ${result.content.length} chars, ${result.toolCalls.size} function_call")
            onComplete(AgentStreamResult(
                content = result.content,
                toolCalls = result.toolCalls,
                usage = result.usage,
                truncated = result.truncated
            ))
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "✗ Responses SSE failed: ${t.message}", t)
            onError(ApiError.from(t as? Exception ?: IOException("流式请求异常: ${t::class.java.simpleName}: ${t.message}")))
        } finally {
            response?.close()
        }
    }

    /** 把 XINCODE 的 OpenAI 形态 messages 转成 Anthropic(顶层 system + content blocks)。 */
    private fun buildAnthropicMessages(messages: List<JSONObject>): Pair<String, JSONArray> {
        val systemSb = StringBuilder()
        val out = JSONArray()
        var idx = 0
        while (idx < messages.size) {
            val m = messages[idx]
            when (m.optString("role")) {
                "system" -> {
                    if (systemSb.isNotEmpty()) systemSb.append("\n\n")
                    systemSb.append(m.optString("content"))
                    idx++
                }
                "user" -> {
                    out.put(JSONObject().put("role", "user")
                        .put("content", JSONArray().put(textBlock(m.optString("content")))))
                    idx++
                }
                "assistant" -> {
                    val blocks = JSONArray()
                    val content = m.optString("content", "")
                    if (content.isNotEmpty()) blocks.put(textBlock(content))
                    m.optJSONArray("tool_calls")?.let { tcs ->
                        for (j in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(j) ?: continue
                            val fn = tc.optJSONObject("function") ?: continue
                            val input = try { JSONObject(fn.optString("arguments", "{}")) } catch (_: Exception) { JSONObject() }
                            blocks.put(JSONObject().put("type", "tool_use")
                                .put("id", tc.optString("id")).put("name", fn.optString("name")).put("input", input))
                        }
                    }
                    if (blocks.length() == 0) blocks.put(textBlock(""))
                    out.put(JSONObject().put("role", "assistant").put("content", blocks))
                    idx++
                }
                "tool" -> {
                    // 连续 tool 结果聚合进一条 user 消息(Anthropic 要求 tool_result 在 user 回合)。
                    val blocks = JSONArray()
                    while (idx < messages.size && messages[idx].optString("role") == "tool") {
                        val t = messages[idx]
                        blocks.put(JSONObject().put("type", "tool_result")
                            .put("tool_use_id", t.optString("tool_call_id")).put("content", t.optString("content")))
                        idx++
                    }
                    out.put(JSONObject().put("role", "user").put("content", blocks))
                }
                else -> idx++
            }
        }
        return systemSb.toString() to out
    }

    private fun textBlock(text: String): JSONObject =
        JSONObject().put("type", "text").put("text", text)

    /** OpenAI tools[{type:function,function:{name,description,parameters}}] → Anthropic[{name,description,input_schema}]. */
    private fun convertToolsToAnthropic(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            out.put(JSONObject()
                .put("name", fn.optString("name"))
                .put("description", fn.optString("description"))
                .put("input_schema", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object")))
        }
        return out
    }

    /**
     * Hermes-③:给最近 [breakpoints] 条消息的最后一个 content block 打 `cache_control:{type:ephemeral}`。
     * Anthropic 最多 4 个断点:system 已占 1,这里再给最近 3 条形成滑动窗口——每轮把缓存前缀往后延一点。
     * 只对 content 为 JSONArray(block 形态)的消息生效;字符串 content 跳过(不影响正确性)。
     */
    private fun applyAnthropicCacheControl(anthMessages: JSONArray, breakpoints: Int = 3) {
        var marked = 0
        var i = anthMessages.length() - 1
        while (i >= 0 && marked < breakpoints) {
            val msg = anthMessages.optJSONObject(i)
            val blocks = msg?.optJSONArray("content")
            if (blocks != null && blocks.length() > 0) {
                val last = blocks.optJSONObject(blocks.length() - 1)
                if (last != null && !last.has("cache_control")) {
                    last.put("cache_control", JSONObject().put("type", "ephemeral"))
                    marked++
                }
            }
            i--
        }
    }

    // -- SSE parsing internals ---------------------------------------------------

    /**
     * 安全取字符串:JSON null 一律当作「没有这个值」。
     *
     * 必须有这个函数,因为 org.json 的 optString 碰到 JSONObject.NULL 返回的是【字面字符串
     * "null"】,而不是传入的默认值。流式增量里 name / arguments / delta 为 null 是常态,
     * 直接用 optString 的后果是:工具名变成 "null" 派发失败、参数 JSON 里被塞进 "null" 而
     * 解析不了、回复正文里凭空冒出 "null" 三个字。
     */
    private fun JSONObject.optStr(key: String): String =
        if (isNull(key)) "" else optString(key, "")

    /** Accumulates a single tool_call's fields across streaming SSE chunks. */
    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val argsBuf = StringBuilder()
    }

    /**
     * 这一行是否携带非空 finish_reason —— 即服务端宣告「本次生成到此为止」。
     *
     * 不少 OpenAI 兼容网关不发 `data: [DONE]`,只发带 finish_reason 的最后一个 chunk。
     * 光认 [DONE] 会把这类供应商的每次正常回复都误判成截断,所以两个信号都认。
     */
    private fun sseHasFinishReason(raw: String): Boolean {
        val line = raw.trimStart()
        if (!line.startsWith("data:")) return false
        val data = line.removePrefix("data:").trimStart()
        if (data.isEmpty() || data == "[DONE]") return false
        return try {
            val choices = JSONObject(data).optJSONArray("choices") ?: return false
            val first = choices.optJSONObject(0) ?: return false
            !first.isNull("finish_reason") && first.optString("finish_reason").isNotEmpty()
        } catch (_: Exception) { false }
    }

    /** What one SSE line yielded for the agent loop. */
    private sealed class SseAgentEvent {
        data class Token(val text: String) : SseAgentEvent()
        data class Reasoning(val text: String) : SseAgentEvent()
        object Done : SseAgentEvent()
        object Skip : SseAgentEvent()
    }

    /**
     * Parses one SSE line for the agent loop.
     * Handles both content tokens and incremental tool_calls.
     */
    private fun parseSseLineAgent(raw: String, tcAcc: MutableMap<Int, ToolCallAccumulator>): SseAgentEvent {
        val line = raw.trimStart()
        if (line.isEmpty()) return SseAgentEvent.Skip
        if (!line.startsWith("data:")) return SseAgentEvent.Skip

        val data = line.removePrefix("data:").trimStart()
        if (data == "[DONE]") return SseAgentEvent.Done

        return try {
            val json = JSONObject(data)
            // Check usage on EVERY SSE line — safe no-op if no usage field
            parseSseUsage(raw)
            val choices = json.optJSONArray("choices") ?: run {
                return SseAgentEvent.Skip
            }
            val first = choices.optJSONObject(0) ?: return SseAgentEvent.Skip
            val delta = first.optJSONObject("delta")
            if (delta == null) {
                return SseAgentEvent.Skip
            }

            // --- content token ---
            if (!delta.isNull("content")) {
                val token = delta.optString("content", "")
                if (token.isNotEmpty()) return SseAgentEvent.Token(token)
            }

            // --- reasoning_content (DeepSeek-R1 style thinking) ---
            if (!delta.isNull("reasoning_content")) {
                val r = delta.optString("reasoning_content", "")
                if (r.isNotEmpty()) return SseAgentEvent.Reasoning(r)
            }
            // --- reasoning (alternative field name) ---
            if (!delta.isNull("reasoning")) {
                val r = delta.optString("reasoning", "")
                if (r.isNotEmpty()) return SseAgentEvent.Reasoning(r)
            }

            // --- tool_calls (incremental) ---
            val toolCallsDelta = delta.optJSONArray("tool_calls")
            if (toolCallsDelta != null) {
                for (i in 0 until toolCallsDelta.length()) {
                    val tc = toolCallsDelta.optJSONObject(i) ?: continue
                    val idx = tc.optInt("index", i)
                    val acc = tcAcc.getOrPut(idx) { ToolCallAccumulator() }

                    // 关键:org.json 的 optString 遇到 JSON null 会返回【字面字符串 "null"】,
                    // 而不是给的默认值。而多数供应商的增量 chunk 长这样:
                    //   第 1 帧 {"id":"call_x","function":{"name":"web_search","arguments":""}}
                    //   后续帧 {"function":{"name":null,"arguments":"{\"query\""}}
                    // 原来的 has("name") + optString 会把已经正确的名字覆盖成 "null",
                    // 于是派发时报「未知工具: null」,模型看到报错就重试,再次被覆盖 —— 无限循环。
                    // 所以必须 isNull 判空,且不拿后到的空值覆盖已经拿到的值。
                    if (!tc.isNull("id")) {
                        val id = tc.optString("id", "")
                        if (id.isNotEmpty()) acc.id = id
                    }
                    // function sub-object
                    val func = tc.optJSONObject("function")
                    if (func != null) {
                        if (!func.isNull("name")) {
                            val n = func.optString("name", "")
                            if (n.isNotEmpty()) acc.name = n
                        }
                        // arguments 同理:append 一个字面 "null" 会直接毁掉参数 JSON。
                        if (!func.isNull("arguments")) {
                            acc.argsBuf.append(func.optString("arguments", ""))
                        }
                    }
                }
            }

            SseAgentEvent.Skip
        } catch (e: Exception) {
            // Re-parse as usage (final chunk may have usage but no delta)
            parseSseUsage(raw)
            Log.w(TAG, "SSE agent parse skip: ${raw.take(80)}")
            SseAgentEvent.Skip
        }
    }

    /**
     * Extracts reasoning_content from an SSE line that has no content token.
     * Returns the reasoning text or null.
     */
    private fun parseSseReasoning(raw: String): String? {
        val line = raw.trimStart()
        if (line.isEmpty() || !line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trimStart()
        if (data == "[DONE]") return null
        return try {
            val json = JSONObject(data)
            val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return null
            // DeepSeek-R1: reasoning_content
            if (!delta.isNull("reasoning_content")) {
                delta.optString("reasoning_content", "").ifEmpty { null }
            } else if (!delta.isNull("reasoning")) {
                delta.optString("reasoning", "").ifEmpty { null }
            } else null
        } catch (e: Exception) { null }
    }

    /** Extracts usage metrics from an SSE data line (final chunk before [DONE]).
     * Returns the usage JSONObject if found, null otherwise.
     * Logs with tag CacheUsage. Only prints the chunk that carries usage data.
     */
    private fun parseSseUsage(raw: String): org.json.JSONObject? {
        val line = raw.trimStart()
        if (line.isEmpty() || !line.startsWith("data:")) return null
        val data = line.removePrefix("data:").trimStart()
        if (data == "[DONE]") return null
        try {
            val json = JSONObject(data)
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                Log.i("CacheUsage", "prompt_tokens=${usage.optInt("prompt_tokens", -1)}, cache_hit=${usage.optInt("prompt_cache_hit_tokens", -1)}, cache_miss=${usage.optInt("prompt_cache_miss_tokens", -1)}")
                Log.v("RawSSE", raw.trimStart())
                return usage
            } else {
                // Also log the finish_reason chunk if there's no usage (non-DeepSeek providers)
                val choices = json.optJSONArray("choices")
                if (choices != null) {
                    for (i in 0 until choices.length()) {
                        val c = choices.optJSONObject(i)
                        if (c != null && !c.isNull("finish_reason")) {
                            Log.v("RawSSE", raw.trimStart())
                            return null
                        }
                    }
                }
            }
        } catch (_: Exception) { /* ignore parse errors */ }
        return null
    }

    /**
     * Extracts content token from an SSE line. Returns null if no content.
     */
    private fun parseSseLine(raw: String): String? {
        val line = raw.trimStart()
        if (line.isEmpty()) return null
        if (!line.startsWith("data:")) return null

        val data = line.removePrefix("data:").trimStart()
        // [DONE] is not valid JSON — skip before any parsing attempt
        if (data == "[DONE]") return null

        return try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices") ?: return null
            val first = choices.optJSONObject(0) ?: return null
            val delta = first.optJSONObject("delta") ?: return null
            // Explicit null check: delta.content may be JSON null (first/last chunk)
            if (delta.isNull("content")) return null
            val token = delta.optString("content", "")
            // Only return non-empty content; never include role/finish_reason
            token.ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "SSE parse skip: ${data.take(80)}")
            null
        }
    }

    // -- embeddings --------------------------------------------------------

    /**
     * Generates vector embedding for the given input text.
     * Calls POST {base_url}/v1/embeddings.
     * Returns the embedding as FloatArray, or null on failure (e.g. provider doesn't support it).
     */
    suspend fun embeddings(input: String): FloatArray? = withContext(Dispatchers.IO) {
        try {
            val cfg = resolveConfig().getOrElse {
                Log.w(TAG, "embeddings: config resolution failed: ${it.message}")
                return@withContext null
            }
            // 模型名以前写死 text-embedding-3-small —— 非 OpenAI 供应商基本都没有这个模型,
            // 记忆向量化会一直静默失败。现在优先用【功能模型配置】里给 embedding 指定的模型。
            val embPid = database.settingDao().get("profile_current_id")?.toLongOrNull() ?: 0L
            val embPfx = if (embPid > 0) "p$embPid." else ""
            val embModel = database.settingDao().get("${embPfx}fn_embedding_model")?.trim()
                ?.ifBlank { null } ?: "text-embedding-3-small"
            val body = JSONObject().apply {
                put("model", embModel)
                put("input", input)
            }
            // 端点同样要按版本段拼:base_url 自带 /v1 时硬拼会变成 /v1/v1/embeddings。
            val embUrl = trimBase(cfg.baseUrl).let {
                if (hasVersionSegment(it)) "$it/embeddings" else "$it/v1/embeddings"
            }
            val request = Request.Builder()
                .url(embUrl)
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            Log.d(TAG, "→ POST $embUrl model=$embModel len=${input.length}")
            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.w(TAG, "embeddings: HTTP ${response.code} — provider may not support embeddings")
                return@withContext null
            }
            val data = JSONObject(responseBody).getJSONArray("data")
            val emb = data.getJSONObject(0).getJSONArray("embedding")
            val floats = FloatArray(emb.length())
            for (i in 0 until emb.length()) {
                floats[i] = emb.getDouble(i).toFloat()
            }
            Log.i(TAG, "✓ embedding: ${floats.size} dims")
            floats
        } catch (e: Exception) {
            Log.w(TAG, "embeddings failed: ${e.message}")
            null
        }
    }
}
