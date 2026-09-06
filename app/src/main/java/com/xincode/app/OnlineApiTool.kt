package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
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
 * 在线插件的单个工具:把远程 OpenAPI 操作转成 Agent 可调用的 Tool,
 * 执行时直接向插件云端发起标准 HTTP 请求(GET query / POST JSON body)。
 *
 * - 出站前经 [NetGuard] 校验,拒绝指向内网/环回的地址(SSRF 防护);
 * - 鉴权头在请求时注入,绝不进入模型上下文;
 * - 响应文本超长截断,防止撑爆上下文窗口。
 */
class OnlineApiTool(
    private val pluginId: String,
    private val pluginName: String,
    private val spec: PluginRegistry.RemoteTool,
    private val baseUrl: String,
    private val authHeader: String?,
    /** 返回解密后的 Key;未配置返回 null。由 PluginStoreManager 提供闭包。 */
    private val keyProvider: () -> String?
) : Tool {

    override val name: String =
        "online_${pluginId.replace(Regex("[^a-zA-Z0-9_]", "__"), "_")}__${spec.name}"

    override val description: String =
        "[${pluginName}在线插件] ${spec.summary.ifBlank { spec.name }}"

    override val parametersSchema: JSONObject = buildSchema()

    private fun buildSchema(): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        for (p in spec.params) {
            val prop = JSONObject()
            prop.put("type", p.type)
            if (p.description.isNotBlank()) prop.put("description", p.description)
            properties.put(p.name, prop)
            if (p.required) required.put(p.name)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    override suspend fun execute(params: Map<String, String>): ToolResult =
        executeJson(JSONObject().apply { params.forEach { (k, v) -> put(k, v) } })

    override suspend fun executeJson(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            // path 参数 {x} 从入参取值替换,替换掉的键不再进 body/query(注释与代码一致)。
            // 注意:先替换再做 NetGuard 校验 —— 校验的必须是最终 URL,否则替换值绕过守卫。
            var urlPath = spec.path
            val substituted = mutableSetOf<String>()
            Regex("\\{([a-zA-Z0-9_]+)\\}").findAll(urlPath).forEach { m ->
                val key = m.groupValues[1]
                val v = args.optString(key)
                if (v.isNotBlank()) {
                    urlPath = urlPath.replace(m.value, v)
                    substituted.add(key)
                }
            }
            // effectiveArgs:入参减去已进路径的键。putOpt 单重载,绕开 JSONObject.get 的
            // Java 重载歧义;同时跳过 null,blank 值保留(由服务端判)。
            val effectiveArgs = JSONObject()
            args.keys().forEach { k ->
                if (k !in substituted) effectiveArgs.putOpt(k, args.opt(k))
            }

            NetGuard.validate(baseUrl.trimEnd('/') + urlPath)

            val url = baseUrl.trimEnd('/') + urlPath
            val key = keyProvider()

            val request = when (spec.method) {
                "POST", "PUT", "PATCH" -> {
                    val body = JSONObject()
                    val keys = effectiveArgs.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (!urlPath.contains("{$k}")) body.putOpt(k, effectiveArgs.opt(k))
                    }
                    Request.Builder().url(url)
                        .applyAuth(authHeader, key)
                        .post(body.toString().toRequestBody(JSON))
                        .build()
                }
                else -> {
                    val sb = StringBuilder(url)
                    var first = !url.contains("?")
                    val keys = effectiveArgs.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (effectiveArgs.isNull(k) || urlPath.contains("{$k}")) continue
                        sb.append(if (first) "?" else "&").append(k).append("=")
                            .append(java.net.URLEncoder.encode(effectiveArgs.optString(k), "UTF-8"))
                        first = false
                    }
                    Request.Builder().url(sb.toString())
                        .applyAuth(authHeader, key)
                        .get()
                        .build()
                }
            }

            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 401 || resp.code == 403 ->
                        ToolResult.Error("插件鉴权失败(${resp.code}),请到插件市场重新填写有效的 API Key。")
                    resp.code == 429 ->
                        ToolResult.Error("插件接口限流(429),请稍后再试。")
                    !resp.isSuccessful ->
                        ToolResult.Error("远程接口响应错误 HTTP ${resp.code}: ${text.take(300)}")
                    else -> ToolResult.Success(text.take(4000))
                }
            }
        } catch (e: IllegalArgumentException) {
            ToolResult.Error("出站请求被安全策略拒绝:${e.message}")
        } catch (e: Exception) {
            ToolResult.Error("无法连通插件服务器:${e.message?.take(200)}")
        }
    }

    private fun okhttp3.Request.Builder.applyAuth(header: String?, key: String?): okhttp3.Request.Builder {
        if (!header.isNullOrBlank() && !key.isNullOrBlank()) header(header, key.trim())
        return this
    }
}
