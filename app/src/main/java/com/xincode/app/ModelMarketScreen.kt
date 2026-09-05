package com.xincode.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.AppDatabase
import com.xincode.data.ProviderConfigEntity
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

/**
 * 供应商预置,分三区展示:
 *  - [free] = true            → 「免费 / 免费额度」(自己注册领 key,有每日额度或限速)
 *  - [plan] = true            → 「订阅套餐(Plan)」(按月订阅、非按量计费,如各家 coding plan / 代币计划)
 *  - 两者皆 false             → 「按量付费」
 */
data class ProviderPreset(
    val name: String,
    val supplierId: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: List<String>,
    val site: String,          // 官网/注册页
    val note: String,
    val free: Boolean,
    val apiPathType: String = "openai",
    val contextWindow: Int = 0,
    val plan: Boolean = false,  // 订阅套餐类(coding plan / 代币计划等)
    /** true = 走 OAuth 设备码登录拿 token,不需要用户手填 API Key。 */
    val oauth: Boolean = false
)

object ProviderPresets {
    val ALL: List<ProviderPreset> = listOf(
        // —— 免费 / 免费额度(需自己注册领 key,每日有额度或限速)——
        ProviderPreset("智谱 GLM(GLM-4-Flash 免费)", "zhipu", "https://open.bigmodel.cn/api/paas/v4",
            "glm-4-flash", listOf("glm-4-flash", "glm-4-flashx", "glm-4.6"),
            "https://open.bigmodel.cn/", "GLM-4-Flash 长期免费,有速率限制;注册即用", free = true, contextWindow = 128000),
        ProviderPreset("硅基流动 SiliconFlow", "siliconflow", "https://api.siliconflow.cn/v1",
            "Qwen/Qwen2.5-7B-Instruct", listOf("Qwen/Qwen2.5-7B-Instruct", "THUDM/glm-4-9b-chat", "deepseek-ai/DeepSeek-V3"),
            "https://siliconflow.cn/", "注册送免费额度;部分小模型长期免费;有 DeepSeek/Qwen/GLM 等", free = true, contextWindow = 32768),
        ProviderPreset("魔搭 ModelScope", "modelscope", "https://api-inference.modelscope.cn/v1",
            "Qwen/Qwen2.5-7B-Instruct", listOf("Qwen/Qwen2.5-7B-Instruct", "Qwen/Qwen2.5-Coder-32B-Instruct"),
            "https://modelscope.cn/", "阿里魔搭,注册送免费推理额度;OpenAI 兼容", free = true, contextWindow = 32768),
        ProviderPreset("OpenCode Zen", "opencode-zen", "https://opencode.ai/zen/v1",
            "", listOf(),
            "https://opencode.ai/auth", "登录 opencode 领 key;推广期有免费模型(数据可能用于训练)", free = true),
        ProviderPreset("OpenRouter(免费模型)", "openrouter", "https://openrouter.ai/api/v1",
            "deepseek/deepseek-chat-v3:free", listOf("deepseek/deepseek-chat-v3:free", "meta-llama/llama-3.3-70b-instruct:free", "qwen/qwen-2.5-72b-instruct:free"),
            "https://openrouter.ai/", "国际平台,带 :free 后缀的模型免费但限速;需能连外网", free = true),

        // —— 付费(需购买 API 额度 / 订阅 coding plan)——
        ProviderPreset("DeepSeek 官方", "deepseek", "https://api.deepseek.com",
            "deepseek-chat", listOf("deepseek-chat", "deepseek-reasoner"),
            "https://platform.deepseek.com/", "官方 API,按量付费,便宜且缓存友好", free = false, contextWindow = 65536),
        ProviderPreset("Anthropic Claude(coding plan)", "anthropic", "https://api.anthropic.com",
            "claude-sonnet-4-5", listOf("claude-sonnet-4-5", "claude-opus-4-1"),
            "https://console.anthropic.com/", "需付费 API 或订阅 coding plan;Anthropic 协议", free = false, apiPathType = "anthropic", contextWindow = 200000),
        ProviderPreset("OpenAI", "openai", "https://api.openai.com/v1",
            "gpt-4o", listOf("gpt-4o", "gpt-4o-mini", "o3-mini"),
            "https://platform.openai.com/", "官方 API,按量付费", free = false, contextWindow = 128000),
        ProviderPreset("OpenAI Responses", "openai-responses", "https://api.openai.com/v1",
            "gpt-4o", listOf("gpt-4o", "gpt-4o-mini", "o3-mini"),
            "https://platform.openai.com/", "官方 Responses API,按量付费", free = false,
            apiPathType = "responses", contextWindow = 128000),
        ProviderPreset("Moonshot Kimi", "moonshot", "https://api.moonshot.cn/v1",
            "moonshot-v1-8k", listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            "https://platform.moonshot.cn/", "月之暗面 Kimi,按量付费", free = false, contextWindow = 128000),

        // —— OAuth 登录(无需手填 API Key)——
        ProviderPreset("Nous Portal(账号登录)", "nous", NousAuth.INFERENCE_BASE_URL,
            "", listOf(),
            NousAuth.PORTAL_BASE_URL, "用 Nous 账号授权登录即可,无需手动申请 API Key(设备码流程)",
            free = true, oauth = true),

        // —— 订阅套餐(Plan):按月订阅、非按量计费 ——
        ProviderPreset("小米 MiMo 代币计划(中国)", "xiaomi-plan-cn", "https://token-plan-cn.xiaomimimo.com/v1",
            "mimo-v2.5", listOf("mimo-v2.5", "mimo-v2.5-pro"),
            "https://xiaomimimo.com/", "小米代币计划,订阅制;注意只勾对话模型(带 -tts/-asr 的是语音模型,用于对话会报错)",
            free = false, plan = true, contextWindow = 128000),
        ProviderPreset("小米 MiMo 代币计划(新加坡)", "xiaomi-plan-sgp", "https://token-plan-sgp.xiaomimimo.com/v1",
            "mimo-v2.5", listOf("mimo-v2.5", "mimo-v2.5-pro"),
            "https://xiaomimimo.com/", "小米代币计划,新加坡节点", free = false, plan = true, contextWindow = 128000),
        ProviderPreset("小米 MiMo 代币计划(阿姆斯特丹)", "xiaomi-plan-ams", "https://token-plan-ams.xiaomimimo.com/v1",
            "mimo-v2.5", listOf("mimo-v2.5", "mimo-v2.5-pro"),
            "https://xiaomimimo.com/", "小米代币计划,欧洲节点", free = false, plan = true, contextWindow = 128000),
        ProviderPreset("智谱 GLM 编程套餐(国内)", "zhipu-coding-cn", "https://open.bigmodel.cn/api/coding/paas/v4",
            "glm-4.6", listOf("glm-4.6", "glm-4.5-air"),
            "https://open.bigmodel.cn/", "智谱编程套餐,订阅制,与按量计费分开", free = false, plan = true, contextWindow = 128000),
        ProviderPreset("Z.AI 编程套餐(国际)", "zai-coding", "https://api.z.ai/api/coding/paas/v4",
            "glm-4.6", listOf("glm-4.6", "glm-4.5-air"),
            "https://z.ai/", "Z.AI 编程套餐,订阅制", free = false, plan = true, contextWindow = 128000),
        ProviderPreset("Kimi 编程套餐", "kimi-coding", "https://api.kimi.com/coding",
            "", listOf(),
            "https://platform.moonshot.cn/", "Kimi 编程套餐,订阅制", free = false, plan = true, contextWindow = 128000),
        ProviderPreset("阶跃 StepFun Step Plan(国内)", "stepfun-plan-cn", "https://api.stepfun.com/step_plan/v1",
            "", listOf(),
            "https://platform.stepfun.com/", "阶跃星辰 Step Plan 订阅套餐", free = false, plan = true),
        ProviderPreset("阶跃 StepFun Step Plan(国际)", "stepfun-plan-intl", "https://api.stepfun.ai/step_plan/v1",
            "", listOf(),
            "https://stepfun.ai/", "阶跃星辰 Step Plan 订阅套餐,国际节点", free = false, plan = true),
        ProviderPreset("阿里云编程套餐", "alibaba-coding-plan", "https://coding-intl.dashscope.aliyuncs.com/v1",
            "", listOf(),
            "https://dashscope.console.aliyun.com/", "阿里云 Coding Plan 订阅套餐", free = false, plan = true),

        // —— 按量付费(补充)——
        ProviderPreset("小米 MiMo(按量)", "xiaomi", "https://api.xiaomimimo.com/v1",
            "mimo-v2.5", listOf("mimo-v2.5", "mimo-v2.5-pro"),
            "https://xiaomimimo.com/", "小米 MiMo 按量计费接口", free = false, contextWindow = 128000),
        ProviderPreset("Z.AI / GLM(国际)", "zai", "https://api.z.ai/api/paas/v4",
            "glm-4.6", listOf("glm-4.6", "glm-4.5-air"),
            "https://z.ai/", "智谱国际站,按量付费", free = false, contextWindow = 128000),
        ProviderPreset("Kimi / Moonshot(国际)", "kimi-intl", "https://api.moonshot.ai/v1",
            "kimi-k2-0905-preview", listOf("kimi-k2-0905-preview", "moonshot-v1-128k"),
            "https://platform.moonshot.ai/", "Moonshot 国际站", free = false, contextWindow = 128000),
        ProviderPreset("Google AI Studio", "gemini", "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash", emptyList(),
            "https://aistudio.google.com/", "Google AI Studio,有免费额度;需能连外网", free = true, contextWindow = 1000000),
        ProviderPreset("xAI Grok", "xai", "https://api.x.ai/v1",
            "grok-2", listOf("grok-2", "grok-2-mini"),
            "https://console.x.ai/", "xAI 官方 API,按量付费", free = false, contextWindow = 128000),
        ProviderPreset("MiniMax(国内)", "minimax-cn", "https://api.minimaxi.com/v1",
            "", listOf(),
            "https://platform.minimaxi.com/", "MiniMax 国内站", free = false),
        ProviderPreset("MiniMax(国际)", "minimax", "https://api.minimax.io/v1",
            "", listOf(),
            "https://www.minimax.io/", "MiniMax 国际站", free = false),
        ProviderPreset("LongCat(美团)", "longcat", "https://api.longcat.chat/openai/v1",
            "", listOf(),
            "https://longcat.chat/", "美团 LongCat,OpenAI 兼容", free = false),
        ProviderPreset("腾讯 TokenHub", "tencent-tokenhub", "https://tokenhub.tencentmaas.com/v1",
            "", listOf(),
            "https://tokenhub.tencentmaas.com/", "腾讯混元 TokenHub", free = false),
        ProviderPreset("NVIDIA NIM", "nvidia", "https://integrate.api.nvidia.com/v1",
            "", listOf(),
            "https://build.nvidia.com/", "NVIDIA NIM,注册有免费额度", free = true),
        ProviderPreset("Hugging Face Router", "huggingface", "https://router.huggingface.co/v1",
            "", listOf(),
            "https://huggingface.co/settings/tokens", "HF 推理路由,聚合多家模型", free = true),
        ProviderPreset("Novita AI", "novita", "https://api.novita.ai/openai/v1",
            "", listOf(),
            "https://novita.ai/", "Novita,OpenAI 兼容,按量付费", free = false),
        ProviderPreset("Vercel AI Gateway", "vercel-ai-gateway", "https://ai-gateway.vercel.sh",
            "", listOf(),
            "https://vercel.com/docs/ai-gateway", "Vercel AI 网关,聚合多家供应商", free = false),
        ProviderPreset("GMI Cloud", "gmi", "https://api.gmi-serving.com/v1",
            "", listOf(),
            "https://www.gmicloud.ai/", "GMI Cloud 推理服务", free = false),
        ProviderPreset("Kilo Code", "kilocode", "https://api.kilo.ai/api/gateway",
            "", listOf(),
            "https://kilo.ai/", "Kilo Code 网关", free = false),
        ProviderPreset("Ollama Cloud", "ollama-cloud", "https://ollama.com/v1",
            "", listOf(),
            "https://ollama.com/", "Ollama 云端托管模型", free = false),
        ProviderPreset("LM Studio(本地)", "lmstudio", "http://127.0.0.1:1234/v1",
            "", listOf(),
            "https://lmstudio.ai/", "本机 LM Studio 服务;手机上需指向局域网内的电脑地址", free = true),
        ProviderPreset("Ollama(本地)", "ollama", "http://localhost:11434/v1",
            "", listOf(),
            "https://ollama.com/", "本机 Ollama;手机上需改成局域网内电脑的 IP", free = true)
    )
}

