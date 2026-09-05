package com.xincode.app

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import com.xincode.data.AppDatabase
import com.xincode.data.SkillEntity
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 插件商店的数据层:插件目录(随包内置清单) + 安装/卸载管理器。
 *
 * 设计原则:不新增表,状态一律从既有数据源推导,保证与其它页面(MCP 服务器/Skills/Git 接入)互认——
 * - CONNECTOR:是否已存 GitHub token(git_token_enc,Keystore 加密,与 Git 接入共用);
 * - MCP:settings 键 plugin_<id>_url 记录安装地址,且 mcp_servers 表里该 url 存在;
 * - SKILL:skills 表里对应技能 state == "active"(卸载=归档,重装=复活)。
 */
enum class PluginKind(val label: String) {
    CONNECTOR("连接器"),
    MCP("MCP 服务"),
    SKILL("技能包")
}

/** 一个可安装的插件。 */
data class PluginDescriptor(
    val id: String,
    val name: String,
    val summary: String,
    val kind: PluginKind,
    val icon: ImageVector,
    /** 品牌官方图标(res/drawable),有官方 logo 的插件优先用它,不再用通用图标凑数 */
    val brandRes: Int? = null,
    /** 安装前是否需要网页授权(点击安装先弹跳转授权弹窗) */
    val requiresAuth: Boolean = false,
    /** MCP 服务默认地址(空 = 安装时向用户询问,如 Composio) */
    val defaultUrl: String = "",
    /** MCP 连接是否复用 GitHub token 作 Authorization 头 */
    val needsGitToken: Boolean = false,
    /** SKILL 形态对应的技能名(assets/skills 目录名) */
    val skillName: String = "",
    /** MCP 地址需要用户自填时的占位提示 */
    val urlPlaceholder: String = "",
    /** 指引用户去哪拿地址/密钥的控制台地址(弹窗里会给出直达链接) */
    val consoleUrl: String = ""
)

/** 内置插件清单。MCP 地址连接成功才记为已安装;技能以 state 判定;连接器以 token 判定。 */
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
        PluginDescriptor(
            id = "composio_mcp",
            name = "Composio",
            summary = "500+ SaaS 应用(Gmail/Notion/Slack…)由 Composio 托管 OAuth 后接入。装前需在控制台生成属于你账号的 MCP 地址。",
            kind = PluginKind.MCP,
            icon = Icons.Outlined.Extension,
            urlPlaceholder = "https://mcp.composio.dev/…",
            consoleUrl = "https://app.composio.dev"
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

/** 插件安装/卸载的编排层:薄封装,真正能力都复用 McpManager / SkillDao / GithubAuth。 */
class PluginStoreManager(
    private val context: Context,
    private val database: AppDatabase,
    private val keystore: KeystoreProvider,
    private val mcpManager: McpManager
) {
    companion object {
        private fun mcpKey(id: String) = "plugin_${id}_url"
        private const val GIT_TOKEN_KEY = "git_token_enc" // 沿用历史 key,老版本登录过的 token 直接复用
    }

    /** 各插件当前安装状态(从真实数据源推导,供页面渲染)。 */
    suspend fun installedStates(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val token = gitToken()
        val servers = database.mcpServerDao().getAll()
        val skills = database.skillDao().getAll().associate { it.name to it.state }
        PluginCatalog.all.associate { p ->
            p.id to when (p.kind) {
                PluginKind.CONNECTOR -> token.isNotBlank()
                PluginKind.MCP -> {
                    val url = database.settingDao().get(mcpKey(p.id))
                        ?.takeIf { it.isNotBlank() } ?: p.defaultUrl
                    url.isNotBlank() && servers.any { it.url == url }
                }
                PluginKind.SKILL -> (skills[p.skillName] ?: "archived") == "active"
            }
        }
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
}
