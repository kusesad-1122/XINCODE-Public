package com.xincode.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

private val Mono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/** 供应商预置。free=true 归入「免费模型」区(自己注册领 key,每日有额度/限速);否则「付费供应商」区。 */
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
    val contextWindow: Int = 0
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
        ProviderPreset("Moonshot Kimi", "moonshot", "https://api.moonshot.cn/v1",
            "moonshot-v1-8k", listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k"),
            "https://platform.moonshot.cn/", "月之暗面 Kimi,按量付费", free = false, contextWindow = 128000)
    )
}

/** 「免费模型 / 供应商市场」:分免费/付费两区,每个供应商带官网入口 + 一键添加(填 key)。 */
@Composable
fun ModelMarketScreen(database: AppDatabase, keystore: KeystoreProvider, onBack: () -> Unit) {
    val xc = LocalXinColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyDialogFor by remember { mutableStateOf<ProviderPreset?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun openSite(url: String) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text("免费模型 / 供应商", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
            Spacer(Modifier.weight(1f))
        }
        toast?.let {
            Text(it, fontSize = 11.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionLabel("免费 / 免费额度(自己注册领 key,每日有额度或限速)", xc) }
            items(ProviderPresets.ALL.filter { it.free }.size) { i ->
                val p = ProviderPresets.ALL.filter { it.free }[i]
                PresetCard(p, xc, onSite = { openSite(p.site) }, onAdd = { keyDialogFor = p })
            }
            item { Spacer(Modifier.height(6.dp)); SectionLabel("付费供应商(购买 API 额度 / 订阅 coding plan)", xc) }
            items(ProviderPresets.ALL.filter { !it.free }.size) { i ->
                val p = ProviderPresets.ALL.filter { !it.free }[i]
                PresetCard(p, xc, onSite = { openSite(p.site) }, onAdd = { keyDialogFor = p })
            }
        }
    }

    // 填 key 对话框
    keyDialogFor?.let { p ->
        var key by remember(p) { mutableStateOf("") }
        var model by remember(p) { mutableStateOf(p.defaultModel) }
        AlertDialog(
            onDismissRequest = { keyDialogFor = null },
            title = { Text("添加 ${p.name}", fontSize = 14.sp, fontFamily = Mono, color = xc.ink) },
            text = {
                Column {
                    Text(p.note, fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                    Spacer(Modifier.height(8.dp))
                    Text("官网注册领取 API Key ›", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { openSite(p.site) })
                    Spacer(Modifier.height(10.dp))
                    TField(key, { key = it }, "粘贴 API Key", xc)
                    Spacer(Modifier.height(8.dp))
                    TField(model, { model = it }, "模型 ID(可改)", xc)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val k = key.trim(); val m = model.trim()
                    if (k.isBlank() || m.isBlank()) { toast = "请填 key 和模型"; return@TextButton }
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val enc = android.util.Base64.encodeToString(keystore.encrypt(k), android.util.Base64.NO_WRAP)
                            database.providerConfigDao().deactivateAll()
                            val id = database.providerConfigDao().insert(
                                ProviderConfigEntity(
                                    name = p.name, supplierId = p.supplierId, baseUrl = p.baseUrl,
                                    apiKeyEnc = enc, model = m,
                                    enabledModelIds = (p.models + m).distinct(),
                                    isActive = true, apiPathType = p.apiPathType, contextWindow = p.contextWindow
                                )
                            )
                            database.providerConfigDao().setActive(id)
                        }
                        toast = "已添加并启用 ${p.name}"
                        keyDialogFor = null
                    }
                }) { Text("添加并启用", fontFamily = Mono, color = xc.green) }
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
            val (badge, bc) = if (p.free) "免费" to xc.green else "付费" to Color(0xFFF2C14E)
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
