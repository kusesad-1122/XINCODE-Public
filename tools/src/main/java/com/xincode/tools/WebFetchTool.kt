package com.xincode.tools

import android.util.Log
import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.provider.HttpCacheProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * Web fetch tool — fetches a URL and extracts readable content as Markdown.
 * Uses Jsoup for HTML parsing and content extraction.
 */
class WebFetchTool : Tool {

    companion object {
        private const val TAG = "WebFetchTool"
        private const val MAX_OUTPUT = 8000
        private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }

    override val name = "web_fetch"
    override val description = "抓取指定 URL 的网页内容，返回 Markdown 化正文。最长 8000 字符，超长会被截断。"

    // 受「联网搜索」总开关控制。
    override fun isAvailable(): Boolean = WebSearchGate.enabled

    override fun unavailableReason(): String = WebSearchGate.OFF_REASON

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("url", JSONObject().apply {
                put("type", "string")
                put("description", "要抓取的网页完整 URL")
            })
        })
        put("required", JSONArray().apply { put("url") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val url = params["url"] ?: return@withContext ToolResult.Error("缺少 url 参数")

        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .cache(HttpCacheProvider.get())
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext ToolResult.Error("HTTP ${response.code}: 无法访问 $url")
            }

            // Parse with Jsoup
            val doc = Jsoup.parse(html, url)

            // 先抽取「结构化线索」:meta 描述 / og / JSON-LD —— 很多 JS 渲染页的关键信息(价格、简介)其实藏在这里。
            val metaBits = buildString {
                val md = doc.select("meta[name=description]").attr("content").trim()
                val ogd = doc.select("meta[property=og:description]").attr("content").trim()
                val ogt = doc.select("meta[property=og:title]").attr("content").trim()
                if (ogt.isNotBlank()) appendLine("og:title: $ogt")
                if (md.isNotBlank()) appendLine("description: $md")
                if (ogd.isNotBlank() && ogd != md) appendLine("og:description: $ogd")
                // JSON-LD 结构化数据(常含价格/产品信息)
                doc.select("script[type=application/ld+json]").forEach { s ->
                    val t = s.data().trim()
                    if (t.isNotBlank()) appendLine("json-ld: ${t.take(1500)}")
                }
            }.trim()

            // Remove clutter(在结构化抽取之后再删)
            doc.select("script, style, nav, footer, header, iframe, noscript, .sidebar, .nav, .footer, .header, .menu, .ad, .advertisement").remove()

            val title = doc.title()

            // 正文:标题层级 + 段落/列表 + 【表格】(价格常在表格里)。
            val bodyMd = buildString {
                doc.select("h1, h2, h3, h4, h5, h6, p, li, pre, blockquote, table").forEach { el ->
                    when (el.tagName()) {
                        "h1", "h2", "h3", "h4", "h5", "h6" -> {
                            val prefix = "#".repeat(el.tagName().substring(1).toIntOrNull() ?: 1)
                            val t = el.text().trim(); if (t.isNotBlank()) appendLine("$prefix $t")
                        }
                        "pre" -> { val t = el.text(); if (t.isNotBlank()) appendLine("```\n$t\n```") }
                        "blockquote" -> { val t = el.text().trim(); if (t.isNotBlank()) appendLine("> $t") }
                        "table" -> {
                            // 表格转成 | a | b | 行,保留价格这类表格数据。
                            el.select("tr").forEach { tr ->
                                val cells = tr.select("th, td").map { it.text().trim() }.filter { true }
                                if (cells.any { it.isNotBlank() }) appendLine("| " + cells.joinToString(" | ") + " |")
                            }
                        }
                        else -> { val t = el.text().trim(); if (t.isNotBlank()) appendLine(t) }
                    }
                }
            }.trim()

            // 判定「正文过薄」——多半是需要 JS 渲染的页面。给模型明确指引,避免反复抓同一个死页。
            val looksThin = bodyMd.length < 200 && metaBits.length < 120

            val output = buildString {
                appendLine("# $title"); appendLine("URL: $url"); appendLine()
                if (metaBits.isNotBlank()) { appendLine("## 结构化信息"); appendLine(metaBits); appendLine() }
                if (bodyMd.isNotBlank()) { appendLine("## 正文"); appendLine(bodyMd) }
                if (looksThin) {
                    appendLine()
                    appendLine("[提示] 此页正文很少,大概率是需要 JavaScript 动态渲染的页面(抓取器拿不到)。")
                    appendLine("不要反复抓这个 URL;改用 web_search 换关键词、或换官方文档/第三方聚合页/API 定价说明页再试。")
                }
            }

            val trimmed = if (output.length > MAX_OUTPUT) {
                output.take(MAX_OUTPUT) + "\n\n[...已截断，原内容约 ${output.length} 字符]"
            } else output

            ToolResult.Success(trimmed)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed: ${e.message}", e)
            ToolResult.Error("抓取失败 $url: ${e.message}")
        }
    }
}