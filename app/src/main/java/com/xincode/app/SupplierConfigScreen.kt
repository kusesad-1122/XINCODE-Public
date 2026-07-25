package com.xincode.app

import android.util.Base64
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.xincode.data.AppDatabase
import com.xincode.data.ProviderConfigEntity
import com.xincode.provider.OpenAiClient
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- palette ---
private val Bg = Color(0xFFF9F9F6)
private val Ink = Color(0xFF1A1A17)
private val Sub = Color(0xFF86857B)
private val Faint = Color(0xFFB7B6AB)
private val Green = Color(0xFF6E8050)
private val Red = Color(0xFFA8514A)
private val Border = Color(0xFFE6E4DC)

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
    onBack: () -> Unit
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
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsLoading by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }
    var pendingActivateId by remember { mutableStateOf<Long?>(null) }  // warn before activating
    var editingApiKey by remember { mutableStateOf("") }  // decrypted stored key for live fetch during edit

    val selectedSupplier = knownSuppliers.find { it.id == selectedSupplierId } ?: knownSuppliers.last()
    val isCustom = selectedSupplierId == "custom"
    val effectiveBaseUrl = if (isCustom) baseUrl else selectedSupplier.baseUrl

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

    fun confirmActivate() {
        val id = pendingActivateId ?: return
        pendingActivateId = null
        scope.launch {
            withContext(Dispatchers.IO) { configDao.deactivateAll(); configDao.setActive(id) }
            activeId = id; status = "✓ 已切换配置"
        }
    }

    fun deleteConfig(cfg: ProviderConfigEntity) {
        scope.launch {
            withContext(Dispatchers.IO) { configDao.delete(cfg) }
            if (activeId == cfg.id) activeId = null; loadConfigs(); status = "✓ 已删除"
        }
    }

    fun startNew() {
        editingConfig = null; configName = ""; selectedSupplierId = "deepseek"
        baseUrl = ""; apiKey = ""; model = ""; models = emptyList()
        editingApiKey = ""; checkedModelIds = emptySet(); selectedApiPathType = "openai"
        showForm = true; status = ""
    }

    fun startEdit(cfg: ProviderConfigEntity) {
        editingConfig = cfg; configName = cfg.name; selectedSupplierId = cfg.supplierId
        baseUrl = cfg.baseUrl; apiKey = ""; model = cfg.model
        models = emptyList()  // will be fetched live, same as startNew
        checkedModelIds = cfg.enabledModelIds.toSet()
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
            val r = openAiClient.listModels(effectiveBaseUrl, key)
            models = r.getOrDefault(emptyList())
            modelsLoading = false
            if (models.isEmpty()) status = "✗ 拉取失败或无可用模型"
            else { showModelDropdown = true; status = "✓ 已加载 ${models.size} 个模型" }
        }
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
                apiPathType = selectedApiPathType
            )
            if (editingConfig != null) {
                withContext(Dispatchers.IO) { configDao.update(entity) }
            } else {
                val newId = withContext(Dispatchers.IO) { configDao.insert(entity) }
                withContext(Dispatchers.IO) { configDao.deactivateAll(); configDao.setActive(newId) }
                activeId = newId
            }
            showForm = false; loadConfigs()
            status = "✓ 配置已保存"
        }
    }

    LaunchedEffect(Unit) { loadConfigs() }

    Column(Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp)) {
        // Header
        Text("← 返回", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Sub,
            modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("供应商配置", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Ink)
            Text("+ 新建", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { startNew() })
        }
        Spacer(Modifier.height(12.dp))

        // Config list
        if (savedConfigs.isEmpty()) {
            Text("暂无配置，点「+ 新建」创建", fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = Faint, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            savedConfigs.forEach { cfg ->
                val isActive = cfg.id == activeId
                Row(Modifier.fillMaxWidth().background(if (isActive) Color(0xFFF0EFEA) else Bg)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { activateConfig(cfg.id) }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cfg.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            color = if (isActive) Ink else Sub)
                        Text("${knownSuppliers.find{it.id==cfg.supplierId}?.name ?: cfg.supplierId} · ${cfg.model}",
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Faint)
                    }
                    if (isActive) Text("✓", fontSize = 12.sp, color = Green, modifier = Modifier.padding(end = 8.dp))
                    Text("✎", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Sub,
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { startEdit(cfg) }.padding(4.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("✗", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Red,
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { deleteConfig(cfg) }.padding(4.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            }
        }

        // Form
        if (showForm) {
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
            Spacer(Modifier.height(16.dp))

            Text(if (editingConfig != null) "编辑配置" else "新建配置", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Ink)
            Spacer(Modifier.height(12.dp))

            Label("名称")
            TextField(value = configName, onValueChange = { configName = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), textStyle = fieldTextStyle(),
                placeholder = { Text("例如: 我的DeepSeek", color = Faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace) })
            Spacer(Modifier.height(12.dp))

            // Supplier selector
            Label("供应商")
            Box(Modifier.fillMaxWidth().zIndex(10f)) {
                Row(Modifier.fillMaxWidth().border(1.dp, Faint).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedSupplier.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Ink,
                        modifier = Modifier.weight(1f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            showSupplierDropdown = !showSupplierDropdown
                        }.padding(horizontal = 8.dp, vertical = 4.dp))
                    Text("▼", fontSize = 10.sp, color = Faint, modifier = Modifier.padding(end = 8.dp))
                }
                if (showSupplierDropdown) {
                    Column(Modifier.fillMaxWidth().offset(y = 48.dp).background(Bg).border(1.dp, Faint)
                        .padding(vertical = 4.dp).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        knownSuppliers.forEach { sup ->
                            Text(sup.name, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                color = if (sup.id == selectedSupplierId) Ink else Sub,
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (sup.id == selectedSupplierId) Color(0xFFF0EFEA) else Bg)
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        selectedSupplierId = sup.id; showSupplierDropdown = false
                                        model = sup.defaultModel; models = emptyList()
                                        selectedApiPathType = sup.apiPathType
                                        if (sup.id != "custom") baseUrl = sup.baseUrl else baseUrl = ""
                                    }.padding(horizontal = 12.dp, vertical = 7.dp))
                        }
                    }
                }
            }
            if (effectiveBaseUrl.isNotBlank()) {
                val suffixHint = when (selectedApiPathType) {
                    "anthropic" -> "/v1/messages"
                    "openai" -> "/v1/chat/completions"
                    else -> ""
                }
                Text(effectiveBaseUrl + suffixHint, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Faint, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))

            if (isCustom) {
                Label("base_url")
                TextField(value = baseUrl, onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), textStyle = fieldTextStyle(),
                    placeholder = { Text("https://api.xxx.com", color = Faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace) })
                Spacer(Modifier.height(12.dp))

                Label("API 路径类型")
                Box(Modifier.fillMaxWidth().zIndex(9f)) {
                    val apiPathLabel = when (selectedApiPathType) {
                        "openai" -> "OpenAI 兼容 (自动追加 /v1/chat/completions)"
                        "anthropic" -> "Anthropic 兼容 (自动追加 /v1/messages)"
                        else -> "自定义 (完整 URL，不追加)"
                    }
                    Row(Modifier.fillMaxWidth().border(1.dp, Faint).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(apiPathLabel, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Ink,
                            modifier = Modifier.weight(1f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                showApiPathDropdown = !showApiPathDropdown
                            }.padding(horizontal = 8.dp, vertical = 4.dp))
                        Text("▼", fontSize = 10.sp, color = Faint, modifier = Modifier.padding(end = 8.dp))
                    }
                    if (showApiPathDropdown) {
                        Column(Modifier.fillMaxWidth().offset(y = 42.dp).background(Bg).border(1.dp, Faint)
                            .padding(vertical = 4.dp)) {
                            listOf("openai" to "OpenAI 兼容\n自动追加 /v1/chat/completions",
                                   "anthropic" to "Anthropic 兼容\n自动追加 /v1/messages",
                                   "custom" to "自定义\n完整 URL，不追加").forEach { (id, label) ->
                                Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    color = if (id == selectedApiPathType) Ink else Sub,
                                    modifier = Modifier.fillMaxWidth()
                                        .background(if (id == selectedApiPathType) Color(0xFFF0EFEA) else Bg)
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
                placeholder = { Text(if (editingConfig != null && apiKey.isEmpty()) "留空则保留原 Key" else "sk-...", color = Faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace) })
            Spacer(Modifier.height(12.dp))

            Label("启用模型（多选）")
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().border(1.dp, Faint).padding(4.dp).heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    if (modelsLoading) {
                        Text("加载中…", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Faint,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    } else if (models.isEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("点击右侧 ↻ 拉取模型列表", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Faint,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            Text("↻", fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Sub,
                                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fetchModels() }
                                    .padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                    } else {
                        // Refresh header
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("↻ 刷新", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Sub,
                                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { fetchModels() })
                            Text(if (model.isNotEmpty()) "当前: $model" else "未选", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Faint)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
                        // Checkbox list
                        models.forEach { m ->
                            val isChecked = m in checkedModelIds
                            val isUnavailable = editingConfig != null && m in (editingConfig?.enabledModelIds ?: emptyList()) && m !in models
                            Row(Modifier.fillMaxWidth()
                                .background(if (m == model) Color(0xFFF0EFEA) else Bg)
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
                                    Text(m, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                        color = if (m == model) Ink else Sub)
                                    if (isUnavailable) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Outlined.ErrorOutline,
                                                contentDescription = "不可用",
                                                modifier = Modifier.size(12.dp).padding(end = 2.dp),
                                                tint = Red
                                            )
                                            Text("该模型已不可用，将自动取消勾选", fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Red)
                                        }
                                    }
                                }
                                // Active marker
                                if (m == model) Text("←", fontSize = 10.sp, color = Sub, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("取消", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Sub,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showForm = false }.padding(horizontal = 12.dp, vertical = 8.dp))
                Spacer(Modifier.width(12.dp))
                Text("保存配置", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Ink,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { saveConfig() }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            Spacer(Modifier.height(64.dp))  // 底部留白:配合整页可滚动,保存按钮完整露出、不贴屏幕/导航栏底边
        }

        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                color = if (status.contains("✓")) Green else if (status.contains("✗")) Red else Sub)
        }

        // ---- model switch warning dialog ----
        if (pendingActivateId != null) {
            val targetCfg = savedConfigs.find { it.id == pendingActivateId }
            AlertDialog(
                onDismissRequest = { pendingActivateId = null },
                title = { Text("切换模型", fontFamily = FontFamily.Monospace, color = Ink) },
                text = {
                    Text(
                        "将切换到「${targetCfg?.name ?: "新配置"}」(${targetCfg?.model ?: "?"})。\n\n" +
                        "新模型可能无法解析当前对话中的历史工具调用记录，强烈建议开启新会话后再切换。\n\n" +
                        "是否继续切换？",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Ink, lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmActivate() }) {
                        Text("切换", fontFamily = FontFamily.Monospace, color = Red)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingActivateId = null }) {
                        Text("取消", fontFamily = FontFamily.Monospace, color = Sub)
                    }
                },
                containerColor = Bg
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Sub, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Bg, unfocusedContainerColor = Bg,
    focusedIndicatorColor = Ink, unfocusedIndicatorColor = Faint,
    cursorColor = Ink, focusedTextColor = Ink, unfocusedTextColor = Ink
)

@Composable
private fun fieldTextStyle() = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)