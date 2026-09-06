package com.xincode.app

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.xincode.core.ToolRegistry
import com.xincode.data.AppDatabase
import com.xincode.data.SkillEntity
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件商店的数据层:插件目录 + 安装/卸载管理器。
 *
 * 三种插件形态,状态一律从既有数据源推导(与 MCP/Skills/Git 页面互认):
 * - CONNECTOR:是否已存 GitHub token(git_token_enc,Keystore 加密);
 * - MCP:settings 键 plugin_<id>_url 记录安装地址,且 mcp_servers 表里该 url 已连接;
 * - SKILL:skills 表里对应技能 state == "active"(卸载=归档,重装=复活);
 * - ONLINE:远程目录提供的 OpenAPI 插件,settings 键 plugin_online_<id>=="1",
 *   工具在 syncOnlineTools() 注册进 ToolRegistry(online_<id>__<op>)。
 */
enum class PluginKind(val label: String) {
    CONNECTOR("连接器"),
    MCP("MCP 服务"),
    SKILL("技能包"),
    ONLINE("在线插件")
}

/** 一个可安装的插件。 */
data class PluginDescriptor(
    val id: String,
    val name: String,
    val summary: String,
    val kind: PluginKind,
    val icon: ImageVector,
    /** 品牌官方图标(res/drawable),有官方 logo 的插件优先用它 */
    val brandRes: Int? = null,
    /** 官方在线图标 URL(实时加载,加载失败回退 brandRes/icon) */
    val iconUrl: String? = null,
    /** 安装前是否需要网页授权(点击安装先弹跳转授权弹窗) */
    val requiresAuth: Boolean = false,
    /** MCP 服务默认地址 */
    val defaultUrl: String = "",
    /** MCP 连接是否复用 GitHub token 作 Authorization 头 */
    val needsGitToken: Boolean = false,
    /** SKILL 形态对应的技能名(assets/skills 目录名) */
    val skillName: String = "",
    /** 在线插件对应的远程目录条目(ONLINE 形态非空) */
    val remote: PluginRegistry.RemotePlugin? = null
)

/** 插件详情页展示的一行能力说明。 */
data class PluginCapability(val name: String, val summary: String)

/** 内置(随包)插件清单。 */
object PluginCatalog {

