package com.xincode.app

import android.util.Base64
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xincode.data.AppDatabase
import com.xincode.data.ModelProfile
import com.xincode.data.ModelProfileCodec
import com.xincode.data.ProviderConfigEntity
import com.xincode.provider.OpenAiClient
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- palette ---
private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border

private const val TAG = "XincodeUI"

// ── known supplier catalog (fallback models; live fetch preferred via fetchModels()) ──

// --- supplier catalog ---
private data class Supplier(
    val id: String, val name: String, val baseUrl: String,
    val defaultModel: String, val models: List<String>,
    val apiPathType: String = "openai"
)

private val knownSuppliers = listOf(
    Supplier("deepseek", "DeepSeek", "https://api.deepseek.com", "deepseek-chat",
        listOf("deepseek-chat", "deepseek-reasoner")),
    Supplier("openai", "OpenAI", "https://api.openai.com", "gpt-4o-mini",
        listOf("gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo", "o1-mini", "o3-mini")),
    Supplier("openai-responses", "OpenAI Responses", "https://api.openai.com/v1", "gpt-4o",
        listOf("gpt-4o", "gpt-4o-mini", "o3-mini"), apiPathType = "responses"),
    Supplier("siliconflow", "硅基流动", "https://api.siliconflow.cn", "deepseek-ai/DeepSeek-V3",
        listOf("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "Qwen/Qwen2.5-7B-Instruct")),
    Supplier("groq", "Groq", "https://api.groq.com/openai", "llama-3.3-70b-versatile",
        listOf("llama-3.3-70b-versatile", "mixtral-8x7b-32768", "gemma2-9b-it")),
    Supplier("zhipu", "智谱AI", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
        listOf("glm-4", "glm-4-flash", "glm-4-plus")),
    Supplier("dashscope", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-turbo",
        listOf("qwen-turbo", "qwen-plus", "qwen-max")),
    Supplier("moonshot", "Moonshot", "https://api.moonshot.cn", "moonshot-v1-8k",
        listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")),
    Supplier("baidu", "百度千帆", "https://qianfan.baidubce.com/v2", "ernie-3.5-8k",
        listOf("ernie-3.5-8k", "ernie-4.0-8k", "ernie-speed-8k")),
    Supplier("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash",
        emptyList()),
    Supplier("ollama", "Ollama (本地)", "http://localhost:11434", "",
        listOf("llama3", "qwen2.5", "deepseek-r1", "mistral")),
    Supplier("anthropic", "Anthropic", "https://api.anthropic.com", "claude-sonnet-4-20250514",
        listOf("claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-3-opus-20240229"),
        apiPathType = "anthropic"),
    Supplier("nous", "Nous Portal", "https://inference.nousresearch.com", "",
        emptyList()),
    Supplier("openrouter", "OpenRouter", "https://openrouter.ai/api", "",
        emptyList()),
    Supplier("xai", "xAI (Grok)", "https://api.x.ai", "grok-2",
        listOf("grok-2", "grok-2-mini")),
    Supplier("custom", "自定义", "", "", emptyList(), apiPathType = "openai")
)

// ── SupplierConfigScreen ──
// Model list via fetchModels() only; never from hardcoded list or Room entity.
@Composable
fun SupplierConfigScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    openAiClient: OpenAiClient,
    onBack: () -> Unit,
    onConfigChanged: () -> Unit = {},
    showHeader: Boolean = true
) {
    val configDao = database.providerConfigDao()
    val scope = rememberCoroutineScope()

    var savedConfigs by remember { mutableStateOf<List<ProviderConfigEntity>>(emptyList()) }
    var activeId by remember { mutableStateOf<Long?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<ProviderConfigEntity?>(null) }
    var status by remember { mutableStateOf("") }

    // Form state
    var configName by remember { mutableStateOf("") }
    var selectedSupplierId by remember { mutableStateOf("deepseek") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var checkedModelIds by remember { mutableStateOf<Set<String>>(emptySet()) }  // multi-select state
    var showSupplierDropdown by remember { mutableStateOf(false) }
    var selectedApiPathType by remember { mutableStateOf("openai") }
    var showApiPathDropdown by remember { mutableStateOf(false) }
    // 能力声明。ToolCall 默认开:老配置迁移后也是 1,不能让人升级完 agent 就不动了。
    var capVision by remember { mutableStateOf(false) }
    var capAudio by remember { mutableStateOf(false) }
    var capVideo by remember { mutableStateOf(false) }
    var capToolCall by remember { mutableStateOf(true) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    /**
     * 手动填写的模型 ID。
     *
     * 不少中转站不提供 /models 列表接口(或返回的列表跟实际可用的对不上),拉取拿不到东西,
     * 配置就彻底走不下去。这里单独存一份手填集合,不跟拉取结果混在一起,原因有二:
     *  1. 再次点「↻ 刷新」会整体覆盖 models,混在一起的话手填的会被冲掉;
     *  2. 编辑已有配置时 models 是空的(等用户现拉),手填的必须仍然看得见。
     */
    var manualModelIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var newModelId by remember { mutableStateOf("") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var pendingActivateId by remember { mutableStateOf<Long?>(null) }  // warn before activating
    var editingApiKey by remember { mutableStateOf("") }  // decrypted stored key for live fetch during edit
    var modelProfiles by remember { mutableStateOf<Map<String, ModelProfile>>(emptyMap()) }
    var modelProfileTarget by remember { mutableStateOf<String?>(null) }
    var switchModelConfig by remember { mutableStateOf<ProviderConfigEntity?>(null) }

    val selectedSupplier = knownSuppliers.find { it.id == selectedSupplierId } ?: knownSuppliers.last()
    val isCustom = selectedSupplierId == "custom"
    val effectiveBaseUrl = if (isCustom) baseUrl else selectedSupplier.baseUrl
    // 手填的排在拉取结果前面:刚添加的一眼就能看到,不用在几百个模型里翻。
    val displayModels = (manualModelIds.toList() + models).distinct()

    fun loadConfigs() {
        scope.launch {
            savedConfigs = withContext(Dispatchers.IO) { configDao.getAll() }
            activeId = savedConfigs.firstOrNull { it.isActive }?.id
        }
    }

    fun activateConfig(id: Long) {
        if (id != activeId) {
            pendingActivateId = id  // show warning first
        }
    }

    fun changeConfigModel(cfg: ProviderConfigEntity, newModel: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                configDao.update(cfg.copy(model = newModel))
            }
            switchModelConfig = null
            loadConfigs()
            status = "✓ ${cfg.name} 已更换模型为 $newModel"
            onConfigChanged()
        }
    }

    fun confirmActivate() {
        val id = pendingActivateId ?: return
        pendingActivateId = null
        scope.launch {
            withContext(Dispatchers.IO) {
                database.inTransaction {
                    configDao.deactivateAll()
                    configDao.setActive(id)
                }
            }
            activeId = id; status = "✓ 已切换配置"
            onConfigChanged()
        }
    }

    fun deleteConfig(cfg: ProviderConfigEntity) {
        scope.launch {
            withContext(Dispatchers.IO) {
                database.inTransaction {
                    configDao.delete(cfg)
                    if (cfg.isActive) {
                        val fallback = configDao.getAll().firstOrNull()
                        configDao.deactivateAll()
                        fallback?.let { configDao.setActive(it.id) }
                    }
                }
            }
            activeId = null
            loadConfigs()
            status = "✓ 已删除"
            onConfigChanged()
        }
    }

    fun startNew() {
        editingConfig = null; configName = ""; selectedSupplierId = "deepseek"
        baseUrl = ""; apiKey = ""; model = ""; models = emptyList()
        editingApiKey = ""; checkedModelIds = emptySet(); selectedApiPathType = "openai"
        manualModelIds = emptySet(); newModelId = ""
        modelProfiles = emptyMap(); modelProfileTarget = null
        capVision = false; capAudio = false; capVideo = false; capToolCall = true
        showForm = true; status = ""
    }

    fun startEdit(cfg: ProviderConfigEntity) {
        editingConfig = cfg; configName = cfg.name; selectedSupplierId = cfg.supplierId
        baseUrl = cfg.baseUrl; apiKey = ""; model = cfg.model
        // 已保存列表只是离线缓存；在线刷新成功后会被真实服务端列表替换。
        models = cfg.enabledModelIds
        checkedModelIds = cfg.enabledModelIds.toSet()
        // 真正手填模型由用户在本次编辑中添加，不能把所有旧缓存误判为手填。
        manualModelIds = emptySet()
        newModelId = ""
        capVision = cfg.supportsVision; capAudio = cfg.supportsAudio
        capVideo = cfg.supportsVideo; capToolCall = cfg.supportsToolCall
        modelProfiles = ModelProfileCodec.decode(cfg.modelSettingsJson)
        modelProfileTarget = null
        selectedApiPathType = cfg.apiPathType
        // Decrypt stored key so user can refresh models without re-entering
        editingApiKey = try {
            keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        } catch (_: Exception) { "" }
        showForm = true; status = ""
    }

    fun fetchModels() {
        val key = apiKey.ifBlank { editingApiKey }
        if (key.isBlank() || effectiveBaseUrl.isBlank()) {
            status = "✗ 需要 api_key 才能拉取模型列表"; return
        }
        modelsLoading = true; status = ""
        scope.launch {
            val liveModels = openAiClient.listModels(effectiveBaseUrl, key, selectedSupplierId).getOrDefault(emptyList())
            modelsLoading = false
            if (liveModels.isEmpty()) {
                status = "✗ 拉取失败或无可用模型，可在下方手动填写模型 ID"
            } else {
                // 在线列表是权威来源；旧缓存中的下线模型不再继续混入选择器。
                models = liveModels
                val allowed = liveModels.toSet() + manualModelIds
                checkedModelIds = if (checkedModelIds.isEmpty()) {
                    setOf(liveModels.first())
                } else {
                    checkedModelIds.intersect(allowed).ifEmpty { setOf(liveModels.first()) }
                }
                if (model !in checkedModelIds) model = checkedModelIds.first()
                showModelDropdown = true
                status = "✓ 已加载 ${liveModels.size} 个模型"
            }
        }
    }

    /**
     * 手动加一个模型 ID(给不支持 /models 列表接口的中转站用)。
     * 加完直接勾选;若当前还没有活动模型,顺带设为活动,省得用户再点一次。
     */
    fun addManualModel() {
        val id = newModelId.trim()
        if (id.isBlank()) return
        if (id in manualModelIds || id in models) {
            status = "✗ 「$id」已在列表中"; newModelId = ""; return
        }
        manualModelIds = manualModelIds + id
        checkedModelIds = checkedModelIds + id
        if (model.isBlank()) model = id
        newModelId = ""
        status = "✓ 已添加 $id"
    }

    fun saveConfig() {
        val url = effectiveBaseUrl.ifBlank {
            status = "✗ base_url 不能为空"; return
        }
        if (apiKey.isBlank() && editingConfig == null) {
            status = "✗ api_key 不能为空"; return
        }
        if (checkedModelIds.isEmpty()) { status = "✗ 至少勾选一个模型"; return }
        if (model.isBlank() && checkedModelIds.isNotEmpty()) {
            model = checkedModelIds.first()  // auto-select first if none active
        }
        val name = configName.ifBlank { selectedSupplier.name }
        val keyEnc = if (apiKey.isNotBlank()) {
            Base64.encodeToString(keystore.encrypt(apiKey), Base64.NO_WRAP)
        } else {
            editingConfig?.apiKeyEnc ?: ""
        }
        scope.launch {
            val entity = ProviderConfigEntity(
                id = editingConfig?.id ?: 0,
                name = name, supplierId = selectedSupplierId,
                baseUrl = url, apiKeyEnc = keyEnc, model = model,
                enabledModelIds = checkedModelIds.toList(),
                isActive = editingConfig?.isActive ?: savedConfigs.isEmpty(),
                apiPathType = selectedApiPathType,
                supportsVision = capVision, supportsAudio = capAudio,
                supportsVideo = capVideo, supportsToolCall = capToolCall,
                // 编辑时保留原有的上下文窗口/压缩阈值,别被默认值悄悄清掉
                contextWindow = editingConfig?.contextWindow ?: 0,
                autoCompactThresholdPercent = editingConfig?.autoCompactThresholdPercent ?: 85,
                extraHeadersJson = editingConfig?.extraHeadersJson ?: "",
                modelSettingsJson = ModelProfileCodec.encode(modelProfiles)
            )
            val newId = withContext(Dispatchers.IO) {
                database.inTransaction {
                    if (editingConfig != null) {
                        configDao.update(entity)
                        0L
                    } else {
                        val id = configDao.insert(entity.copy(isActive = false))
                        configDao.deactivateAll()
                        configDao.setActive(id)
                        id
                    }
                }
            }
            if (newId > 0L) activeId = newId
            showForm = false; loadConfigs()
            status = "✓ 配置已保存"
            onConfigChanged()
        }
    }

    LaunchedEffect(Unit) { loadConfigs() }

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp)) {
        if (showHeader) {
            XinPageHeader(
                title = "供应商配置",
                subtitle = "管理 API、密钥和可用模型",
                onBack = onBack
            ) {
                XinHeaderAction(label = "新建", onClick = { startNew() })
            }
            Spacer(Modifier.height(12.dp))
        }

        // 顶部操作行：无论是否隐藏 header，始终露出「+ 新建配置」按钮
        if (!showForm) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "已保存配置 (${savedConfigs.size})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = XinUiFont,
                        color = Ink
                    )
                    Text(
                        "点击条目即可直接切换为当前全局运行配置",
                        fontSize = 11.sp,
                        fontFamily = XinUiFont,
                        color = Faint
                    )
                }
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(17.dp))
                        .background(Green)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { startNew() }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "+ 新建配置",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = XinUiFont,
                        color = Bg
                    )
                }
            }
        }

        // Config list
        if (savedConfigs.isEmpty() && !showForm) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(LocalXinColors.current.bgElevated)
                    .border(1.dp, Border, RoundedCornerShape(18.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("尚未添加任何供应商配置", fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = XinUiFont, color = Ink)
                Spacer(Modifier.height(6.dp))
                Text("支持 OpenAI、DeepSeek、Claude、通义千问及本地 Ollama 等接入", fontSize = 11.sp, fontFamily = XinUiFont, color = Sub)
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(Green)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { startNew() }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("+ 立即添加配置", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Bg, fontFamily = XinUiFont)
                }
            }
        } else {
            savedConfigs.forEach { cfg ->
                val isActive = cfg.id == activeId
                Row(Modifier.fillMaxWidth().background(if (isActive) LocalXinColors.current.activeBg else Bg)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { activateConfig(cfg.id) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ProviderAvatar(
                        supplierId = cfg.supplierId,
                        size = 36.dp,
                        contentDescription = "${cfg.name} 供应商图标"
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cfg.name, fontSize = 12.sp, fontFamily = XinUiFont,
                            color = if (isActive) Ink else Sub)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${knownSuppliers.find{it.id==cfg.supplierId}?.name ?: cfg.supplierId} · ${cfg.model}",
                                fontSize = 10.sp, fontFamily = XinUiFont, color = Faint)
                            Spacer(Modifier.width(6.dp))
                            Text("更换模型", fontSize = 9.sp, fontFamily = XinUiFont, color = Green,
                                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    switchModelConfig = cfg
                                })
                        }
                    }
                    if (isActive) Text("✓", fontSize = 12.sp, color = Green, modifier = Modifier.padding(end = 8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { startEdit(cfg) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Edit, contentDescription = "编辑 ${cfg.name}", tint = Sub, modifier = Modifier.size(19.dp))
                        }
                        IconButton(onClick = { deleteConfig(cfg) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除 ${cfg.name}", tint = Red, modifier = Modifier.size(19.dp))
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            }
        }

        // Form
        if (showForm) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            Spacer(Modifier.height(16.dp))

            Text(if (editingConfig != null) "编辑配置" else "新建配置", fontSize = 13.sp, fontFamily = XinUiFont, color = Ink)
            Spacer(Modifier.height(12.dp))

            Label("名称")
            TextField(value = configName, onValueChange = { configName = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), textStyle = fieldTextStyle(),
                placeholder = { Text("例如: 我的DeepSeek", color = Faint, fontSize = 12.sp, fontFamily = XinUiFont) })
            Spacer(Modifier.height(12.dp))

            // Supplier selector
            Label("供应商")
            Box(Modifier.fillMaxWidth().zIndex(10f)) {
                Row(Modifier.fillMaxWidth().border(1.dp, Border, RoundedCornerShape(16.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProviderAvatar(
                        supplierId = selectedSupplier.id,
                        size = 32.dp,
                        contentDescription = "${selectedSupplier.name} 图标"
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(selectedSupplier.name, fontSize = 13.sp, fontFamily = XinUiFont, color = Ink,
                        modifier = Modifier.weight(1f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            showSupplierDropdown = !showSupplierDropdown
                        }.padding(horizontal = 8.dp, vertical = 4.dp))
                    Text("▼", fontSize = 10.sp, color = Faint, modifier = Modifier.padding(end = 8.dp))
                }
                if (showSupplierDropdown) {
                    Column(Modifier.fillMaxWidth().offset(y = 56.dp).background(Bg, RoundedCornerShape(16.dp)).border(1.dp, Border, RoundedCornerShape(16.dp))
                        .padding(vertical = 4.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        knownSuppliers.forEach { sup ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (sup.id == selectedSupplierId) LocalXinColors.current.activeBg else Bg)
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        selectedSupplierId = sup.id; showSupplierDropdown = false
                                        model = sup.defaultModel; models = emptyList()
                                        selectedApiPathType = sup.apiPathType
                                        if (sup.id != "custom") baseUrl = sup.baseUrl else baseUrl = ""
                                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ProviderAvatar(
                                    supplierId = sup.id,
                                    size = 30.dp,
                                    contentDescription = "${sup.name} 图标"
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    sup.name,
                                    fontSize = 12.sp,
                                    fontFamily = XinUiFont,
                                    color = if (sup.id == selectedSupplierId) Ink else Sub
                                )
                            }
                        }
                    }
                }
            }
            if (effectiveBaseUrl.isNotBlank()) {
                // 预览必须与 OpenAiClient.chatEndpoint 的拼法一致:先剥掉用户可能自带的 /v1,再补全,
                // 否则界面显示的地址和实际请求的地址不一样,排查问题时更误导。
                val shown = if (selectedApiPathType == "custom") {
                    effectiveBaseUrl.trim().trimEnd('/')
                } else {
                    val base = effectiveBaseUrl.trim().trimEnd('/')
                    // 与 OpenAiClient.chatEndpoint 完全同一套规则:base_url 自带版本段(/v1、/v4…)时只接资源路径。
                    val versioned = Regex("/v\\d+[a-zA-Z0-9]*$").containsMatchIn(base)
                    base + when (selectedApiPathType) {
                        "anthropic" -> if (versioned) "/messages" else "/v1/messages"
                        "responses" -> if (versioned) "/responses" else "/v1/responses"
                        else -> if (versioned) "/chat/completions" else "/v1/chat/completions"
                    }
                }
                Text(shown, fontSize = 10.sp, fontFamily = XinUiFont, color = Faint, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))

            if (isCustom) {
                Label("base_url")
                TextField(value = baseUrl, onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), textStyle = fieldTextStyle(),
                    placeholder = { Text("https://api.xxx.com", color = Faint, fontSize = 12.sp, fontFamily = XinUiFont) })
                Spacer(Modifier.height(12.dp))

                Label("API 路径类型")
                Box(Modifier.fillMaxWidth().zIndex(9f)) {
                    val apiPathLabel = when (selectedApiPathType) {
                        "openai" -> "OpenAI 兼容 (自动追加 /v1/chat/completions)"
                        "responses" -> "OpenAI Responses (自动追加 /v1/responses)"
                        "anthropic" -> "Anthropic 兼容 (自动追加 /v1/messages)"
                        else -> "自定义 (完整 URL，不追加)"
                    }
                    Row(Modifier.fillMaxWidth().border(1.dp, Faint).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(apiPathLabel, fontSize = 12.sp, fontFamily = XinUiFont, color = Ink,
                            modifier = Modifier.weight(1f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                showApiPathDropdown = !showApiPathDropdown
                            }.padding(horizontal = 8.dp, vertical = 4.dp))
                        Text("▼", fontSize = 10.sp, color = Faint, modifier = Modifier.padding(end = 8.dp))
                    }
                    if (showApiPathDropdown) {
                        Column(Modifier.fillMaxWidth().offset(y = 42.dp).background(Bg).border(1.dp, Faint)
                            .padding(vertical = 4.dp)) {
                            listOf("openai" to "OpenAI 兼容\n自动追加 /v1/chat/completions",
                                   "responses" to "OpenAI Responses\n自动追加 /v1/responses",
                                   "anthropic" to "Anthropic 兼容\n自动追加 /v1/messages",
                                   "custom" to "自定义\n完整 URL，不追加").forEach { (id, label) ->
                                Text(label, fontSize = 11.sp, fontFamily = XinUiFont,
                                    color = if (id == selectedApiPathType) Ink else Sub,
                                    modifier = Modifier.fillMaxWidth()
                                        .background(if (id == selectedApiPathType) LocalXinColors.current.activeBg else Bg)
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            selectedApiPathType = id; showApiPathDropdown = false
                                        }.padding(horizontal = 12.dp, vertical = 7.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Label("api_key")
            TextField(value = apiKey, onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), textStyle = fieldTextStyle(),
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(if (editingConfig != null && apiKey.isEmpty()) "留空则保留原 Key" else "sk-...", color = Faint, fontSize = 12.sp, fontFamily = XinUiFont) })
            Spacer(Modifier.height(12.dp))

            Label("启用模型（多选）")
            // 手填入口:中转站不给 /models 时的唯一出路,所以放在列表【上方】常驻,
            // 而不是藏在「拉取失败」之后才出现——拉取成功但列表不全的情况同样需要它。
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = newModelId,
                    onValueChange = { newModelId = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    colors = fieldColors(),
                    textStyle = fieldTextStyle(),
                    placeholder = {
                        Text("拉不到列表？直接填模型 ID，如 gpt-4o", color = Faint,
                            fontSize = 12.sp, fontFamily = XinUiFont)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addManualModel() })
                )
                Text("+ 添加", fontSize = 12.sp, fontFamily = XinUiFont,
                    color = if (newModelId.isBlank()) Faint else Green,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { addManualModel() }
                        .padding(horizontal = 10.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().border(1.dp, Faint).padding(4.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    if (modelsLoading) {
                        Text("加载中…", fontSize = 12.sp, fontFamily = XinUiFont, color = Faint,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    } else if (displayModels.isEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("点 ↻ 拉取，或在上方直接填模型 ID", fontSize = 12.sp, fontFamily = XinUiFont, color = Faint,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            Text("↻", fontSize = 14.sp, fontFamily = XinUiFont, color = Sub,
                                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fetchModels() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                    } else {
                        // Refresh header
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("↻ 刷新", fontSize = 10.sp, fontFamily = XinUiFont, color = Sub,
                                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fetchModels() })
                            Text(if (model.isNotEmpty()) "当前: $model" else "未选", fontSize = 10.sp, fontFamily = XinUiFont, color = Faint)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
                        // Checkbox list
                        displayModels.forEach { m ->
                            val isChecked = m in checkedModelIds
                            val isManual = m in manualModelIds
                            // 只有「确实拉到过列表」且该模型不在其中、又不是手填的,才算供应商已下线。
                            // 手填的模型本来就不在拉取结果里,不能因此标红。
                            val isUnavailable = models.isNotEmpty() && !isManual && m !in models
                            Row(Modifier.fillMaxWidth()
                                .background(if (m == model) LocalXinColors.current.activeBg else Bg)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    val newSet = if (isChecked) checkedModelIds - m else checkedModelIds + m
                                    if (newSet.isEmpty()) { status = "✗ 至少保留一个启用模型"; return@clickable }
                                    checkedModelIds = newSet
                                    if (m == model && !isChecked) model = newSet.first()
                                }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                // Checkbox indicator
                                Text(if (isChecked) "☑" else "☐", fontSize = 13.sp, color = if (isChecked) Green else Faint,
                                    modifier = Modifier.padding(end = 8.dp))
                                // Model name
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(m, fontSize = 12.sp, fontFamily = XinUiFont,
                                            color = if (m == model) Ink else Sub)
                                        // 标出哪些是自己填的,便于跟拉取来的区分(手填写错了才好找回来改)
                                        if (isManual) {
                                            Text("手填", fontSize = 8.sp, fontFamily = XinUiFont, color = Faint,
                                                modifier = Modifier.padding(start = 6.dp))
                                        }
                                    }
                                    if (isUnavailable) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Outlined.ErrorOutline,
                                                contentDescription = "不可用",
                                                modifier = Modifier.size(12.dp).padding(end = 2.dp),
                                                tint = Red
                                            )
                                            Text("该模型已不可用，将自动取消勾选", fontSize = 9.sp, fontFamily = XinUiFont, color = Red)
                                        }
                                    }
                                }
                                // Active marker
                                if (m == model) Text("←", fontSize = 10.sp, color = Sub, modifier = Modifier.padding(start = 4.dp))
                                IconButton(onClick = { modelProfileTarget = m }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Outlined.Settings, contentDescription = "配置模型", tint = if (modelProfiles.containsKey(m)) Green else Faint, modifier = Modifier.size(17.dp))
                                }
                                // 手填的可以删掉(拉取来的不给删,刷新一下就回来了,给了反而误导)
                                if (isManual) {
                                    Text("✕", fontSize = 11.sp, fontFamily = XinUiFont, color = Faint,
                                        modifier = Modifier
                                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                                manualModelIds = manualModelIds - m
                                                val remaining = checkedModelIds - m
                                                checkedModelIds = remaining
                                                // 删掉的正好是活动模型 → 顺延到还勾着的第一个,别留空
                                                if (model == m) model = remaining.firstOrNull().orEmpty()
                                                status = "✓ 已移除 $m"
                                            }
                                            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ---- 能力声明 ----
            Label("模型能力")
            CapabilityRow(
                "模型支持识图", "启用后图片直接发给该模型;关闭则交由 describe_image 转给视觉副模型",
                capVision
            ) { capVision = it }
            CapabilityRow(
                "模型支持音频解析", "启用后音频直接发给该模型;关闭则走 transcribe_audio 转写",
                capAudio
            ) { capAudio = it }
            CapabilityRow(
                "模型支持视频解析", "声明用,当前暂无视频直传链路,勾选仅用于功能分配时的匹配提示",
                capVideo
            ) { capVideo = it }
            CapabilityRow(
                "模型支持 ToolCall",
                if (capToolCall) "使用 API 原生工具调用接口(需要模型支持)"
                else "关闭后不会发送 tools 字段,该模型将无法调用任何工具,只能纯聊天",
                capToolCall
            ) { capToolCall = it }
            if (!capToolCall) {
                Text(
                    "注意:关闭 ToolCall 后这套配置只能对话,不能执行工具。",
                    fontSize = 10.sp, fontFamily = XinUiFont, color = Red,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("取消", fontSize = 13.sp, fontFamily = XinUiFont, color = Sub,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showForm = false }.padding(horizontal = 12.dp, vertical = 8.dp))
                Spacer(Modifier.width(12.dp))
                Text("保存配置", fontSize = 13.sp, fontFamily = XinUiFont, color = Ink,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { saveConfig() }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(64.dp))  // 底部留白:配合整页可滚动,保存按钮完整露出、不贴屏幕/导航栏底边
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 11.sp, fontFamily = XinUiFont,
                color = if (status.contains("✓")) Green else if (status.contains("✗")) Red else Sub)
        }

        // ---- 更换模型对话框 ----
        switchModelConfig?.let { targetCfg ->
            val available = (targetCfg.enabledModelIds + listOf(targetCfg.model)).filter { it.isNotBlank() }.distinct()
            AlertDialog(
                onDismissRequest = { switchModelConfig = null },
                title = { Text("更换模型 - ${targetCfg.name}", fontFamily = XinUiFont, color = Ink, fontSize = 14.sp) },
                text = {
                    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        Text("点击选择该供应商的默认运行模型：", fontSize = 11.sp, fontFamily = XinUiFont, color = Sub)
                        Spacer(Modifier.height(8.dp))
                        available.forEach { m ->
                            val isCurrent = m == targetCfg.model
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isCurrent) LocalXinColors.current.activeBg else Bg)
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        changeConfigModel(targetCfg, m)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    m,
                                    fontSize = 12.sp,
                                    fontFamily = XinUiFont,
                                    color = if (isCurrent) Ink else Sub,
                                    modifier = Modifier.weight(1f)
                                )
                                if (isCurrent) {
                                    Text("✓ 当前", fontSize = 10.sp, fontFamily = XinUiFont, color = Green)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { switchModelConfig = null }) {
                        Text("关闭", fontFamily = XinUiFont, color = Sub)
                    }
                },
                containerColor = Bg
            )
        }

        modelProfileTarget?.let { modelId ->
            ModelProfileDialog(
                modelId = modelId,
                initial = modelProfiles[modelId] ?: ModelProfile(),
                onDismiss = { modelProfileTarget = null },
                onApply = { profile ->
                    modelProfiles = modelProfiles + (modelId to profile)
                    modelProfileTarget = null
                }
            )
        }

        // ---- model switch warning dialog ----
        if (pendingActivateId != null) {
            val targetCfg = savedConfigs.find { it.id == pendingActivateId }
            AlertDialog(
                onDismissRequest = { pendingActivateId = null },
                title = { Text("切换模型", fontFamily = XinUiFont, color = Ink) },
                text = {
                    Text(
                        "将切换到「${targetCfg?.name ?: "新配置"}」(${targetCfg?.model ?: "?"})。\n\n" +
                        "新模型可能无法解析当前对话中的历史工具调用记录，强烈建议开启新会话后再切换。\n\n" +
                        "是否继续切换？",
                        fontFamily = XinUiFont, fontSize = 12.sp, color = Ink, lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmActivate() }) {
                        Text("切换", fontFamily = XinUiFont, color = Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingActivateId = null }) {
                        Text("取消", fontFamily = XinUiFont, color = Sub)
                    }
                },
                containerColor = Bg
            )
        }
    }
}

/** 能力开关行:左侧标题+说明,右侧开关。说明文字随开关状态变化,让后果当场可见。 */
@Composable
private fun CapabilityRow(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 12.sp, fontFamily = XinUiFont, color = Ink)
            Text(hint, fontSize = 10.sp, fontFamily = XinUiFont, color = Faint,
                lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Bg,
                checkedTrackColor = Green,
                uncheckedThumbColor = Sub,
                uncheckedTrackColor = Bg,
                uncheckedBorderColor = Faint
            )
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 11.sp, fontFamily = XinUiFont, color = Sub, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Bg, unfocusedContainerColor = Bg,
    focusedIndicatorColor = Ink, unfocusedIndicatorColor = Faint,
    cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink
)

