package com.xincode.app

import android.util.Base64
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
        try {
            !database.settingDao().get("aux_vision_base_url").isNullOrBlank() &&
                !database.settingDao().get("aux_vision_api_key").isNullOrBlank()
        } catch (_: Exception) { false }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val image = params["image"]?.trim().orEmpty()
        val question = params["question"]?.trim()?.ifBlank { "详细描述这张图片的内容;若有文字请原样转录。" }
            ?: "详细描述这张图片的内容;若有文字请原样转录。"
        if (image.isEmpty()) return@withContext ToolResult.Error("缺少 image")

        val baseUrl = database.settingDao().get("aux_vision_base_url")?.trim().orEmpty()
        val keyEnc = database.settingDao().get("aux_vision_api_key").orEmpty()
        val model = database.settingDao().get("aux_vision_model")?.trim()?.ifBlank { "gpt-4o-mini" } ?: "gpt-4o-mini"
        if (baseUrl.isEmpty() || keyEnc.isEmpty())
            return@withContext ToolResult.Error("未配置视觉副模型(设置 → 视觉委托)")
        val apiKey = try { keystore.decrypt(Base64.decode(keyEnc, Base64.NO_WRAP)) } catch (_: Exception) { keyEnc }

        // 组装 image_url:URL 直传,本地路径转 data URI。
        val imageUrl = if (image.startsWith("http://") || image.startsWith("https://")) image
        else {
            val f = File(image)
            if (!f.exists() || !f.isFile) return@withContext ToolResult.Error("图片文件不存在: $image")
            val bytes = f.readBytes()
            val mime = when (f.extension.lowercase()) {
                "png" -> "image/png"; "webp" -> "image/webp"; "gif" -> "image/gif"; else -> "image/jpeg"
            }
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", question))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageUrl))))
            }))
            put("stream", false)
        }
        return@withContext try {
            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@use ToolResult.Error("视觉副模型 HTTP ${resp.code}: ${respBody.take(200)}")
                val text = JSONObject(respBody).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                if (text.isBlank()) ToolResult.Error("视觉副模型无有效返回")
                else ToolResult.Success(text.trim())
            }
        } catch (e: Exception) {
            ToolResult.Error("describe_image 失败: ${e.message}")
        }
    }
}
