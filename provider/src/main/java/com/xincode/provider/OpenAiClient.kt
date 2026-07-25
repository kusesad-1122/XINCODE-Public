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

class OpenAiClient(
    private val database: AppDatabase,
    private val keystore: KeystoreProvider
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
        val autoCompactThresholdPercent: Int = 85
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
            val active = cfgDao.getActive()
                ?: return Result.failure(IllegalStateException("未找到活跃配置，请先在供应商配置中创建"))
            val baseUrl = active.baseUrl.ifBlank {
                return Result.failure(IllegalStateException("base_url 未配置"))
            }
            val model = active.model.ifBlank {
                return Result.failure(IllegalStateException("model 未配置"))
            }
            val apiKey = keystore.decrypt(Base64.decode(active.apiKeyEnc, Base64.NO_WRAP))
            Result.success(ResolvedConfig(
                baseUrl.trimEnd('/'), model, apiKey, active.apiPathType,
                extraHeadersJson = active.extraHeadersJson,
                contextWindow = active.contextWindow,
                autoCompactThresholdPercent = active.autoCompactThresholdPercent
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
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
                    agentStreamResponses(cfg, messages, tools, temperature, maxTokens, topP,
                        responseFormat, onToken, onReasoning, onComplete, onError)
                    return@withContext
                }

                val body = JSONObject().apply {
                    put("model", cfg.model)
                    put("messages", JSONArray(messages))
                    if (tools.length() > 0) {
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
                    } else {
                        put("thinking", JSONObject().apply {
                            put("type", "disabled")
                        })
                        Log.d(TAG, "thinking=disabled")
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

                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    // Capture usage from final SSE chunk (DeepSeek cache stats)
                    val usage = parseSseUsage(line)
                    if (usage != null) lastUsage = usage
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
                        is SseAgentEvent.Done -> { /* stream end marker */ }
                        is SseAgentEvent.Skip -> { /* comment, empty, unparseable */ }
                    }
                }

                // Build final tool_calls list from accumulator
                val toolCalls = tcAcc.values
                    .filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
                    .map { ToolCall(id = it.id, name = it.name, arguments = it.argsBuf.toString()) }

                Log.i(TAG, "✓ Agent SSE complete: $tokenCount tokens, ${toolCalls.size} tool_calls")
                onComplete(AgentStreamResult(content = contentBuf.toString(), toolCalls = toolCalls, usage = lastUsage))
            } catch (e: Exception) {
                Log.e(TAG, "✗ Agent SSE failed: ${e.message}", e)
                onError(ApiError.from(e))
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
                if (tools.length() > 0) put("tools", convertToolsToAnthropic(tools))
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
                .url(cfg.baseUrl + "/v1/messages")
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

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val evt = try { JSONObject(payload) } catch (_: Exception) { continue }
                when (evt.optString("type")) {
                    "message_start" -> {
                        inputTokens = evt.optJSONObject("message")?.optJSONObject("usage")?.optInt("input_tokens", 0) ?: 0
                    }
                    "content_block_start" -> {
                        val idx = evt.optInt("index", 0)
                        val block = evt.optJSONObject("content_block")
                        if (block?.optString("type") == "tool_use") {
                            toolBlocks[idx] = Triple(block.optString("id"), block.optString("name"), StringBuilder())
                        }
                    }
                    "content_block_delta" -> {
                        val idx = evt.optInt("index", 0)
                        val delta = evt.optJSONObject("delta")
                        when (delta?.optString("type")) {
                            "text_delta" -> {
                                val t = delta.optString("text", "")
                                if (t.isNotEmpty()) { contentBuf.append(t); onToken(t) }
                            }
                            "thinking_delta" -> {
                                val t = delta.optString("thinking", "")
                                if (t.isNotEmpty()) onReasoning(t)
                            }
                            "input_json_delta" -> {
                                toolBlocks[idx]?.third?.append(delta.optString("partial_json", ""))
                            }
                        }
                    }
                    "message_delta" -> {
                        outputTokens = evt.optJSONObject("usage")?.optInt("output_tokens", outputTokens) ?: outputTokens
                    }
                    "message_stop" -> { /* end */ }
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
            }
            Log.i(TAG, "✓ Anthropic SSE complete: ${contentBuf.length} chars, ${toolCalls.size} tool_use")
            onComplete(AgentStreamResult(content = contentBuf.toString(), toolCalls = toolCalls, usage = usage))
        } catch (e: Exception) {
            Log.e(TAG, "✗ Anthropic SSE failed: ${e.message}", e)
            onError(ApiError.from(e))
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
            val (instructions, input) = buildResponsesInput(messages)
            val body = JSONObject().apply {
                put("model", cfg.model)
                if (instructions.isNotBlank()) put("instructions", instructions)
                put("input", input)
                if (tools.length() > 0) put("tools", convertToolsToResponses(tools))
                put("temperature", temperature)
                if (maxTokens != null && maxTokens > 0) put("max_output_tokens", maxTokens)
                if (topP != null) put("top_p", topP)
                responseFormatToResponsesText(responseFormat)?.let { put("text", it) } // gap-19
                put("stream", true)
            }

            val request = Request.Builder()
                .url(cfg.baseUrl + "/v1/responses")
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Content-Type", "application/json")
                .applyExtraHeaders(cfg.extraHeadersJson) // gap-08
                .post(body.toString().toRequestBody(JSON))
                .build()

            Log.d(TAG, "→ Responses SSE POST ${cfg.baseUrl}/v1/responses model=${cfg.model} tools=${tools.length()}")
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
            // output_index → (call_id, name, argsBuf) for function_call items
            val toolItems = HashMap<Int, Triple<String, String, StringBuilder>>()
            var inputTokens = 0
            var outputTokens = 0

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val evt = try { JSONObject(payload) } catch (_: Exception) { continue }
                when (evt.optString("type")) {
                    "response.output_text.delta" -> {
                        val t = evt.optString("delta", "")
                        if (t.isNotEmpty()) { contentBuf.append(t); onToken(t) }
                    }
                    "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                        val t = evt.optString("delta", "")
                        if (t.isNotEmpty()) onReasoning(t)
                    }
                    "response.output_item.added" -> {
                        val idx = evt.optInt("output_index", toolItems.size)
                        val item = evt.optJSONObject("item")
                        if (item?.optString("type") == "function_call") {
                            toolItems[idx] = Triple(
                                item.optString("call_id").ifBlank { item.optString("id") },
                                item.optString("name"),
                                StringBuilder(item.optString("arguments", ""))
                            )
                        }
                    }
                    "response.function_call_arguments.delta" -> {
                        val idx = evt.optInt("output_index", -1)
                        val d = evt.optString("delta", "")
                        if (idx >= 0) toolItems[idx]?.third?.append(d)
                    }
                    "response.output_item.done" -> {
                        // 兜底:某些实现只在 done 事件里给出完整 function_call(arguments 齐全)。
                        val idx = evt.optInt("output_index", -1)
                        val item = evt.optJSONObject("item")
                        if (idx >= 0 && item?.optString("type") == "function_call") {
                            val callId = item.optString("call_id").ifBlank { item.optString("id") }
                            val name = item.optString("name")
                            val existing = toolItems[idx]
                            if (existing == null || existing.third.isEmpty()) {
                                toolItems[idx] = Triple(callId, name, StringBuilder(item.optString("arguments", "")))
                            }
                        }
                    }
                    "response.completed", "response.incomplete" -> {
                        val usage = evt.optJSONObject("response")?.optJSONObject("usage")
                        if (usage != null) {
                            inputTokens = usage.optInt("input_tokens", inputTokens)
                            outputTokens = usage.optInt("output_tokens", outputTokens)
                        }
                    }
                    "response.failed", "error" -> {
                        val m = evt.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                            ?: evt.optString("message", "responses stream error")
                        onError(ApiError.from(IOException(m)))
                        return
                    }
                }
            }

            val toolCalls = toolItems.values
                .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
                .map { ToolCall(id = it.first, name = it.second, arguments = it.third.toString().ifBlank { "{}" }) }

            // 映射为 OpenAI 形态 usage,便于下游 token 统计复用。
            val usage = JSONObject().apply {
                put("prompt_tokens", inputTokens)
                put("completion_tokens", outputTokens)
                put("total_tokens", inputTokens + outputTokens)
            }
            Log.i(TAG, "✓ Responses SSE complete: ${contentBuf.length} chars, ${toolCalls.size} function_call")
            onComplete(AgentStreamResult(content = contentBuf.toString(), toolCalls = toolCalls, usage = usage))
        } catch (e: Exception) {
            Log.e(TAG, "✗ Responses SSE failed: ${e.message}", e)
            onError(ApiError.from(e))
        } finally {
            response?.close()
        }
    }

    /**
     * 把 XINCODE 的 OpenAI 形态 messages 转成 Responses `input` 条目数组。
     * 返回 (instructions=聚合的 system 文本, input 数组)。
     * assistant.tool_calls → function_call 条目;role=tool → function_call_output 条目。
     */
    private fun buildResponsesInput(messages: List<JSONObject>): Pair<String, JSONArray> {
        val instructions = StringBuilder()
        val out = JSONArray()
        for (m in messages) {
            when (m.optString("role")) {
                "system" -> {
                    if (instructions.isNotEmpty()) instructions.append("\n\n")
                    instructions.append(m.optString("content"))
                }
                "user" -> {
                    out.put(JSONObject().put("role", "user").put("content", m.optString("content")))
                }
                "assistant" -> {
                    val content = m.optString("content", "")
                    if (content.isNotEmpty()) {
                        out.put(JSONObject().put("role", "assistant").put("content", content))
                    }
                    m.optJSONArray("tool_calls")?.let { tcs ->
                        for (j in 0 until tcs.length()) {
                            val tc = tcs.optJSONObject(j) ?: continue
                            val fn = tc.optJSONObject("function") ?: continue
                            out.put(JSONObject()
                                .put("type", "function_call")
                                .put("call_id", tc.optString("id"))
                                .put("name", fn.optString("name"))
                                .put("arguments", fn.optString("arguments", "{}")))
                        }
                    }
                }
                "tool" -> {
                    out.put(JSONObject()
                        .put("type", "function_call_output")
                        .put("call_id", m.optString("tool_call_id"))
                        .put("output", m.optString("content")))
                }
            }
        }
        return instructions.toString() to out
    }

    /** OpenAI chat tools[{type:function,function:{...}}] → Responses 扁平 [{type:function,name,description,parameters}]. */
    private fun convertToolsToResponses(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            out.put(JSONObject()
                .put("type", "function")
                .put("name", fn.optString("name"))
                .put("description", fn.optString("description"))
                .put("parameters", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object")))
        }
        return out
    }

    /**
     * gap-19:把 chat 形态 response_format({type:json_schema,json_schema:{name,schema,strict}})
     * 转成 Responses 的 text.format({type:json_schema,name,schema,strict})。已是扁平形态则原样透传。
     */
    private fun responseFormatToResponsesText(responseFormat: JSONObject?): JSONObject? {
        if (responseFormat == null) return null
        return try {
            val type = responseFormat.optString("type", "json_schema")
            val nested = responseFormat.optJSONObject("json_schema")
            val format = if (nested != null) {
                JSONObject().apply {
                    put("type", type)
                    put("name", nested.optString("name", "response"))
                    nested.optJSONObject("schema")?.let { put("schema", it) }
                    if (nested.has("strict")) put("strict", nested.optBoolean("strict"))
                }
            } else {
                responseFormat // 调用方已给扁平 format
            }
            JSONObject().put("format", format)
        } catch (_: Exception) { null }
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

    /** Accumulates a single tool_call's fields across streaming SSE chunks. */
    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val argsBuf = StringBuilder()
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

                    // id and type may come in first chunk
                    if (tc.has("id")) acc.id = tc.optString("id", "")
                    // function sub-object
                    val func = tc.optJSONObject("function")
                    if (func != null) {
                        if (func.has("name")) acc.name = func.optString("name", "")
                        if (func.has("arguments")) {
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
            val body = JSONObject().apply {
                put("model", "text-embedding-3-small")
                put("input", input)
            }
            val request = Request.Builder()
                .url("${cfg.baseUrl}/v1/embeddings")
                .addHeader("Authorization", "Bearer ${cfg.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            Log.d(TAG, "→ POST ${cfg.baseUrl}/v1/embeddings len=${input.length}")
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