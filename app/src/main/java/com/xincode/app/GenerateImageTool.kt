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
            "model=生图模型名(可选,不填用已配置的图像模型或当前供应商默认);" +
            "size=尺寸(可选,默认 1024x1024)。" +
            "成功后返回一个「### 文件名(图片,路径:…)」标记——你必须把该标记原样贴进给用户的回复里," +
            "图片才会直接显示出来,不要只用文字描述代替。"

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

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val prompt = params["prompt"]?.trim().orEmpty()
        if (prompt.isEmpty()) return@withContext ToolResult.Error("缺少 prompt")
        val size = params["size"]?.trim()?.ifBlank { "1024x1024" } ?: "1024x1024"
        try {
            // 1) 解析端点:自定义委托 > 功能模型配置 > 活跃配置
            val aux = runCatching { AuxModels.resolve(database, keystore, "image") }.getOrNull()
            val baseUrl: String
            val apiKey: String
            var model = params["model"]?.trim().orEmpty()
            val apiPathType: String
            if (aux != null && aux.baseUrl.isNotBlank() && aux.apiKey.isNotBlank()) {
                baseUrl = aux.baseUrl; apiKey = aux.apiKey; apiPathType = aux.apiPathType
                if (model.isBlank()) model = aux.model
            } else {
                val cfg = database.providerConfigDao().getActive()
                    ?: return@withContext ToolResult.Error("没有可用的供应商配置,请先在「模型与供应商」里添加(设置 → 功能模型配置 → 图像生成 也可单独指定)")
                if (cfg.baseUrl.isBlank() || cfg.apiKeyEnc.isBlank())
                    return@withContext ToolResult.Error("活跃供应商配置缺少 base_url 或 key")
                baseUrl = cfg.baseUrl
                apiPathType = cfg.apiPathType
                apiKey = try {
                    keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
                } catch (e: Exception) {
                    return@withContext ToolResult.Error("供应商 key 解密失败,请重新填写保存")
                }
                if (model.isBlank()) model = cfg.model
            }
            if (model.isBlank())
                return@withContext ToolResult.Error("未指定生图模型:传 model 参数,或在功能模型配置里给「图像生成」指定模型")

            // 2) 调 /images/generations(OpenAI 兼容;custom=完整 URL 原样用)
            val endpoint = if (apiPathType == "custom") {
                baseUrl.trim().trimEnd('/')
            } else {
                val base = baseUrl.trim().trimEnd('/')
                val versioned = Regex("/v\\d[^/]*").containsMatchIn(base)
                base + if (versioned) "/images/generations" else "/v1/images/generations"
            }
            val body = JSONObject()
                .put("model", model)
                .put("prompt", prompt)
                .put("size", size)
                .put("response_format", "b64_json")
                .toString()
            val req = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody(JSON))
                .build()
            val resp = http.newCall(req).execute()
            val respText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val hint = if (resp.code == 404)
                    "。该端点不支持 /images/generations(原生 Anthropic/Gemini 官方端点没有此接口),请换 OpenAI 兼容/中转站,或在功能模型配置里给「图像生成」单独指定兼容端点"
                else ""
                return@withContext ToolResult.Error("生图请求失败 HTTP ${resp.code}: ${respText.take(300)}$hint")
            }
            // 3) 取图:b64 优先,url 兜底下载。字节原样落盘,不做任何压缩。
            val data0 = JSONObject(respText).optJSONArray("data")?.optJSONObject(0)
                ?: return@withContext ToolResult.Error("生图返回无 data")
            val raw: ByteArray = when {
                !data0.optString("b64_json").isNullOrBlank() ->
                    Base64.decode(data0.getString("b64_json"), Base64.DEFAULT)
                !data0.optString("url").isNullOrBlank() -> {
                    val imgReq = Request.Builder().url(data0.getString("url")).get().build()
                    val imgResp = http.newCall(imgReq).execute()
                    if (!imgResp.isSuccessful || imgResp.body == null)
                        return@withContext ToolResult.Error("图片下载失败 HTTP ${imgResp.code}")
                    imgResp.body!!.bytes()
                }
                else -> return@withContext ToolResult.Error("生图返回既无 b64 也无 url")
            }
            if (raw.isEmpty()) return@withContext ToolResult.Error("生图返回为空")

            val dir = File(filesDir, "generated_images").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "IMG_${stamp}_${(1000..9999).random()}.png")
            file.writeBytes(raw)   // 原样写入:不压缩、不转码、不降质

            val marker = "### ${file.name}(图片,路径:${file.absolutePath})"
            ToolResult.Success(
                "图片已生成并原图保存: ${file.absolutePath} (${raw.size / 1024}KB,无压缩)。\n" +
                    "请把下面这行标记【原样】贴进你给用户的回复里,图片才会直接显示:\n$marker"
            )
        } catch (e: Exception) {
            Log.w("GenerateImage", "failed: ${e.message}")
            ToolResult.Error("生图异常: ${e.message}")
        }
    }
}
