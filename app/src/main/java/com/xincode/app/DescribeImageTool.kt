package com.xincode.app

import android.util.Base64
import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.provider.ResponsesProtocol
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 多模态委托(受 Hermes `auxiliary.vision` 启发):当**主模型不是多模态**时,把看图这件事
 * 转交给一个单独配置的、能看图的**副模型**——`describe_image` 把图片(本地路径或 URL)base64
 * 后按 OpenAI `image_url` 形态 POST 到副端点,返回**文字描述**,纯文本主模型即可消费。
 *
 * 配置(设置页「视觉委托」):`aux_vision_base_url` / `aux_vision_api_key`(keystore 加密)/ `aux_vision_model`。
 * 服务门控:未配置副端点时不把本工具发给模型。
 */
class DescribeImageTool(
    private val database: AppDatabase,
    private val keystore: KeystoreProvider
) : Tool {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    override val name = "describe_image"
    override val description =
        "看图工具:当你(主模型)看不了图片时,用它把图交给能看图的副模型,拿回文字描述。" +
        "传 image=本地文件路径或图片 URL,question=你想知道的(如'图里有什么文字'/'描述这张截图')。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("image", JSONObject().apply { put("type", "string"); put("description", "本地图片绝对路径或 http(s) URL") })
            put("question", JSONObject().apply { put("type", "string"); put("description", "关于图片你想了解什么(可选)") })
        })
        put("required", JSONArray(listOf("image")))
    }

    // 服务门控:未配置副视觉端点则不暴露。
    override fun isAvailable(): Boolean = kotlinx.coroutines.runBlocking {
        // 走 AuxModels:手填的视觉端点、或【功能模型配置】里给 vision 指了一套配置,都算可用。
        try { AuxModels.isConfigured(database, "vision") } catch (_: Exception) { false }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val image = params["image"]?.trim().orEmpty()
        val question = params["question"]?.trim()?.ifBlank { "详细描述这张图片的内容;若有文字请原样转录。" }
            ?: "详细描述这张图片的内容;若有文字请原样转录。"
        if (image.isEmpty()) return@withContext ToolResult.Error("缺少 image")

        // 同上,统一由 AuxModels 解析(手填委托端点优先,其次功能模型配置)。
        val resolved = AuxModels.resolve(database, keystore, "vision")
            ?: return@withContext ToolResult.Error(
                "未配置视觉模型(设置 → 功能模型配置 → 图像识别,或 设置 → 模型委托)"
            )
        val baseUrl = resolved.baseUrl
        val apiKey = resolved.apiKey
        val model = resolved.model.ifBlank { "gpt-4o-mini" }
        val responses = resolved.apiPathType == "responses"

        // 请求体:远程 URL 直接进 JSON;本地文件【流式】编码,不整张读进内存。
        //
        // 为什么不能图省事用 readBytes + encodeToString:那样峰值堆占用约是图片体积的 8 倍——
        // 原始 byte[] 是 N,Base64 出来的 Java String 按 UTF-16 存是 2.74N,
        // body.toString() 又整份拷贝一次 2.74N,toRequestBody 再转回 UTF-8 是 1.37N。
        // 应用默认堆常见 192-256MB,一张 30MB 的相机原图就会 OOM。
        // 现在改为边读边编码直接写进 sink,内存占用与图片大小无关。
        val localFile = if (image.startsWith("http://") || image.startsWith("https://")) null
        else File(image).also {
            if (!it.exists() || !it.isFile) return@withContext ToolResult.Error("图片文件不存在: $image")
        }

        val requestBody: okhttp3.RequestBody = if (localFile == null) {
            if (responses) {
                val content = JSONArray()
                    .put(JSONObject().put("type", "input_text").put("text", question))
                    .put(JSONObject().put("type", "input_image").put("image_url", image))
                ResponsesProtocol.buildRequest(
                    model = model,
                    messages = listOf(JSONObject().put("role", "user").put("content", content))
                ).toString().toRequestBody(JSON)
            } else {
                JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray()
                            .put(JSONObject().put("type", "text").put("text", question))
                            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image))))
                    }))
                    put("stream", false)
                }.toString().toRequestBody(JSON)
            }
        } else {
            val mime = when (localFile.extension.lowercase()) {
                "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg"
            }
            // 手写 JSON 的前后缀,中间那段 base64 由 writeTo 流式补上。
            // 字符串一律用 JSONObject.quote 转义,避免 question 里的引号/换行破坏 JSON。
            val prefix = if (responses) {
                buildString {
                    append("{\"model\":").append(JSONObject.quote(model))
                    append(",\"input\":[{\"role\":\"user\",\"content\":[")
                    append("{\"type\":\"input_text\",\"text\":").append(JSONObject.quote(question)).append("},")
                    append("{\"type\":\"input_image\",\"image_url\":\"data:").append(mime).append(";base64,")
                }
            } else {
                buildString {
                    append("{\"model\":").append(JSONObject.quote(model))
                    append(",\"messages\":[{\"role\":\"user\",\"content\":[")
                    append("{\"type\":\"text\",\"text\":").append(JSONObject.quote(question)).append("},")
                    append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:").append(mime).append(";base64,")
                }
            }
            val suffix = if (responses) "\"}]}],\"stream\":false}"
            else "\"}}]}],\"stream\":false}"
            object : okhttp3.RequestBody() {
                override fun contentType() = JSON
                /** 精确算出长度,避免退化成 chunked 传输(部分网关不接受)。 */
                override fun contentLength(): Long {
                    val n = localFile.length()
                    val b64 = ((n + 2) / 3) * 4     // base64 定长展开
                    return prefix.toByteArray().size + b64 + suffix.toByteArray().size
                }
                override fun writeTo(sink: okio.BufferedSink) {
                    sink.writeUtf8(prefix)
                    localFile.inputStream().use { input ->
                        // 缓冲区必须是 3 的倍数:base64 每 3 字节编成 4 字符,
                        // 不足 3 的倍数就会提前补 '=' padding,拼起来整段就废了。
                        val buf = ByteArray(48 * 1024)
                        while (true) {
                            var filled = 0
                            while (filled < buf.size) {
                                val n = input.read(buf, filled, buf.size - filled)
                                if (n < 0) break
                                filled += n
                            }
                            if (filled == 0) break
                            sink.writeUtf8(Base64.encodeToString(buf, 0, filled, Base64.NO_WRAP))
                            if (filled < buf.size) break   // 读到文件尾
                        }
                    }
                    sink.writeUtf8(suffix)
                }
            }
        }

        return@withContext try {
            val endpoint = if (responses) ResponsesProtocol.endpoint(baseUrl)
            else "${baseUrl.trimEnd('/')}/v1/chat/completions"
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .applyExtraHeaders(resolved.extraHeadersJson)
                .post(requestBody)
                .build()
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@use ToolResult.Error("视觉副模型 HTTP ${resp.code}: ${respBody.take(200)}")
                val json = JSONObject(respBody)
                val text = if (responses) ResponsesProtocol.extractResponse(json).content
                else json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                if (text.isBlank()) ToolResult.Error("视觉副模型无有效返回")
                else ToolResult.Success(text.trim())
            }
        } catch (e: Exception) {
            ToolResult.Error("describe_image 失败: ${e.message}")
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
