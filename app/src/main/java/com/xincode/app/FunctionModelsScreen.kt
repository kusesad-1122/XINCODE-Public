package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.data.ProviderConfigEntity
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

/**
 * 【功能模型配置】页:给应用内部每个模型调用点单独指定「用哪套供应商配置 + 哪个模型」。
 *
 * 清单来自 [FunctionModels.FEATURES],每一项都对应代码里真实存在的调用点。
 * 未指定的功能沿用当前活跃配置,所以这一页可以完全不管也不影响使用。
 */
@Composable
fun FunctionModelsScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()

    var configs by remember { mutableStateOf<List<ProviderConfigEntity>>(emptyList()) }
    var assignments by remember { mutableStateOf<Map<String, FunctionModels.Assignment>>(emptyMap()) }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    var testResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun reload() {
        scope.launch {
            configs = withContext(Dispatchers.IO) { database.providerConfigDao().getAll() }
            assignments = withContext(Dispatchers.IO) {
                FunctionModels.FEATURES.associate { it.key to FunctionModels.get(database, it.key) }
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    val activeConfig = configs.firstOrNull { it.isActive }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "功能模型配置",
            subtitle = "为不同内部任务分别指定模型",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {

            Text(
                "为不同功能分别指定模型。未指定的沿用当前主模型,可以完全不管。",
                fontSize = 11.sp, fontFamily = Mono, color = xc.sub, lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "常见用法:上下文总结、后台复盘、Goal 裁判用便宜模型;子智能体用够强且支持工具调用的模型。",
                fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 15.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                "说明:「图像识别 / 深度推理 / 翻译 / 语音转写」与「模型委托」配置的是同一组调用点——" +
                    "模型委托里手填了自定义端点时以委托端点为优先,否则用这里指定的供应商。",
                fontSize = 10.sp, fontFamily = Mono, color = xc.yellow, lineHeight = 15.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            if (configs.isEmpty()) {
                Text("还没有任何供应商配置,请先去「供应商配置」添加。",
                    fontSize = 12.sp, fontFamily = Mono, color = xc.red,
                    modifier = Modifier.padding(vertical = 20.dp))
            }

            FunctionModels.FEATURES.forEach { feature ->
                val a = assignments[feature.key] ?: FunctionModels.Assignment(0L, "")
                val boundConfig = if (a.configId > 0) configs.find { it.id == a.configId } else null
                // 没指定就是跟随主模型,校验能力时也要按主模型算
                val effective = boundConfig ?: activeConfig
                val missing = FunctionModels.capabilityMissing(feature, effective)
                val expanded = expandedKey == feature.key

                Column(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).padding(14.dp)
                ) {
                    Text(feature.label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        fontFamily = Mono, color = xc.ink)
                    Text(feature.hint, fontSize = 10.sp, fontFamily = Mono, color = xc.sub,
                        lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))

                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(xc.activeBg).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "当前配置: " + (boundConfig?.name ?: "跟随主模型"),
                                fontSize = 11.sp, fontFamily = Mono, color = xc.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "模型: " + a.model.ifBlank { effective?.model ?: "未配置" },
                                fontSize = 10.sp, fontFamily = Mono, color = xc.sub,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            if (missing && feature.needs != null) {
                                Row(
                                    Modifier.padding(top = 4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null,
                                        modifier = Modifier.size(12.dp).padding(end = 3.dp), tint = xc.red)
                                    Text(
                                        "所选配置未声明「${feature.needs.label}」,该功能可能不可用。" +
                                            "可在供应商配置里开启对应开关。",
                                        fontSize = 9.sp, fontFamily = Mono, color = xc.red, lineHeight = 13.sp
                                    )
                                }
                            }
                        }
                        Text(
                            if (testing == feature.key) "测试中" else "测试",
                            fontSize = 11.sp, fontFamily = Mono,
                            color = if (testing == feature.key) xc.faint else xc.green,
                            modifier = Modifier
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    if (testing != null) return@clickable
                                    testing = feature.key
                                    scope.launch {
                                        val r = runFunctionTest(database, keystore, feature.key)
                                        testResults = testResults + (feature.key to r)
                                        testing = null
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                        Text(
                            if (expanded) "▾" else "▸",
                            fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                            modifier = Modifier
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    expandedKey = if (expanded) null else feature.key
                                }
                                .padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
                        )
                    }

                    testResults[feature.key]?.let { r ->
                        Text(
                            r, fontSize = 10.sp, fontFamily = Mono, lineHeight = 14.sp,
                            color = if (r.startsWith("✓")) xc.green else xc.red,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }

                    if (expanded) {
                        Spacer(Modifier.height(10.dp))
                        Text("选择供应商配置", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                        Spacer(Modifier.height(4.dp))
                        Column(Modifier.fillMaxWidth().border(1.dp, xc.border, RoundedCornerShape(6.dp))) {
                            PickRow("跟随主模型", a.configId == 0L, xc) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { FunctionModels.clear(database, feature.key) }
                                    reload()
                                }
                            }
                            configs.forEach { c ->
                                PickRow("${c.name}  (${c.model})", a.configId == c.id, xc) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            // 换配置时把模型一起重置成该配置的默认模型,
                                            // 否则会留着上一个供应商的模型 ID —— 换了家却发着别家的模型名。
                                            FunctionModels.set(database, feature.key, c.id, c.model)
                                        }
                                        reload()
                                    }
                                }
                            }
                        }

                        // 选了具体配置才给挑模型:跟随主模型时模型由主配置决定
                        if (a.configId > 0) {
                            val bound = configs.find { it.id == a.configId }
                            val modelChoices = ((bound?.enabledModelIds ?: emptyList()) +
                                listOfNotNull(bound?.model)).filter { it.isNotBlank() }.distinct()
                            if (modelChoices.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Text("选择模型", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                                Spacer(Modifier.height(4.dp))
                                Column(
                                    Modifier.fillMaxWidth().border(1.dp, xc.border, RoundedCornerShape(6.dp))
                                        .heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                                ) {
                                    modelChoices.forEach { m ->
                                        PickRow(m, a.model == m, xc) {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    FunctionModels.set(database, feature.key, a.configId, m)
                                                }
                                                reload()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun PickRow(label: String, selected: Boolean, xc: XinColors, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp, fontFamily = Mono,
        color = if (selected) xc.ink else xc.sub,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) xc.activeBg else xc.bg)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp)
    )
}

/**
 * 用该功能实际会用到的那套配置发一次最小请求,确认真能通。
 *
 * 刻意走 `OpenAiClient(functionKey = key)` 而不是自己拼 HTTP —— 只有这样测的才是
 * 这个功能真正会走的那条路径(含端点拼接、自定义请求头、apiPathType 分支)。
 * 自己拼一遍的话,测试通过不代表功能能用。
 */
private suspend fun runFunctionTest(
    database: AppDatabase,
    keystore: KeystoreProvider,
    key: String
): String = withContext(Dispatchers.IO) {
    try {
        val cfg = FunctionModels.resolveConfig(database, key)
            ?: return@withContext "✗ 没有可用的供应商配置"
        val client = com.xincode.provider.OpenAiClient(database, keystore, functionKey = key)
        val r = client.chat("请只回复两个字:可用")
        r.fold(
            onSuccess = { text ->
                if (text.isBlank()) "✗ 连通但返回为空(模型可能不支持该调用方式)"
                else "✓ 可用 · ${cfg.name} · ${text.trim().take(30)}"
            },
            onFailure = { e -> "✗ ${e.message?.take(160) ?: "调用失败"}" }
        )
    } catch (t: Throwable) {
        "✗ ${t::class.java.simpleName}: ${t.message?.take(140)}"
    }
}