@Composable
private fun fieldTextStyle() = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = XinUiFont)


@Composable
private fun ModelProfileDialog(
    modelId: String,
    initial: ModelProfile,
    onDismiss: () -> Unit,
    onApply: (ModelProfile) -> Unit
) {
    val xc = LocalXinColors.current
    var contextText by remember(modelId) { mutableStateOf(formatTokenBudget(initial.contextWindow)) }
    var outputText by remember(modelId) { mutableStateOf(formatTokenBudget(initial.maxOutputTokens)) }
    var thinkingEffort by remember(modelId) { mutableStateOf(initial.thinkingEffort) }
    var supportsImage by remember(modelId) { mutableStateOf(initial.supportsImageInput) }
    val efforts = listOf("auto", "none", "minimal", "low", "medium", "high", "xhigh", "max")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("模型配置", fontFamily = XinSerifFont, fontWeight = FontWeight.SemiBold, color = xc.ink) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            ) {
                Text(modelId, fontFamily = XinCodeFont, fontSize = 12.sp, color = xc.green, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                Text("上下文窗口", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub)
                Text("输入过长时按此容量触发自动压缩；自动表示跟随供应商配置。", fontFamily = XinUiFont, fontSize = 10.sp, color = xc.faint)
                Spacer(Modifier.height(5.dp))
                TextField(
                    value = contextText,
                    onValueChange = { contextText = it },
                    modifier = Modifier.fillMaxWidth().border(1.dp, xc.border, RoundedCornerShape(12.dp)),
                    singleLine = true,
                    placeholder = { Text("例如 1100K", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.faint) },
                    colors = profileFieldColors(xc),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = XinCodeFont, fontSize = 13.sp)
                )
                Spacer(Modifier.height(12.dp))
                Text("最大输出 token", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub)
                Text("留空或自动表示不覆盖服务商默认值。", fontFamily = XinUiFont, fontSize = 10.sp, color = xc.faint)
                Spacer(Modifier.height(5.dp))
                TextField(
                    value = outputText,
                    onValueChange = { outputText = it },
                    modifier = Modifier.fillMaxWidth().border(1.dp, xc.border, RoundedCornerShape(12.dp)),
                    singleLine = true,
                    placeholder = { Text("例如 128K", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.faint) },
                    colors = profileFieldColors(xc),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = XinCodeFont, fontSize = 13.sp)
                )
                Spacer(Modifier.height(14.dp))
                Text("思考强度", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub)
                Spacer(Modifier.height(6.dp))
                efforts.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        pair.forEach { effort ->
                            ModelEffortChoice(
                                label = effort,
                                selected = thinkingEffort == effort,
                                modifier = Modifier.weight(1f),
                                onClick = { thinkingEffort = effort }
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("图片输入", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.ink)
                        Text("声明该模型支持视觉内容", fontFamily = XinUiFont, fontSize = 10.sp, color = xc.faint)
                    }
                    Switch(checked = supportsImage, onCheckedChange = { supportsImage = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    ModelProfile(
                        contextWindow = parseTokenBudget(contextText),
                        maxOutputTokens = parseTokenBudget(outputText),
                        thinkingEffort = thinkingEffort,
                        supportsImageInput = supportsImage
                    )
                )
            }) { Text("应用", fontFamily = XinUiFont, color = xc.green) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("放弃修改", fontFamily = XinUiFont, color = xc.sub) } },
        containerColor = xc.bgElevated
    )
}