    val all: List<PluginDescriptor> = listOf(
        // ── 连接器 ──
        PluginDescriptor(
            id = "github_connector",
            name = "GitHub 连接器",
            summary = "OAuth 设备流登录 GitHub(免手动建 Token)。Token 加密存本机,远程 MCP 与 git 操作复用同一授权。",
            kind = PluginKind.CONNECTOR,
            icon = Icons.Outlined.Code,
            brandRes = R.drawable.ic_brand_github,
            iconUrl = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png",
            requiresAuth = true
        ),
        // ── MCP 服务 ──
        PluginDescriptor(
            id = "github_mcp",
            name = "GitHub 远程 MCP",
            summary = "官方远程 MCP(免 root/免 node):AI 直接用 GitHub API 管仓库/PR/Issue/文件。未登录时安装会先弹授权。",
            kind = PluginKind.MCP,
            icon = Icons.Outlined.Code,
            brandRes = R.drawable.ic_brand_github,
            iconUrl = "https://github.githubassets.com/images/modules/logos_page/GitHub-Mark.png",
            requiresAuth = true,
            defaultUrl = "https://api.githubcopilot.com/mcp/",
            needsGitToken = true
        ),
        PluginDescriptor(
            id = "microsoft_learn_mcp",
            name = "Microsoft Learn",
            summary = "微软官方文档 MCP(免鉴权):.NET / Azure / Microsoft 365 的一手文档与代码示例即查即用。",
            kind = PluginKind.MCP,
            icon = Icons.Outlined.Storage,
            brandRes = R.drawable.ic_brand_microsoft,
            iconUrl = "https://img-prod-cms-rt-microsoft-com.akamaized.net/cms/api/am/imageFileData/RE1Mu3b",
            defaultUrl = "https://learn.microsoft.com/api/mcp"
        ),
        PluginDescriptor(
            id = "context7_mcp",
            name = "Context7",
            summary = "主流框架/库的实时官方文档直接注入上下文,写代码不再靠模型记忆(免鉴权,限流更宽松可自配 Key)。",
            kind = PluginKind.MCP,
            icon = Icons.Outlined.MenuBook,
            defaultUrl = "https://mcp.context7.com/mcp"
        ),
        PluginDescriptor(
            id = "deepwiki_mcp",
            name = "DeepWiki",
            summary = "公共 MCP,无需授权:查询开源仓库的结构化 Wiki,架构/API/用法一问即答。",
            kind = PluginKind.MCP,
            icon = Icons.Outlined.Storage,
            defaultUrl = "https://mcp.deepwiki.com/mcp"
        ),
        // ── 技能包(assets/skills) ──
        PluginDescriptor(
            id = "skill_security_code_auditor",
            name = "安全代码审计",
            summary = "检查注入、越权、XSS/CSRF/SSRF、命令执行、敏感信息泄露与 Android WebView 安全等风险。",
            kind = PluginKind.SKILL,
            icon = Icons.Outlined.Security,
            skillName = "security-code-auditor"
        ),
        PluginDescriptor(
            id = "skill_ghidra_analysis",
            name = "逆向分析",
            summary = "二进制/APK/SO/ELF/DEX 静态分析:jadx + apktool + Ghidra headless 分层逆向。",
            kind = PluginKind.SKILL,
            icon = Icons.Outlined.Terminal,
            skillName = "ghidra-analysis"
        ),
        PluginDescriptor(
            id = "skill_android_apk_builder",
            name = "APK 构建",
            summary = "终端里为 Android/Gradle 项目编译 Debug/Release APK 与 AAB,自动识别 SDK 与产物路径。",
            kind = PluginKind.SKILL,
            icon = Icons.Outlined.Build,
            skillName = "android-apk-builder"
        ),
        PluginDescriptor(
            id = "skill_apk_update",
            name = "APK 更新管理",
            summary = "检查 APK/LSPosed 模块新版本,从 GitHub Release 下载、安装更新、清理残留。",
            kind = PluginKind.SKILL,
            icon = Icons.Outlined.Sync,
            skillName = "apk-update-skill"
        ),
        PluginDescriptor(
            id = "skill_apktool",
            name = "APK 反编译",
            summary = "apktool 解包资源/smali、改完回编译重打包,可改 Manifest/图标/签名配置。",
            kind = PluginKind.SKILL,
            icon = Icons.Outlined.Code,
            skillName = "apktool-tool"
        ),
        PluginDescriptor(
            id = "skill_lsposed_mod_dev",
            name = "LSPosed 模块开发",
            summary = "Xposed/LSPosed 模块开发与排障:hook 失效分析、旧模块迁移(仅限合法授权用途)。",
            kind = PluginKind.SKILL,
            icon = Icons.Outlined.Memory,
            skillName = "LSPosed-Mod-Dev"
        )
    )

    fun byId(id: String): PluginDescriptor? = all.firstOrNull { it.id == id }
}

/** 在线插件描述符:由远程目录条目转换而来。 */
fun PluginRegistry.RemotePlugin.toDescriptor(): PluginDescriptor = PluginDescriptor(
    id = "online_$id",
    name = name,
    summary = description,
    kind = PluginKind.ONLINE,
    icon = Icons.Outlined.Public,
    iconUrl = icon,
    requiresAuth = authType == "api_key",
    defaultUrl = baseUrl,
    remote = this
)

