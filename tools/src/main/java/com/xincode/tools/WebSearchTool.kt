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
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 联网搜索工具。
 *
 * 默认走【无需 API Key】的搜索引擎,依次兜底:必应(cn.bing.com)→ 百度 → DuckDuckGo。
 * 之所以把必应/百度放前面:DuckDuckGo 在国内常被阻断,若作首选会每次失败→模型反复重试(曾出现
 * 一次搜索烧掉 40 次工具调用)。必应/百度国内可直连,基本一次成功。
 * 若用户在设置里配了 Tavily key,则优先用 Tavily(质量更好)。本工具【始终可用】(受联网总开关控制),
 * 不会因没配 key 就不暴露给模型,从而避免模型退化成瞎猜 URL 反复 web_fetch。
 */
class WebSearchTool : Tool {

    companion object {
        private const val TAG = "WebSearchTool"
        private const val TAVILY_ENDPOINT = "https://api.tavily.com/search"
        private const val DDG_ENDPOINT = "https://html.duckduckgo.com/html/"
        private const val DEFAULT_MAX_RESULTS = 5
        private const val MAX_OUTPUT = 4000
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    }

    /** 一条搜索结果。 */
    private data class Hit(val title: String, val url: String, val snippet: String)

    /** 可选 Tavily API key — 由 XincodeApplication 从 Room 设置注入;为空则用免费引擎。 */
    var apiKey: String = ""

    override val name = "web_search"
    override val description = "搜索互联网,返回相关网页标题、链接和摘要。开箱即用(默认必应/百度,国内可直连,无需配置)。"

    // 始终可用(有 DuckDuckGo 兜底),但受「联网搜索」总开关控制。
    override fun isAvailable(): Boolean = WebSearchGate.enabled

