package com.xincode.app

import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.data.ProviderConfigEntity
import com.xincode.provider.OpenAiClient
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

/**
 * 本对话的供应商/模型选择器。
 *
 * 与「功能模型配置」的区别:这里是**主对话本身**用哪个模型,不是某个内部功能用哪个模型。
 * 选择会写进 sessions.modelProviderConfigId + currentModelId,只影响这一个会话,
 * 下一轮回复立即生效(OpenAiClient 按会话解析覆盖)。
 */
@Composable
fun SessionModelPicker(
    database: AppDatabase,
    keystore: KeystoreProvider,
    openAiClient: OpenAiClient,
    sessionId: Long,
    onClose: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as XincodeApplication
    val configs by database.providerConfigDao().observeAll().collectAsState(initial = emptyList())

    var pickedProvider by remember { mutableStateOf<Long?>(null) }  // null = 跟随全局
    var pickedModel by remember { mutableStateOf("") }              // "" = 该供应商默认模型
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var manualId by remember { mutableStateOf("") }
    var loadedSession by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        val s = withContext(Dispatchers.IO) { database.sessionDao().getById(sessionId) }
        pickedProvider = s?.modelProviderConfigId
        pickedModel = s?.currentModelId?.trim().orEmpty()
        loadedSession = true
    }

    // When following the global active provider, its model list is still selectable. A deleted
    // explicit provider also falls back visually to active instead of leaving a dead picker.
    val cfg = configs.firstOrNull { it.id == pickedProvider }
        ?: configs.firstOrNull { it.isActive }
    val enabled = remember(cfg) {
        runCatching {
            val arr = org.json.JSONArray(cfg?.enabledModelIds ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
    val models = remember(enabled, fetched, cfg) {
        (enabled + fetched + listOfNotNull(cfg?.model?.takeIf { it.isNotBlank() })).distinct()
    }

    fun refresh() {
        val c = cfg ?: return
        val key = runCatching {
            keystore.decrypt(Base64.decode(c.apiKeyEnc, Base64.NO_WRAP))
        }.getOrNull()
        if (key.isNullOrBlank()) { status = "✗ 这个配置没有可用的 API Key"; return }
        loading = true; status = ""
        scope.launch {
            val r = openAiClient.listModels(c.baseUrl, key)
            fetched = r.getOrDefault(emptyList())
            loading = false
            status = if (fetched.isEmpty()) "✗ 拉不到列表,可手填模型 ID" else "✓ 拉到 ${fetched.size} 个"
        }
    }

    if (!loadedSession) return

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("本对话模型", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "只影响当前对话,下一轮回复立即生效;其他会话不受影响。",
                    fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Text("供应商", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)

                Row(Modifier.fillMaxWidth()
                    .background(if (pickedProvider == null) xc.activeBg else xc.bg)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        pickedProvider = null; pickedModel = ""; fetched = emptyList(); status = ""
                    }.padding(6.dp)) {
                    Text("跟随全局活跃配置", fontSize = 11.sp, fontFamily = Mono,
                        color = if (pickedProvider == null) xc.green else xc.sub)
                }
                configs.forEach { c ->
                    Row(Modifier.fillMaxWidth()
                        .background(if (pickedProvider == c.id) xc.activeBg else xc.bg)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            pickedProvider = c.id; pickedModel = ""; fetched = emptyList(); status = ""
                        }.padding(6.dp)) {
                        Text(c.name, fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                    }
                }

                if (cfg != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模型", fontSize = 11.sp, fontFamily = Mono, color = xc.sub,
                            modifier = Modifier.weight(1f))
                        Text(
                            if (loading) "拉取中…" else "刷新列表",
                            fontSize = 10.sp, fontFamily = Mono,
                            color = if (loading) xc.faint else xc.green,
                            modifier = Modifier.clickable(
                                indication = null, interactionSource = remember { MutableInteractionSource() }
                            ) { if (!loading) refresh() }
                        )
                    }
                    if (status.isNotBlank()) {
                        Text(status, fontSize = 9.sp, fontFamily = Mono,
                            color = if (status.startsWith("✓")) xc.green else xc.red)
                    }
                    Row(Modifier.fillMaxWidth()
                        .background(if (pickedModel.isBlank()) xc.activeBg else xc.bg)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            pickedModel = ""
                        }.padding(6.dp)) {
                        Text("用该供应商默认模型", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                    }
                    models.forEach { m ->
                        Row(Modifier.fillMaxWidth()
                            .background(if (pickedModel == m) xc.activeBg else xc.bg)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                pickedModel = m
                            }.padding(6.dp)) {
                            Text(m, fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("拉不到就手填模型 ID", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            TextField(
                                value = manualId, onValueChange = { manualId = it }, singleLine = true,
                                placeholder = { Text("例如 deepseek-chat", fontSize = 11.sp, fontFamily = Mono, color = xc.faint) },
                                modifier = Modifier.fillMaxWidth().border(0.5.dp, xc.border, RoundedCornerShape(4.dp)),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink,
                                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                                ),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = Mono)
                            )
                        }
                        Text("加入", fontSize = 11.sp, fontFamily = Mono, color = xc.green,
                            modifier = Modifier
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    val id = manualId.trim()
                                    if (id.isNotBlank()) {
                                        if (id !in models) fetched = fetched + id
                                        pickedModel = id
                                        manualId = ""
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val providerId = pickedProvider?.takeIf { id -> configs.any { it.id == id } }
                app.switchSessionModel(sessionId, providerId, pickedModel)
                onClose()
            }) { Text("保存", fontFamily = Mono, color = xc.green) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    app.clearSessionModelOverride(sessionId)
                    onClose()
                }) { Text("清除覆盖", fontFamily = Mono, color = xc.red) }
                TextButton(onClick = onClose) { Text("取消", fontFamily = Mono, color = xc.sub) }
            }
        },
        containerColor = xc.bg
    )
}