/** 插件安装/卸载的编排层:复用 McpManager / SkillDao / ToolRegistry / GithubAuth。 */
class PluginStoreManager(
    private val context: Context,
    private val database: AppDatabase,
    private val keystore: KeystoreProvider,
    private val mcpManager: McpManager,
    private val toolRegistry: ToolRegistry
) {
    companion object {
        private fun mcpKey(id: String) = "plugin_${id}_url"
        private fun onlineKey(id: String) = "plugin_online_$id"
        private fun onlineKeyKey(id: String) = "plugin_online_${id}_key"
        private const val ONLINE_TOOLS_KEY = "plugin_online_toolnames"
        private const val GIT_TOKEN_KEY = "git_token_enc" // 沿用历史 key,老版本登录过的 token 直接复用
    }

    /** 远程目录(内存态):每次进插件市场/启动时刷新。 */
    @Volatile
    var remotePlugins: List<PluginRegistry.RemotePlugin> = emptyList()
        private set

    @Volatile
    var remoteError: String? = null
        private set

    /** 拉取远程插件目录(失败回退缓存),返回 ONLINE 描述符列表。remoteError 反映加载状态。 */
    suspend fun refreshRemoteCatalog(): List<PluginDescriptor> = withContext(Dispatchers.IO) {
        val (raw, ok) = PluginRegistry.fetch(database)
        remoteRaw = raw
        remoteError = if (ok) null else "远程目录加载失败,已使用上次缓存"
        raw.map { it.toDescriptor() }
    }

    /** 启动同步:刷新远程目录 + 重建在线插件工具注册。 */
    suspend fun syncAll() {
        refreshRemoteCatalog()
        syncOnlineTools()
    }

    /** 各插件当前安装状态(从真实数据源推导,供页面渲染)。 */
    suspend fun installedStates(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val token = gitToken()
        val servers = database.mcpServerDao().getAll()
        val skills = database.skillDao().getAll().associate { it.name to it.state }
        val bundled = PluginCatalog.all.associate { p ->
            p.id to when (p.kind) {
                PluginKind.CONNECTOR -> token.isNotBlank()
                PluginKind.MCP -> {
                    val url = database.settingDao().get(mcpKey(p.id))
                        ?.takeIf { it.isNotBlank() } ?: p.defaultUrl
                    // 必须处于 connected:断开后的残留条目不算已安装(工具已注销,AI 用不了)
                    url.isNotBlank() && servers.any { it.url == url && it.connected }
                }
                PluginKind.SKILL -> (skills[p.skillName] ?: "archived") == "active"
                PluginKind.ONLINE -> false
            }
        }
        val online = remotePlugins.mapNotNull { rp ->
            database.settingDao().get(onlineKey(rp.id))?.let { "online_${rp.id}" to (it == "1") }
        }.toMap()
        bundled + online
    }

    /** 当前 GitHub token(解密;与 Git 接入共用,未登录返回空串)。 */
    suspend fun gitToken(): String = withContext(Dispatchers.IO) {
        val enc = database.settingDao().get(GIT_TOKEN_KEY)
        if (enc.isNullOrBlank()) "" else try {
            keystore.decrypt(android.util.Base64.decode(enc, android.util.Base64.NO_WRAP))
        } catch (_: Exception) { "" }
    }

    /** 保存 OAuth 设备流拿到的 token(Keystore 加密后落库)。 */
    suspend fun saveGitToken(token: String) = withContext(Dispatchers.IO) {
        val enc = android.util.Base64.encodeToString(keystore.encrypt(token.trim()), android.util.Base64.NO_WRAP)
        database.settingDao().put(GIT_TOKEN_KEY, enc)
    }

    /** 卸载连接器 = 清除 token(置空视作未登录)。 */
    suspend fun clearGitToken() = withContext(Dispatchers.IO) {
        database.settingDao().put(GIT_TOKEN_KEY, "")
    }

    /** 安装 MCP 插件:连接并注册工具,成功后记录安装地址。失败不落任何状态。 */
    suspend fun installMcp(p: PluginDescriptor, url: String, authHeader: String): McpConnectResult {
        val result = mcpManager.connectServer(p.name, url.trim(), authHeader.trim())
        if (result is McpConnectResult.Success) {
            database.settingDao().put(mcpKey(p.id), url.trim())
        }
        return result
    }

    /** 卸载 MCP 插件:断开并删除服务器条目(含其工具注册)。 */
    suspend fun uninstallMcp(p: PluginDescriptor) = withContext(Dispatchers.IO) {
        val url = database.settingDao().get(mcpKey(p.id))?.takeIf { it.isNotBlank() } ?: p.defaultUrl
        if (url.isNotBlank()) mcpManager.deleteServer(url)
        database.settingDao().put(mcpKey(p.id), "")
    }

    /** 安装技能包:从 assets 重导入并置 active(用户可能之前在 Skills 页删过)。 */
    suspend fun installSkill(p: PluginDescriptor): Boolean = withContext(Dispatchers.IO) {
        try {
            val text = context.assets.open("skills/${p.skillName}/SKILL.md")
                .bufferedReader().use { it.readText() }
            val (name, desc, content) = SkillImporter.parse(text, p.skillName)
            val existing = database.skillDao().getByName(name)
            val now = System.currentTimeMillis()
            database.skillDao().upsert(
                SkillEntity(
                    id = existing?.id ?: 0,
                    name = name,
                    description = desc,
                    content = content,
                    source = "bundled",
                    state = "active",
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 卸载技能包:归档(数据保留,重装即恢复;agent 提示词不再注入)。 */
    suspend fun uninstallSkill(p: PluginDescriptor) = withContext(Dispatchers.IO) {
        val skill = database.skillDao().getByName(p.skillName) ?: return@withContext
        database.skillDao().setState(skill.id, "archived", System.currentTimeMillis())
    }

    // ---------- 在线插件(远程 OpenAPI) ----------

    /** 安装在线插件:记录状态;api_key 型必须提供 Key(Keystore 加密落库)。 */
    suspend fun installOnline(p: PluginDescriptor, apiKey: String): McpConnectResult = withContext(Dispatchers.IO) {
        val raw = p.remote ?: remotePlugins.firstOrNull { "online_${it.id}" == p.id }
            ?: return@withContext McpConnectResult.Error("远程目录中找不到该插件,请下拉刷新后重试")
        if (raw.authType == "api_key" && apiKey.isBlank()) {
            return@withContext McpConnectResult.Error("该插件需要填写 API Key")
        }
        if (apiKey.isNotBlank()) {
            val enc = android.util.Base64.encodeToString(keystore.encrypt(apiKey.trim()), android.util.Base64.NO_WRAP)
            database.settingDao().put(onlineKeyKey(raw.id), enc)
        }
        database.settingDao().put(onlineKey(raw.id), "1")
        syncOnlineTools()
        McpConnectResult.Success(raw.name, raw.tools.size, raw.tools.map { it.name })
    }

    /** 卸载在线插件:清状态并注销其全部工具。 */
    suspend fun uninstallOnline(p: PluginDescriptor) = withContext(Dispatchers.IO) {
        val rawId = p.removePrefix("online_")
        database.settingDao().put(onlineKey(rawId), "")
        database.settingDao().put(onlineKeyKey(rawId), "")
        syncOnlineTools()
    }

    /**
     * 在线插件工具同步:先注销上一批(工具名清单持久化在 settings),
     * 再把当前已装在线插件的每个操作注册为 online_<id>__<op> 工具。
     */
    suspend fun syncOnlineTools() = withContext(Dispatchers.IO) {
        val previous = runCatching {
            val arr = JSONArray(database.settingDao().get(ONLINE_TOOLS_KEY) ?: "[]")
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrDefault(emptyList())

        val installed = remotePlugins.filter {
            database.settingDao().get(onlineKey(it.id)) == "1"
        }

        val newNames = mutableListOf<String>()
        for (raw in installed) {
            val keyProvider = {
                val enc = runCatching {
                    database.settingDao().get(onlineKeyKey(raw.id))
                }.getOrNull()
                if (enc.isNullOrBlank()) null else try {
                    keystore.decrypt(android.util.Base64.decode(enc, android.util.Base64.NO_WRAP))
                } catch (_: Exception) { null }
            }
            for (t in raw.tools) {
                val tool = OnlineApiTool(
                    pluginId = raw.id,
                    pluginName = raw.name,
                    spec = t,
                    baseUrl = raw.baseUrl,
                    authHeader = raw.authHeader.ifBlank { null },
                    keyProvider = keyProvider
                )
                toolRegistry.register(tool)
                newNames.add(tool.name)
            }
        }

        // 注销上一批里已不在新清单中的工具(同名的会被 register 直接覆盖,无害)
        previous.filter { it !in newNames }.forEach { toolRegistry.unregister(it) }
        database.settingDao().put(ONLINE_TOOLS_KEY, JSONArray(newNames).toString())
        toolRegistry.invalidateAvailability()
    }

    /** 插件详情:它装好后给 Agent 带来哪些能力(返回中文词条键,由 UI 层 t() 翻译)。 */
    suspend fun capabilitiesFor(p: PluginDescriptor): List<PluginCapability> = withContext(Dispatchers.IO) {
        when (p.kind) {
            PluginKind.MCP -> {
                val url = database.settingDao().get(mcpKey(p.id))?.takeIf { it.isNotBlank() } ?: p.defaultUrl
                val names = url.takeIf { it.isNotBlank() }
                    ?.let { database.mcpServerDao().getByUrl(it)?.toolNames }
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?: emptyList()
                if (names.isEmpty()) {
                    listOf(PluginCapability("安装后可见", "连接成功后,该服务器提供的全部工具会列在这里并注入 Agent。"))
                } else {
                    names.map { PluginCapability(it, "MCP 工具 · 由该服务器远程提供") }
                }
            }
            PluginKind.ONLINE -> {
                val raw = p.remote ?: remotePlugins.firstOrNull { "online_${it.id}" == p.id }
                raw?.tools?.map { t ->
                    val params = t.params.joinToString("、") { it.name }
                    PluginCapability(
                        t.name,
                        buildString {
                            append(t.summary)
                            if (params.isNotBlank()) append("(参数:").append(params).append(")")
                        }
                    )
                } ?: emptyList()
            }
            PluginKind.SKILL -> listOf(PluginCapability("技能指令包", "安装后 Agent 可通过 invoke_skill 调用这套方法"))
            PluginKind.CONNECTOR -> listOf(PluginCapability("账号授权", "授权后,Git 相关插件与 git 操作复用这份登录凭证"))
        }
    }
}