/** 「免费模型 / 供应商市场」:分免费/付费两区,每个供应商带官网入口 + 一键添加(填 key)。 */
@Composable
fun ModelMarketScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    openAiClient: com.xincode.provider.OpenAiClient,
    onBack: () -> Unit,
    onConfigChanged: () -> Unit = {},
    showHeader: Boolean = true
) {
    val xc = LocalXinColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyDialogFor by remember { mutableStateOf<ProviderPreset?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var marketCategory by remember { mutableStateOf("all") }

    fun openSite(url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        if (showHeader) {
            XinPageHeader(
                title = t("模型与供应商"),
                subtitle = t("免费额度、订阅套餐和按量付费"),
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        toast?.let {
            Text(it, fontSize = 11.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        // 长市场页提供一个可收起的左侧筛选栏；搜索只在用户需要时展开，保持默认页面安静。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searchOpen) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 52.dp),
                    singleLine = true,
                    placeholder = { Text(t("搜索供应商或模型"), fontFamily = XinUiFont, fontSize = 12.sp, color = xc.faint) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = xc.sub) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = t("清除搜索"), tint = xc.sub)
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = xc.bgElevated, unfocusedContainerColor = xc.bgElevated,
                        focusedIndicatorColor = xc.green, unfocusedIndicatorColor = xc.border,
                        focusedTextColor = xc.ink, unfocusedTextColor = xc.ink, cursorColor = xc.green
                    ),
                    textStyle = TextStyle(fontFamily = XinUiFont, fontSize = 13.sp)
                )
            } else {
                Text(t("供应商市场"), fontFamily = XinSerifFont, fontSize = 18.sp, color = xc.ink, modifier = Modifier.weight(1f))
            }
            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) searchQuery = "" }) {
                Icon(Icons.Outlined.Search, contentDescription = t("搜索供应商"), tint = if (searchOpen) xc.green else xc.sub)
            }
            IconButton(onClick = { showFilters = !showFilters }) {
                Icon(Icons.Outlined.FilterList, contentDescription = t("筛选市场"), tint = if (showFilters) xc.green else xc.sub)
            }
        }

        val query = searchQuery.trim()
        val filteredPresets = ProviderPresets.ALL.filter { preset ->
            val categoryMatch = marketCategory == "all" ||
                (marketCategory == "free" && preset.free) ||
                (marketCategory == "plan" && preset.plan) ||
                (marketCategory == "paid" && !preset.free && !preset.plan)
            val textMatch = query.isBlank() || listOf(preset.name, preset.supplierId, preset.note, preset.models.joinToString(" ")).any { it.contains(query, ignoreCase = true) }
            categoryMatch && textMatch
        }
        val freeList = filteredPresets.filter { it.free }
        val planList = filteredPresets.filter { it.plan }
        val payList = filteredPresets.filter { !it.free && !it.plan }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (showFilters) {
                Column(
                    Modifier.width(116.dp).fillMaxHeight().background(xc.bgElevated).padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(t("筛选"), fontFamily = XinUiFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = xc.sub)
                    listOf("all" to t("全部"), "free" to t("免费"), "plan" to t("套餐"), "paid" to t("付费")).forEach { (id, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (marketCategory == id) xc.activeBg else Color.Transparent)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { marketCategory = id }
                                .padding(horizontal = 8.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, fontFamily = XinUiFont, fontSize = 12.sp, color = if (marketCategory == id) xc.green else xc.sub)
                        }
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { SectionLabel("免费 / 免费额度(自己注册领 key,每日有额度或限速)", xc) }
                items(freeList.size) { i ->
                    val p = freeList[i]
                    PresetCard(p, xc, onSite = { openSite(p.site) }, onAdd = { keyDialogFor = p })
                }
                item { Spacer(Modifier.height(6.dp)); SectionLabel("订阅套餐 Plan(按月订阅,非按量计费)", xc) }
                items(planList.size) { i ->
                    val p = planList[i]
                    PresetCard(p, xc, onSite = { openSite(p.site) }, onAdd = { keyDialogFor = p })
                }
                item { Spacer(Modifier.height(6.dp)); SectionLabel("按量付费(购买 API 额度)", xc) }
                items(payList.size) { i ->
                    val p = payList[i]
                    PresetCard(p, xc, onSite = { openSite(p.site) }, onAdd = { keyDialogFor = p })
                }
                if (filteredPresets.isEmpty()) item {
                    Text("没有匹配的供应商或模型", fontFamily = XinSerifFont, fontSize = 18.sp, color = xc.ink, modifier = Modifier.padding(top = 28.dp))
                }
            }
        }
    }

    // 填 key 对话框
    keyDialogFor?.let { p ->
        var key by remember(p) { mutableStateOf("") }
        var model by remember(p) { mutableStateOf(p.defaultModel) }
        // OAuth 设备码登录用:轮询中标记 + 待用户输入的用户码 + 授权网址。
        var oauthBusy by remember(p) { mutableStateOf(false) }
        var oauthUserCode by remember(p) { mutableStateOf("") }
        var oauthVerifyUri by remember(p) { mutableStateOf("") }
        // 授权拿到的令牌先存这儿。授权成功但还没选模型时对话框不关闭,
        // 再次点确认必须复用它,否则用户得在网页上重新授权一遍。
        var oauthToken by remember(p) { mutableStateOf("") }
        // 现拉的模型列表。预设自带的 models 往往只是几个代表性型号,而 OpenCode Zen /
        // Nous / Ollama 这类干脆是空的 —— 不给拉取入口的话,用户根本不知道该填什么。
        var fetched by remember(p) { mutableStateOf<List<String>>(emptyList()) }
        var fetching by remember(p) { mutableStateOf(false) }

        /** 用当前已有的凭据拉一次模型列表。token 为空时回落到输入框里的 key。 */
        fun fetchModels(tokenOverride: String = "") {
            val k = tokenOverride.ifBlank { key.trim() }
            if (k.isBlank()) { toast = "先填 API Key 再拉取"; return }
            fetching = true; toast = "正在拉取模型列表…"
            scope.launch {
                val r = openAiClient.listModels(p.baseUrl, k, p.supplierId)
                fetched = r.getOrDefault(emptyList())
                fetching = false
                toast = if (fetched.isEmpty()) "拉取失败或该供应商不提供列表接口,可手动填写模型 ID"
                        else "已拉取 ${fetched.size} 个模型,点选即可填入"
            }
        }
        AlertDialog(
            onDismissRequest = { keyDialogFor = null },
            title = { Text("添加 ${p.name}", fontSize = 14.sp, fontFamily = Mono, color = xc.ink) },
            text = {
                Column {
                    Text(p.note, fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                    Spacer(Modifier.height(8.dp))
                    if (p.oauth) {
                        // OAuth 预设:不需要手填 Key,点下方按钮走设备码授权。
                        if (oauthUserCode.isNotBlank()) {
                            Text("在打开的网页里输入用户码(点码可复制):", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                            Text(oauthUserCode, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink,
                                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager)
                                        ?.setPrimaryClip(android.content.ClipData.newPlainText("code", oauthUserCode))
                                    toast = "用户码已复制"
                                })
                            if (oauthVerifyUri.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text("重新打开授权网页 ›", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
                                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { openSite(oauthVerifyUri) })
                            }
                        } else {
                            Text("点下方「登录授权」→ 浏览器完成授权 → 自动获取访问令牌。", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                        }
                        Spacer(Modifier.height(10.dp))
                        TField(model, { model = it }, "模型 ID(登录后会自动拉取)", xc)
                    } else {
                        Text("官网注册领取 API Key ›", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
                            modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { openSite(p.site) })
                        Spacer(Modifier.height(10.dp))
                        TField(key, { key = it }, "粘贴 API Key", xc)
                        Spacer(Modifier.height(8.dp))
                        TField(model, { model = it }, "模型 ID(可改)", xc)
                    }

                    // 拉取入口。OAuth 供应商登录成功后会自动拉一次,这里仍保留手动重拉。
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (fetching) "拉取中…" else "↻ 拉取模型列表",
                            fontSize = 11.sp, fontFamily = Mono,
                            color = if (fetching) xc.faint else xc.green,
                            modifier = Modifier
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    if (!fetching) fetchModels()
                                }
                                .padding(vertical = 4.dp)
                        )
                        if (fetched.isNotEmpty()) {
                            Text("  共 ${fetched.size} 个", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                        }
                    }

                    // 拉到的列表:点一行就填进上面的模型 ID 输入框,省得手抄。
                    if (fetched.isNotEmpty()) {
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            fetched.forEach { m ->
                                Text(
                                    m, fontSize = 11.sp, fontFamily = Mono,
                                    color = if (m == model) xc.ink else xc.sub,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                        .background(if (m == model) xc.activeBg else xc.bg)
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { model = m }
                                        .padding(horizontal = 6.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !oauthBusy, onClick = {
                    // OAuth 分支:走设备码登录,拿到 token 后按同样方式落库。
                    if (p.oauth) {
                        if (oauthBusy) return@TextButton

                        /** 用给定令牌落库并启用。抽出来是因为「授权完」和「选完模型再确认」两条路都要走它。 */
                        suspend fun persist(tok: String, m: String) {
                            withContext(Dispatchers.IO) {
                                database.inTransaction {
                                    val enc = android.util.Base64.encodeToString(keystore.encrypt(tok), android.util.Base64.NO_WRAP)
                                    val dao = database.providerConfigDao()
                                    dao.deactivateAll()
                                    val id = dao.insert(
                                        ProviderConfigEntity(
                                            name = p.name, supplierId = p.supplierId, baseUrl = p.baseUrl,
                                            apiKeyEnc = enc, model = m,
                                            enabledModelIds = (p.models + fetched + m).filter { it.isNotBlank() }.distinct(),
                                            isActive = false, apiPathType = p.apiPathType, contextWindow = p.contextWindow
                                        )
                                    )
                                    dao.setActive(id)
                                }
                            }
                            toast = "已登录并启用 ${p.name}"
                            onConfigChanged()
                            oauthBusy = false; oauthUserCode = ""
                            keyDialogFor = null
                        }

                        // 已经授权过了(上一次点击拿到了 token,只是当时在等用户选模型):
                        // 直接落库,绝不重走设备码流程——否则用户要在网页上再授权一遍。
                        val existing = oauthToken
                        if (existing.isNotBlank()) {
                            val m = model.trim()
                            if (m.isBlank()) { toast = "请先选择或填写模型 ID"; return@TextButton }
                            oauthBusy = true
                            scope.launch { persist(existing, m) }
                            return@TextButton
                        }

                        oauthBusy = true; oauthUserCode = ""; toast = "正在申请设备码…"
                        scope.launch {
                            val dc = NousAuth.requestDeviceCode().getOrElse {
                                toast = "申请失败:${it.message?.take(100)}"; oauthBusy = false; return@launch
                            }
                            oauthUserCode = dc.userCode
                            oauthVerifyUri = dc.verificationUriComplete
                            toast = "请在网页完成授权(自动检测)"
                            openSite(dc.verificationUriComplete)
                            val tok = NousAuth.pollForToken(dc.deviceCode, dc.interval, dc.expiresIn) { tick -> toast = tick }
                                .getOrElse {
                                    toast = "登录失败:${it.message?.take(100)}"
                                    oauthBusy = false; oauthUserCode = ""; return@launch
                                }
                            // 令牌先存住。下面若因为要选模型而中途返回,再次点确认时才不用重新授权。
                            oauthToken = tok
                            // OAuth 预设通常没有 defaultModel,授权完还让用户自己猜模型 ID 就白登录了,
                            // 所以这里自动拉一次列表。
                            if (model.isBlank()) {
                                toast = "授权成功,正在拉取模型列表…"
                                val r = openAiClient.listModels(p.baseUrl, tok, p.supplierId)
                                fetched = r.getOrDefault(emptyList())
                                if (fetched.isNotEmpty()) {
                                    model = fetched.first()
                                    toast = "已拉取 ${fetched.size} 个模型,选好后点「确认添加」"
                                    oauthUserCode = ""   // 授权已完成,用户码没用了,收起来
                                    oauthBusy = false
                                    return@launch
                                }
                                toast = "授权成功,但该供应商未提供模型列表,请手填模型 ID 后确认"
                                oauthUserCode = ""; oauthBusy = false
                                return@launch
                            }
                            persist(tok, model.trim())
                        }
                        return@TextButton
                    }

                    val k = key.trim(); val m = model.trim()
                    if (k.isBlank() || m.isBlank()) { toast = "请填 key 和模型"; return@TextButton }
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            database.inTransaction {
                                val enc = android.util.Base64.encodeToString(keystore.encrypt(k), android.util.Base64.NO_WRAP)
                                val dao = database.providerConfigDao()
                                dao.deactivateAll()
                                val id = dao.insert(
                                    ProviderConfigEntity(
                                        name = p.name, supplierId = p.supplierId, baseUrl = p.baseUrl,
                                        apiKeyEnc = enc, model = m,
                                        enabledModelIds = (p.models + fetched + m).filter { it.isNotBlank() }.distinct(),
                                        isActive = false, apiPathType = p.apiPathType, contextWindow = p.contextWindow
                                    )
                                )
                                dao.setActive(id)
                            }
                        }
                        toast = "已添加并启用 ${p.name}"
                        onConfigChanged()
                        keyDialogFor = null
                    }
                }) {
                    Text(
                        when {
                            p.oauth && oauthBusy -> "登录中…(等待网页授权)"
                            // 已授权、只差选模型:文案要跟着变,否则用户以为还要再登录一次
                            p.oauth && oauthToken.isNotBlank() -> "确认添加"
                            p.oauth -> "登录授权"
                            else -> "添加并启用"
                        },
                        fontFamily = Mono, color = xc.green
                    )
                }
            },
            dismissButton = { TextButton(onClick = { keyDialogFor = null }) { Text("取消", fontFamily = Mono, color = xc.sub) } },
            containerColor = xc.bg
        )
    }
}

