package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = XinUiFont

/**
 * 模型委托设置页——【直接从已配置的供应商里选】,不用再手输 base_url/key。
 * 只列出**已填过 API 密钥**的供应商,选其一 + 一个模型即作为该委托任务的副模型
 *(把该供应商的 base_url + 已加密 key + 选中的 model 复制进 aux_<task>_*)。
 */
@Composable
fun AuxModelsScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<List<ProviderConfigEntity>>(emptyList()) }
    // task.key → 当前选中的 "供应商名 / 模型" 展示串
    val selection = remember { mutableStateMapOf<String, String>() }
    var loaded by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 只取【已填过 API 密钥】的供应商;没填过的不理会。
            providers = database.providerConfigDao().getAll().filter { it.apiKeyEnc.isNotBlank() }
            for (t in AuxModels.TASKS) {
                val base = database.settingDao().get("aux_${t.key}_base_url").orEmpty()
                val model = database.settingDao().get("aux_${t.key}_model").orEmpty()
                if (base.isNotBlank() && model.isNotBlank()) {
                    val provName = providers.firstOrNull { it.baseUrl.trimEnd('/') == base.trimEnd('/') }?.name ?: base
                    selection[t.key] = "$provName / $model"
                }
            }
        }
        loaded = true
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp).verticalScroll(rememberScrollState())) {
        XinPageHeader(
            title = "模型委托",
            subtitle = "为视觉、推理、翻译和转写选择副模型",
            onBack = onBack
        )
        Text(
            "未手填端点时,会自动使用「功能模型配置」里给该任务指定的供应商——两者是同一组调用点的两种配置方式。",
            fontSize = 10.sp, fontFamily = JetBrainsMono, color = LocalXinColors.current.yellow, lineHeight = 15.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
        )
        Spacer(Modifier.height(8.dp))

        if (!loaded) { Text("加载中…", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub); return@Column }
        if (providers.isEmpty()) {
            Text("(还没有配置过密钥的供应商。先到「供应商配置」加一个并填 API Key。)",
                fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint)
            return@Column
        }

        for (t in AuxModels.TASKS) {
            var expanded by remember(t.key) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().border(0.5.dp, Border).padding(10.dp)) {
                Row(Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { expanded = !expanded },
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(t.label, fontSize = 13.sp, fontFamily = JetBrainsMono, color = Ink)
                        Text(selection[t.key] ?: "未设置", fontSize = 10.sp, fontFamily = JetBrainsMono,
                            color = if (selection[t.key] != null) Green else Faint)
                    }
                    Text(if (expanded) "▾" else "▸", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint)
                }
                if (expanded) {
                    Spacer(Modifier.height(6.dp))
                    for (p in providers) {
                        val models = (p.enabledModelIds + p.model).filter { it.isNotBlank() }.distinct()
                        if (models.isEmpty()) continue
                        Text(p.name, fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub, modifier = Modifier.padding(top = 4.dp))
                        for (mdl in models) {
                            Text("· $mdl", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Ink,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                // 直接复用该供应商的 base_url + 已加密 key + 选中的 model。
                                                database.settingDao().put("aux_${t.key}_base_url", p.baseUrl.trimEnd('/'))
                                                database.settingDao().put("aux_${t.key}_api_key", p.apiKeyEnc)
                                                database.settingDao().put("aux_${t.key}_model", mdl)
                                            }
                                            selection[t.key] = "${p.name} / $mdl"
                                            savedHint = "${t.label} → ${p.name} / $mdl ✓"
                                            expanded = false
                                        }
                                    }
                                    .padding(start = 8.dp, top = 2.dp, bottom = 2.dp))
                        }
                    }
                    if (selection[t.key] != null) {
                        Text("清除", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Faint,
                            modifier = Modifier.padding(top = 6.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        database.settingDao().put("aux_${t.key}_base_url", "")
                                        database.settingDao().put("aux_${t.key}_api_key", "")
                                        database.settingDao().put("aux_${t.key}_model", "")
                                    }
                                    selection.remove(t.key); savedHint = "${t.label} 已清除"
                                }
                            })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (savedHint.isNotBlank()) Text(savedHint, fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
    }
}