@Composable
private fun ModelEffortChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val xc = LocalXinColors.current
    Row(
        modifier = modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) xc.activeBg else xc.bg)
            .border(0.8.dp, if (selected) xc.green else xc.border, RoundedCornerShape(10.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (selected) "✓" else "", fontFamily = XinUiFont, fontSize = 11.sp, color = xc.green, modifier = Modifier.width(15.dp))
        Text(label, fontFamily = XinCodeFont, fontSize = 11.sp, color = if (selected) xc.ink else xc.sub)
    }
}

@Composable
private fun profileFieldColors(xc: XinColors) = TextFieldDefaults.colors(
    focusedContainerColor = xc.bg,
    unfocusedContainerColor = xc.bg,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    cursorColor = xc.green,
    focusedTextColor = xc.ink,
    unfocusedTextColor = xc.ink
)

private fun parseTokenBudget(raw: String): Int {
    val value = raw.trim().replace(",", "").lowercase()
    if (value.isBlank() || value == "auto" || value == "自动") return 0
    val multiplier = when {
        value.endsWith("m") -> 1_000_000.0
        value.endsWith("k") -> 1_000.0
        else -> 1.0
    }
    val number = value.trimEnd('k', 'm').toDoubleOrNull() ?: return 0
    return (number * multiplier).toInt().coerceIn(0, 2_000_000)
}

private fun formatTokenBudget(value: Int): String = when {
    value <= 0 -> "auto"
    value % 1_000_000 == 0 -> (value / 1_000_000).toString() + "M"
    value % 1_000 == 0 -> (value / 1_000).toString() + "K"
    else -> value.toString()
}