@Composable
private fun SectionLabel(text: String, xc: XinColors) {
    Text(text, fontSize = 11.sp, fontFamily = Mono, color = xc.faint, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
private fun PresetCard(p: ProviderPreset, xc: XinColors, onSite: () -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(xc.bgElevated).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink, modifier = Modifier.weight(1f))
            val (badge, bc) = when {
                p.free -> "免费" to xc.green
                p.plan -> "套餐" to xc.green   // 订阅制:与按量付费区分开,统一橙色系
                else -> "付费" to Color(0xFFF2C14E)
            }
            // 可登录授权的单独标一个:免手填 Key 是很实在的差别,埋在对话框里用户翻不到。
            if (p.oauth) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(xc.green.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text("免 Key 登录", fontSize = 10.sp, fontFamily = Mono, color = xc.green)
                }
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(bc.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(badge, fontSize = 10.sp, fontFamily = Mono, color = bc)
            }
        }
        Text(p.note, fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(top = 4.dp))
        if (p.models.isNotEmpty()) Text("模型:" + p.models.joinToString("、").take(120), fontSize = 10.sp, fontFamily = Mono, color = xc.faint, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("官网注册 ›", fontSize = 12.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSite() })
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(xc.green.copy(alpha = 0.15f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onAdd() }
                .padding(horizontal = 16.dp, vertical = 7.dp)) {
                Text("添加", fontSize = 12.sp, fontFamily = Mono, color = xc.green)
            }
        }
    }
}

@Composable
private fun TField(value: String, onValue: (String) -> Unit, placeholder: String, xc: XinColors) {
    TextField(
        value = value, onValueChange = onValue, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, fontSize = 12.sp, fontFamily = Mono, color = xc.faint) },
        textStyle = TextStyle(fontSize = 12.sp, fontFamily = Mono),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink
        )
    )
}
