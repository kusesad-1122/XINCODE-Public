package com.xincode.app

import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 远程插件目录:插件市场数据托管在仓库 docs/plugins/registry.json(raw.githubusercontent),
 * 新增/下架插件只需改这份 JSON——应用无需发版,重新打开插件市场即可刷出。
 *
 * 每个条目 = 在线清单:远程服务 base_url + 工具列表(method/path/params),
 * 语义为 OpenAPI 的轻量子集(GET query 参数 + POST JSON body)。
 * 拉取失败时回退到 settings 里的 last-good 缓存,保证离线也能看到/管理已装插件。
 */
object PluginRegistry {

    const val REGISTRY_URL =
        "https://raw.githubusercontent.com/kusesad-1122/XINCODE-Public/main/docs/plugins/registry.json"
    private const val CACHE_KEY = "plugin_registry_cache"

    data class RemoteParam(
        val name: String,
        val description: String,
        val required: Boolean,
        val type: String,
        val body: Boolean,
        val default: String?
    )

    data class RemoteTool(
        val name: String,
        val summary: String,
        val method: String,
        val path: String,
        val params: List<RemoteParam>
    )

    data class RemotePlugin(
        val id: String,
        val name: String,
        val description: String,
        val icon: String,
        val authType: String,      // none / api_key
        val authHeader: String,
        val baseUrl: String,
        val tools: List<RemoteTool>,
        /** 注册表 category(气象预报/金融财经/…);老条目缺省为空,UI 按“未分类”归组。 */
        val category: String = "",
        /**
         * 市场统计(下载/星/收藏/发布时间戳),注册表可选携带,缺省 0 = 未知。
         * 公共源(APIs.guru 等)不提供这些数,不编造;后端/精选源给了,UI 自动出现对应排序。
         */
        val downloads: Long = 0L,
        val stars: Long = 0L,
        val favorites: Long = 0L,
        val publishedAt: Long = 0L
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 拉取远程目录;失败时回退 settings 里的 last-good 缓存。 */
    suspend fun fetch(db: AppDatabase): Pair<List<RemotePlugin>, Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(REGISTRY_URL)
                    .header("Accept", "application/json").build()
                val text = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
                val plugins = parse(text)
                if (plugins.isEmpty()) throw RuntimeException("远程目录为空")
                db.settingDao().put(CACHE_KEY, text)
                plugins to true
            } catch (e: Exception) {
                val cached = db.settingDao().get(CACHE_KEY)
                if (!cached.isNullOrBlank()) {
                    runCatching { parse(cached) }.getOrDefault(emptyList()) to false
                } else {
                    emptyList<RemotePlugin>() to false
                }
            }
        }

    /** 解析目录 JSON;单条目非法跳过,不影响其它插件。 */
    fun parse(text: String): List<RemotePlugin> {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("plugins") ?: return emptyList()
        val out = mutableListOf<RemotePlugin>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val id = p.optString("id").trim()
            val name = p.optString("name").trim()
            val baseUrl = p.optString("base_url").trim()
            if (id.isBlank() || name.isBlank() || baseUrl.isBlank()) continue
            val toolsJson = p.optJSONArray("tools") ?: continue
            val tools = mutableListOf<RemoteTool>()
            for (t in 0 until toolsJson.length()) {
                val tj = toolsJson.optJSONObject(t) ?: continue
                val tName = tj.optString("name").trim()
                if (tName.isBlank()) continue
                val params = mutableListOf<RemoteParam>()
                val pj = tj.optJSONArray("params")
                if (pj != null) {
                    for (k in 0 until pj.length()) {
                        val o = pj.optJSONObject(k) ?: continue
                        val pn = o.optString("name").trim()
                        if (pn.isBlank()) continue
                        params.add(
                            RemoteParam(
                                name = pn,
                                description = o.optString("description"),
                                required = o.optBoolean("required", false),
                                type = o.optString("type", "string").ifBlank { "string" },
                                body = o.optBoolean("body", false),
                                default = o.optString("default").ifBlank { null }
                            )
                        )
                    }
                }
                tools.add(
                    RemoteTool(
                        name = tName,
                        summary = tj.optString("summary"),
                        method = tj.optString("method", "GET").uppercase(),
                        path = tj.optString("path", "/"),
                        params = params
                    )
                )
            }
            if (tools.isEmpty()) continue
            out.add(
                RemotePlugin(
                    id = id,
                    name = name,
                    description = p.optString("description"),
                    icon = p.optString("icon"),
                    authType = p.optString("auth_type", "none").ifBlank { "none" },
                    authHeader = p.optString("auth_header"),
                    baseUrl = baseUrl,
                    tools = tools,
                    category = p.optString("category"),
                    downloads = p.optLong("downloads"),
                    stars = p.optString("stars").toLongOrNull() ?: p.optLong("stars"),
                    favorites = p.optLong("favorites"),
                    publishedAt = p.optLong("published_at")
                )
            )
        }
        return out
    }
}
