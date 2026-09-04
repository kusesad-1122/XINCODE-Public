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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.provider.OpenAiClient
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bottom-sheet model picker for the current conversation. */
@OptIn(ExperimentalMaterial3Api::class)
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

    var pickedProvider by remember { mutableStateOf<Long?>(null) }
    var pickedModel by remember { mutableStateOf("") }
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var manualId by remember { mutableStateOf("") }
    var loadedSession by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        val session = withContext(Dispatchers.IO) { database.sessionDao().getById(sessionId) }
        pickedProvider = session?.modelProviderConfigId
        pickedModel = session?.currentModelId?.trim().orEmpty()
        loadedSession = true
    }

    val cfg = configs.firstOrNull { it.id == pickedProvider }
        ?: configs.firstOrNull { it.isActive }
    val configuredModels = cfg?.enabledModelIds.orEmpty()
    val models = (configuredModels + fetched + listOfNotNull(cfg?.model?.takeIf { it.isNotBlank() })).distinct()

    fun refreshModels() {
        val current = cfg ?: return
        val key = runCatching { keystore.decrypt(Base64.decode(current.apiKeyEnc, Base64.NO_WRAP)) }.getOrNull()
        if (key.isNullOrBlank()) {
            status = "✗ 当前配置没有可用的 API Key"
            return
        }
        loading = true
        status = ""
        scope.launch {
            val result = openAiClient.listModels(current.baseUrl, key)
            fetched = result.getOrDefault(emptyList())
            loading = false
            status = if (fetched.isEmpty()) "✗ 拉不到模型列表，可手填模型 ID" else "✓ 已加载 ${fetched.size} 个模型"
        }
    }

    if (!loadedSession) return

    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = xc.bg,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = {
            Box(Modifier.width(36.dp).height(4.dp).background(xc.faint.copy(alpha = 0.65f), RoundedCornerShape(2.dp)))
        }
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.86f).navigationBarsPadding()
                .padding(horizontal = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择模型", fontFamily = XinSerifFont, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, color = xc.ink)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = xc.ink)
                }
            }
            Text("Select model · 只影响当前会话，下一轮回复立即生效", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub)
            Spacer(Modifier.height(16.dp))

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("供应商", fontFamily = XinUiFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = xc.sub)
                Spacer(Modifier.height(6.dp))
                ModelPickerRow(
                    title = "跟随全局活跃配置",
                    subtitle = configs.firstOrNull { it.isActive }?.name ?: "尚未配置供应商",
                    selected = pickedProvider == null,
                    onClick = { pickedProvider = null; pickedModel = ""; fetched = emptyList(); status = "" }
                )
                configs.forEach { provider ->
                    ModelPickerRow(
                        title = provider.name,
                        subtitle = provider.baseUrl,
                        selected = pickedProvider == provider.id,
                        onClick = { pickedProvider = provider.id; pickedModel = ""; fetched = emptyList(); status = "" }
                    )
                }

                if (cfg != null) {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("模型", fontFamily = XinUiFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = xc.sub)
                            Text(cfg.name, fontFamily = XinUiFont, fontSize = 11.sp, color = xc.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = { if (!loading) refreshModels() }, enabled = !loading) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新模型", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (loading) "拉取中" else "刷新列表", fontFamily = XinUiFont)
                        }
                    }
                    if (status.isNotBlank()) {
                        Text(status, fontFamily = XinUiFont, fontSize = 11.sp, color = if (status.startsWith("✓")) xc.green else xc.red)
                    }
                    Spacer(Modifier.height(4.dp))
                    ModelPickerRow(
                        title = "使用供应商默认模型",
                        subtitle = cfg.model.ifBlank { "未设置默认模型" },
                        selected = pickedModel.isBlank(),
                        onClick = { pickedModel = "" }
                    )
                    models.forEach { modelId ->
                        ModelPickerRow(
                            title = modelId,
                            subtitle = if (modelId == cfg.model) "供应商默认模型" else "可用模型",
                            selected = pickedModel == modelId,
                            onClick = { pickedModel = modelId }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("没有列表时手动添加", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = manualId,
                            onValueChange = { manualId = it },
                            modifier = Modifier.weight(1f).border(1.dp, xc.border, RoundedCornerShape(12.dp)),
                            singleLine = true,
                            placeholder = { Text("例如 deepseek-chat", fontFamily = XinUiFont, fontSize = 12.sp, color = xc.faint) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = xc.bgElevated,
                                unfocusedContainerColor = xc.bgElevated,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = xc.ink,
                                unfocusedTextColor = xc.ink,
                                cursorColor = xc.green
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = XinUiFont, fontSize = 13.sp)
                        )
                        TextButton(onClick = {
                            val id = manualId.trim()
                            if (id.isNotBlank()) {
                                if (id !in models) fetched = fetched + id
                                pickedModel = id
                                manualId = ""
                            }
                        }) { Text("添加", fontFamily = XinUiFont, color = xc.green) }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { app.clearSessionModelOverride(sessionId); onClose() }) {
                    Text("清除覆盖", fontFamily = XinUiFont, color = xc.red)
                }
                TextButton(onClick = onClose) { Text("取消", fontFamily = XinUiFont, color = xc.sub) }
                TextButton(onClick = {
                    val providerId = pickedProvider?.takeIf { id -> configs.any { it.id == id } }
                    app.switchSessionModel(sessionId, providerId, pickedModel)
                    onClose()
                }) { Text("保存", fontFamily = XinUiFont, fontWeight = FontWeight.Medium, color = xc.green) }
            }
        }
    }
}

@Composable
private fun ModelPickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val xc = LocalXinColors.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) xc.activeBg else Color.Transparent)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = XinUiFont, fontSize = 14.sp, color = xc.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, fontFamily = XinUiFont, fontSize = 11.sp, color = xc.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Text("✓", fontFamily = XinUiFont, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = xc.green)
    }
}
