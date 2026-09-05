package com.xincode.app

import android.util.Base64
import android.util.Log
import com.xincode.core.Tool
import com.xincode.core.ToolResult
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 文生图工具 generate_image(OpenAI `/images/generations` 兼容)。
 *
 * 端点解析(按优先):「模型委托」手填的 image 自定义端点(aux_image_*) >
 * 「功能模型配置」里给 图像生成 指定的一套供应商(fn_image_config_id/fn_image_model) >
 * 当前活跃供应商配置(中转站一般也兼容该接口)。
 * 模型:传参 model 优先,否则用解析到的供应商默认模型;中转站按各自模型名路由。
 *
 * 图片【原样】存盘(PNG 字节一字不改,不压缩不降质),路径通过
 * `### 文件名(图片,路径:绝对路径)` 标记回传——模型必须把该标记原样发给用户,
 * 聊天气泡会直接渲染出图片。
 */
class GenerateImageTool(
    private val database: AppDatabase,
    private val keystore: KeystoreProvider,
    private val filesDir: File
) : Tool {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    override val name = "generate_image"
    override val description =
        "文生图:按文字描述生成一张图片(OpenAI /images/generations 兼容,中转站一般可用)。" +
            "传 prompt=画面描述(越具体越好,含主体/风格/构图/色彩);" +
            "model=供应商真实提供的生图模型 ID(可选,不填使用已配置的图像模型;不要传聊天模型);" +
            "size=尺寸(可选,默认 1024x1024)。" +
            "成功后返回一个「### 文件名(图片,路径:…)」标记——你必须把该标记原样贴进给用户的回复里," +
            "图片才会直接显示出来,不要只用文字描述代替。若返回模型或账户不支持,这是供应商能力问题;停止换模型重试并明确告诉用户配置生图供应商。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("prompt", JSONObject().apply { put("type", "string"); put("description", "画面描述") })
            put("model", JSONObject().apply { put("type", "string"); put("description", "生图模型名(可选)") })
            put("size", JSONObject().apply { put("type", "string"); put("description", "如 1024x1024(可选)") })
        })
        put("required", JSONArray(listOf("prompt")))
    }

    override fun isAvailable(): Boolean = true

    override fun unavailableReason(): String = "生图需要可用的供应商配置"


    private data class GeneratedImage(val bytes: ByteArray)
    private class ImageRequestException(val status: Int, message: String) : java.io.IOException(message)

    /** Apply optional provider headers without allowing them to replace authentication. */
    private fun Request.Builder.applyHeaders(extraHeadersJson: String): Request.Builder {
        if (extraHeadersJson.isBlank()) return this
        val blocked = setOf("authorization", "content-type", "content-length", "host")
        try {
            val obj = JSONObject(extraHeadersJson)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.isNotBlank() && key.lowercase() !in blocked && !obj.isNull(key)) {
                    header(key, obj.optString(key))
                }
            }
        } catch (_: Exception) { }
        return this
    }

    private fun imageEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/images/generations")) return base
        val versioned = Regex("""/v\d[^/]*""").containsMatchIn(base)
        return base + if (versioned) "/images/generations" else "/v1/images/generations"
    }

    private fun modelsEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/models")) return base
        val versioned = Regex("""/v\d[^/]*""").containsMatchIn(base)
        return base + if (versioned) "/models" else "/v1/models"
    }

    private fun decodeBase64Payload(value: String): ByteArray {
        val payload = value.substringAfter("base64,", value)
        return Base64.decode(payload, Base64.DEFAULT)
    }

    private fun imageFromDataObject(
        data: JSONObject,
        apiKey: String,
        extraHeadersJson: String,
        endpoint: String
    ): GeneratedImage {
        val b64 = data.optString("b64_json", "")
        if (b64.isNotBlank()) return GeneratedImage(decodeBase64Payload(b64))
        val url = data.optString("url", "")
        if (url.isBlank()) throw ImageRequestException(200, "response data has neither b64_json nor url")
        val requestBuilder = Request.Builder().url(url).get()
            .applyHeaders(extraHeadersJson)
        // Signed image URLs normally need no auth. Forward the API key only when the
        // returned URL is on the same host as the generation endpoint.
        try {
            val returnedHost = java.net.URI(url).host
            val endpointHost = java.net.URI(endpoint).host
            if (!returnedHost.isNullOrBlank() && returnedHost.equals(endpointHost, ignoreCase = true)) {
                requestBuilder.header("Authorization", "Bearer " + apiKey)
            }
        } catch (_: Exception) { }
        val request = requestBuilder.build()
        val response = http.newCall(request).execute()
        response.use {
            if (!it.isSuccessful || it.body == null) {
                throw ImageRequestException(it.code, "image download failed HTTP " + it.code)
            }
            return GeneratedImage(it.body!!.bytes())
        }
    }

    private fun requestImages(
        endpoint: String,
        model: String,
        prompt: String,
        size: String,
        apiKey: String,
        extraHeadersJson: String
    ): Result<GeneratedImage> = runCatching {
        val body = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("size", size)
        // GPT image models return base64 by default and use output_format; DALL-E style
        // endpoints use response_format. Sending response_format to gpt-image-1 is invalid.
        if (model.lowercase().contains("gpt-image") || model.lowercase().contains("chatgpt-image")) {
            body.put("output_format", "png")
        } else {
            body.put("response_format", "b64_json")
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer " + apiKey)
            .applyHeaders(extraHeadersJson)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val response = http.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ImageRequestException(it.code, text.take(600))
            val data = JSONObject(text).optJSONArray("data")?.optJSONObject(0)
                ?: throw ImageRequestException(it.code, "response has no data[0]")
            imageFromDataObject(data, apiKey, extraHeadersJson, endpoint)
        }
    }

    /** OpenAI Responses API image_generation tool, for providers that expose it there. */
    private fun requestResponses(
        baseUrl: String,
        model: String,
        prompt: String,
        apiKey: String,
        extraHeadersJson: String
    ): Result<GeneratedImage> = runCatching {
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        val body = JSONObject()
            .put("model", model)
            .put("input", input)
            .put("tools", JSONArray().put(JSONObject().put("type", "image_generation")))
            .put("store", false)
        val endpoint = com.xincode.provider.ResponsesProtocol.endpoint(baseUrl)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer " + apiKey)
            .applyHeaders(extraHeadersJson)
            .post(body.toString().toRequestBody(JSON))
            .build()
        val response = http.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw ImageRequestException(it.code, text.take(600))
            val json = JSONObject(text)
            val output = json.optJSONArray("output")
            if (output != null) {
                for (i in 0 until output.length()) {
                    val item = output.optJSONObject(i) ?: continue
                    if (item.optString("type", "") == "image_generation_call") {
                        val result = item.optString("result", "")
                        if (result.isNotBlank()) return@runCatching GeneratedImage(decodeBase64Payload(result))
                    }
                }
            }
            val direct = json.optString("b64_json", "")
            if (direct.isNotBlank()) return@runCatching GeneratedImage(decodeBase64Payload(direct))
            throw ImageRequestException(it.code, "Responses output has no image_generation_call result")
        }
    }

    private fun isLikelyImageModel(model: String): Boolean {
        val m = model.lowercase()
        return listOf(
            "image", "dall", "gpt-image", "imagen", "flux", "seedream", "wan2", "kolors",
            "ideogram", "midjourney", "imagine", "stable-diffusion", "sdxl", "hunyuan-image"
        ).any { m.contains(it) }
    }

    /** Best-effort discovery used only to make an upstream model/account failure actionable. */
    private fun discoverImageModels(baseUrl: String, apiKey: String, extraHeadersJson: String): List<String> {
        return try {
            val request = Request.Builder()
                .url(modelsEndpoint(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .applyHeaders(extraHeadersJson)
                .get()
                .build()
            val response = http.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) return emptyList()
                val json = JSONObject(it.body?.string().orEmpty())
                val array = json.optJSONArray("data") ?: json.optJSONArray("models") ?: return emptyList()
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.opt(i)
                        val id = when (item) {
                            is JSONObject -> item.optString("id", item.optString("name", ""))
                            else -> item?.toString().orEmpty()
                        }
                        if (id.isNotBlank() && isLikelyImageModel(id)) add(id)
                    }
                }.distinct().take(8)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun explainFailure(
        error: Throwable,
        baseUrl: String,
        apiKey: String,
        extraHeadersJson: String,
        discoveredCandidates: List<String>? = null
    ): String {
        val raw = error.message.orEmpty()
        val candidates = discoveredCandidates ?: discoverImageModels(baseUrl, apiKey, extraHeadersJson)
        val candidateText = if (candidates.isNotEmpty()) {
            "可探测到的生图模型: " + candidates.joinToString(", ") + "。"
        } else {
            "请在供应商后台确认已开通生图模型，并填写该模型的准确 ID。"
        }
        return when {
            raw.contains("No available compatible accounts", ignoreCase = true) ->
                "【本轮停止重试】中转站没有可用的生图账户或未开放当前模型。客户端请求已正确发出，不是图片显示问题；不要继续猜测或切换聊天模型。$candidateText"
            raw.contains("requires an image model", ignoreCase = true) ->
                "【本轮停止重试】当前中转站拒绝了这个模型名：它不在该站的生图模型白名单中。请使用该站实际提供的 image 模型 ID，不要继续猜测或把聊天模型当生图模型。$candidateText"
            else -> "【本轮停止重试】生图请求失败: $raw。请不要猜测其它聊天模型；先配置供应商支持的生图模型。$candidateText"
        }
    }

    private fun extensionFor(bytes: ByteArray): String {
        if (bytes.size >= 8 &&
            (bytes[0].toInt() and 0xff) == 0x89 && bytes[1].toInt() == 0x50 &&
            bytes[2].toInt() == 0x4e && bytes[3].toInt() == 0x47
        ) return "png"
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xff) == 0xff &&
            (bytes[1].toInt() and 0xff) == 0xd8 && (bytes[2].toInt() and 0xff) == 0xff
        ) return "jpg"
        if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).startsWith("GIF")) return "gif"
        if (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) return "webp"
        return "bin"
    }
    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val prompt = params["prompt"]?.trim().orEmpty()
        if (prompt.isEmpty()) return@withContext ToolResult.Error("缺少 prompt")
        val size = params["size"]?.trim()?.ifBlank { "1024x1024" } ?: "1024x1024"
        try {
            // 自定义 image 委托 > 功能模型配置；没有明确生图模型时不再误用当前聊天模型。
            val aux = try { AuxModels.resolve(database, keystore, "image") } catch (_: Exception) { null }
            val baseUrl: String
            val apiKey: String
            var model = params["model"]?.trim().orEmpty()
            val apiPathType: String
            val extraHeadersJson: String
            if (aux != null && aux.baseUrl.isNotBlank() && aux.apiKey.isNotBlank()) {
                baseUrl = aux.baseUrl
                apiKey = aux.apiKey
                apiPathType = aux.apiPathType
                extraHeadersJson = aux.extraHeadersJson
                if (model.isBlank()) model = aux.model
            } else {
                val cfg = database.providerConfigDao().getActive()
                    ?: return@withContext ToolResult.Error("没有可用的供应商配置，请先在模型与供应商中添加配置")
                if (cfg.baseUrl.isBlank() || cfg.apiKeyEnc.isBlank()) {
                    return@withContext ToolResult.Error("活跃供应商配置缺少 base_url 或 key")
                }
                baseUrl = cfg.baseUrl
                apiPathType = cfg.apiPathType
                extraHeadersJson = cfg.extraHeadersJson
                apiKey = try {
                    keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
                } catch (_: Exception) {
                    return@withContext ToolResult.Error("供应商 key 解密失败，请重新填写保存")
                }
            }
            if (apiPathType == "anthropic") {
                return@withContext ToolResult.Error("当前供应商是 Anthropic 消息接口，不支持 OpenAI 生图协议；请改用 OpenAI 兼容或 Responses 生图端点")
            }

            // 没有明确模型时只从供应商 /models 中找候选，不再拿聊天模型拼请求。
            var discoveredCandidates = emptyList<String>()
            if (model.isBlank() && apiPathType != "custom") {
                discoveredCandidates = discoverImageModels(baseUrl, apiKey, extraHeadersJson)
                model = discoveredCandidates.firstOrNull().orEmpty()
            }
            if (model.isBlank()) {
                return@withContext ToolResult.Error(
                    "未配置生图模型。请在功能模型配置 → 图像生成中绑定生图供应商和准确模型 ID；当前供应商未从 /models 返回可识别的生图模型。"
                )
            }

            fun requestFor(selectedModel: String): Result<GeneratedImage> = when (apiPathType) {
                "responses" -> requestResponses(baseUrl, selectedModel, prompt, apiKey, extraHeadersJson)
                "custom" -> requestImages(baseUrl.trim().trimEnd('/'), selectedModel, prompt, size, apiKey, extraHeadersJson)
                else -> requestImages(imageEndpoint(baseUrl), selectedModel, prompt, size, apiKey, extraHeadersJson)
            }

            var usedModel = model
            var result = requestFor(model)
            // 仅在上游明确说“不是生图模型/无账户”时，尝试一个 /models 明确列出的候选。
            if (result.isFailure && apiPathType != "custom") {
                val firstError = result.exceptionOrNull()
                val rawError = firstError?.message.orEmpty()
                if (rawError.contains("requires an image model", ignoreCase = true) ||
                    rawError.contains("No available compatible accounts", ignoreCase = true)) {
                    if (discoveredCandidates.isEmpty()) {
                        discoveredCandidates = discoverImageModels(baseUrl, apiKey, extraHeadersJson)
                    }
                    val alternate = discoveredCandidates.firstOrNull { !it.equals(model, ignoreCase = true) }
                    if (alternate != null) {
                        val retry = requestFor(alternate)
                        if (retry.isSuccess) {
                            usedModel = alternate
                            result = retry
                        }
                    }
                }
            }
            if (result.isFailure) {
                val error = result.exceptionOrNull() ?: IllegalStateException("unknown image request error")
                return@withContext ToolResult.Error(explainFailure(error, baseUrl, apiKey, extraHeadersJson, discoveredCandidates))
            }
            val raw = result.getOrThrow().bytes
            if (raw.isEmpty()) return@withContext ToolResult.Error("生图返回为空")

            val dir = File(filesDir, "generated_images").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val ext = extensionFor(raw)
            val file = File(dir, "IMG_" + stamp + "_" + (1000..9999).random() + "." + ext)
            file.writeBytes(raw)   // 原样保存：不转码、不重新压缩、不降低清晰度。

            val marker = "### " + file.name + "(图片,路径:" + file.absolutePath + ")"
            val modelNote = if (usedModel.equals(model, ignoreCase = true)) "" else "（供应商原模型不可用，已改用其 /models 返回的生图模型 " + usedModel + "）"
            ToolResult.Success(
                "图片已生成并原图保存: " + file.absolutePath + " (" + raw.size / 1024 + "KB,无压缩)" + modelNote + "。\n" +
                    "系统已将图片直接挂到工具消息；如需在助手文字中重复显示，请把下面标记原样保留:\n" + marker
            )
        } catch (e: Exception) {
            Log.w("GenerateImage", "failed: " + e.message)
            ToolResult.Error("生图异常: " + (e.message ?: "未知错误"))
        }
    }
}