    override fun unavailableReason(): String = WebSearchGate.OFF_REASON

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "搜索关键词")
            })
            put("maxResults", JSONObject().apply {
                put("type", "integer")
                put("description", "返回结果数量(默认5,最大10)")
            })
        })
        put("required", JSONArray().apply { put("query") })
    }

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .cache(HttpCacheProvider.get())
            .build()
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val query = params["query"]?.trim().orEmpty()
        if (query.isEmpty()) return@withContext ToolResult.Error("缺少 query 参数")
        val maxResults = params["maxResults"]?.toIntOrNull()?.coerceIn(1, 10) ?: DEFAULT_MAX_RESULTS

        // 有 Tavily key → 优先 Tavily;【仅成功才用】,失败(含 Error 结果/异常)一律回退免费引擎。
        if (apiKey.isNotBlank()) {
            val tv = runCatching { tavily(query, maxResults) }.getOrNull()
            if (tv is ToolResult.Success) return@withContext tv
            Log.w(TAG, "Tavily failed/errored, fallback to free engines")
        }

        // 融合 various_search 的多引擎聚合思路:必应(国内直连,首选)→ 百度 → 搜狗 → DuckDuckGo(常被墙,末位)。
        // 【跨引擎聚合去重】:逐个引擎取结果,按标题去重合并,凑够 maxResults 就停 —— 既比单引擎稳
        // (一个引擎抽风还有别的补上),又不会全跑一遍浪费时间;全空才返回未找到,避免模型反复重试烧调用。
        val engines = listOf<Pair<String, (String, Int) -> List<Hit>>>(
            "必应" to ::bing,
            "百度" to ::baidu,
            "搜狗" to ::sogou,
            "DuckDuckGo" to ::duckduckgo
        )
        val merged = LinkedHashMap<String, Hit>()
        val used = mutableListOf<String>()
        var lastErr: String? = null
        for ((label, fn) in engines) {
            if (merged.size >= maxResults) break
            val hits = runCatching { fn(query, maxResults) }.getOrElse {
                lastErr = it.message; Log.w(TAG, "$label 搜索失败: ${it.message}"); emptyList()
            }
            if (hits.isNotEmpty()) used.add(label)
            for (h in hits) {
                val key = h.title.trim().lowercase().take(48).ifBlank { h.url }
                if (!merged.containsKey(key)) merged[key] = h
                if (merged.size >= maxResults) break
            }
        }
        if (merged.isEmpty()) {
            return@withContext ToolResult.Success(
                "未找到关于「$query」的结果(必应/百度/搜狗/DuckDuckGo 均无结果或暂时不可用${lastErr?.let { ":$it" } ?: ""})。可换关键词或稍后重试。"
            )
        }
        ToolResult.Success(format(query, used.joinToString("+"), merged.values.toList()))
    }

    /** 统一格式化输出。 */
    private fun format(query: String, engine: String, hits: List<Hit>): String {
        val body = hits.mapIndexed { i, h ->
            "[${i + 1}] ${h.title}\n    URL: ${h.url}\n    摘要: ${h.snippet.take(220)}"
        }.joinToString("\n\n")
        return "搜索结果「$query」($engine 聚合):\n\n$body".take(MAX_OUTPUT)
    }

    private fun fetchHtml(url: String): String {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    // ---- 必应 cn.bing.com(无 key,国内直连)----
    private fun bing(query: String, maxResults: Int): List<Hit> {
        val url = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8") + "&FORM=QBLH"
        val doc = Jsoup.parse(fetchHtml(url), url)
        val out = mutableListOf<Hit>()
        for (li in doc.select("li.b_algo")) {
            val a = li.selectFirst("h2 a") ?: li.selectFirst("a") ?: continue
            val href = a.attr("href").trim()
            val title = a.text().trim()
            if (title.isBlank() || !href.startsWith("http")) continue
            val snippet = (li.selectFirst("div.b_caption p") ?: li.selectFirst("p"))?.text()?.trim().orEmpty()
            out.add(Hit(title, href, snippet))
            if (out.size >= maxResults) break
        }
        return out
    }

    // ---- 百度 www.baidu.com(无 key,国内直连)。href 为百度跳转链接,web_fetch 会自动跟随。----
    private fun baidu(query: String, maxResults: Int): List<Hit> {
        val url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.parse(fetchHtml(url), url)
        val out = mutableListOf<Hit>()
        for (res in doc.select("div.result, div.c-container[srcid], div.result-op")) {
            val a = res.selectFirst("h3 a") ?: res.selectFirst("h3.t a") ?: continue
            val href = a.attr("href").trim()
            val title = a.text().trim()
            if (title.isBlank() || href.isBlank()) continue
            val snippet = (res.selectFirst(".c-abstract")
                ?: res.selectFirst("[class*=abstract]")
                ?: res.selectFirst("[class*=content-right]"))?.text()?.trim().orEmpty()
            out.add(Hit(title, if (href.startsWith("http")) href else "https://www.baidu.com$href", snippet))
            if (out.size >= maxResults) break
        }
        return out
    }

    // ---- 搜狗 www.sogou.com(无 key,国内直连)。href 为搜狗跳转链接(相对),web_fetch 会跟随。----
    private fun sogou(query: String, maxResults: Int): List<Hit> {
        val url = "https://www.sogou.com/web?query=" + URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.parse(fetchHtml(url), url)
        val out = mutableListOf<Hit>()
        for (res in doc.select("div.vrwrap, div.rb, div.results div.vrwrap")) {
            val a = res.selectFirst("h3 a") ?: res.selectFirst("a.title") ?: continue
            val href = a.attr("href").trim()
            val title = a.text().trim()
            if (title.isBlank() || href.isBlank()) continue
            val snippet = (res.selectFirst(".str_info") ?: res.selectFirst(".fz-mid") ?: res.selectFirst("[class*=content]"))?.text()?.trim().orEmpty()
            out.add(Hit(title, if (href.startsWith("http")) href else "https://www.sogou.com$href", snippet))
            if (out.size >= maxResults) break
        }
        return out
    }

    // ---- DuckDuckGo(无 key,国内常被墙,末位兜底)----
    private fun duckduckgo(query: String, maxResults: Int): List<Hit> {
        val body = "q=" + URLEncoder.encode(query, "UTF-8") + "&kl=cn-zh"
        val request = okhttp3.Request.Builder()
            .url(DDG_ENDPOINT)
            .header("User-Agent", UA)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(okhttp3.RequestBody.create("application/x-www-form-urlencoded".toMediaType(), body))
            .build()
        client.newCall(request).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            val doc = Jsoup.parse(html, DDG_ENDPOINT)
            val out = mutableListOf<Hit>()
            for (res in doc.select("div.result, div.web-result")) {
                val a = res.selectFirst("a.result__a") ?: continue
                val title = a.text().trim()
                val href = decodeDdgHref(a.attr("href"))
                if (title.isBlank() || href.isBlank()) continue
                out.add(Hit(title, href, res.selectFirst(".result__snippet")?.text()?.trim().orEmpty()))
                if (out.size >= maxResults) break
            }
            return out
        }
    }

    /** DuckDuckGo 的链接常是 /l/?uddg=<编码真实URL> 的跳转,解出真实 URL。 */
    private fun decodeDdgHref(href: String): String {
        if (href.isBlank()) return ""
        val full = if (href.startsWith("//")) "https:$href" else href
        val marker = "uddg="
        val idx = full.indexOf(marker)
        if (idx < 0) return full
        val enc = full.substring(idx + marker.length).substringBefore('&')
        return try { URLDecoder.decode(enc, "UTF-8") } catch (_: Exception) { full }
    }

    // ---- Tavily(有 key 时优先)----
    private fun tavily(query: String, maxResults: Int): ToolResult {
        val requestBody = JSONObject().apply {
            put("api_key", apiKey)
            put("query", query)
            put("max_results", maxResults)
            put("search_depth", "basic")
        }
        val request = okhttp3.Request.Builder()
            .url(TAVILY_ENDPOINT)
            .header("Content-Type", "application/json")
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), requestBody.toString()))
            .build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            Log.w(TAG, "Tavily API error: ${response.code} $body")
            return ToolResult.Error("Tavily ${response.code}")
        }
        val json = JSONObject(body)
        val resultsArr = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<String>()
        for (i in 0 until resultsArr.length()) {
            val item = resultsArr.getJSONObject(i)
            items.add("[${i + 1}] ${item.optString("title", "")}\n    URL: ${item.optString("url", "")}\n    摘要: ${item.optString("content", "").take(220)}")
        }
        val output = if (items.isEmpty()) "未找到关于「$query」的结果" else "搜索结果「$query」:\n\n${items.joinToString("\n\n")}"
        return ToolResult.Success(output.take(MAX_OUTPUT))
    }
}
