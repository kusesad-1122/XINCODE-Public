package com.xincode.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val REPO_URL = "https://github.com/kusesad-1122/XINCODE-Public"

/**
 * 「关于」独立页:应用图标 + 名称 + 版本,以及检查更新、项目地址、Star、更新日志、开源许可、开发者。
 * 版本行可点击 → 主动检查更新(与启动时的静默检查复用同一套 [UpdateChecker])。
 */
@Composable
fun AboutScreen(app: XincodeApplication, onBack: () -> Unit) {
    val xc = LocalXinColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val version = remember { UpdateChecker.currentVersion(ctx) }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf("") }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }

    fun open(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
    }

    fun checkUpdate() {
        if (checking) return
        checking = true; checkResult = "正在检查…"
        scope.launch {
            // force=true:跳过 6 小时节流,用户主动点就必须真查一次。
            val info = UpdateChecker.check(
                context = ctx,
                settingGet = { k -> app.database.settingDao().get(k) },
                settingPut = { k, v -> app.database.settingDao().put(k, v) },
                force = true
            )
            checking = false
            if (info != null) {
                updateInfo = info
                checkResult = "发现新版本 ${info.version}"
            } else {
                checkResult = "已是最新版本"
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(xc.bg).verticalScroll(rememberScrollState())
    ) {
        // 顶栏
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("← 返回", fontSize = 12.sp, fontFamily = XinFont, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text("关于", fontSize = 14.sp, fontFamily = XinFont, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(40.dp))   // 与左侧返回等宽,保证标题居中
        }

        // 图标 + 名称 + 版本
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "XINCODE",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp).clip(CircleShape)
            )
            Spacer(Modifier.height(12.dp))
            Text("XINCODE", fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = XinFont, color = xc.ink)
            Spacer(Modifier.height(4.dp))
            Text("版本 ${version.ifBlank { "未知" }}", fontSize = 12.sp, fontFamily = XinFont, color = xc.sub)
            Spacer(Modifier.height(6.dp))
            Text("纯 Kotlin 原生 Android AI Agent", fontSize = 11.sp, fontFamily = XinFont, color = xc.faint)
        }

        // 卡片一:检查更新
        AboutCard(xc) {
            AboutRow(
                title = if (checking) "检查中…" else "检查更新",
                subtitle = checkResult.ifBlank { "当前版本 ${version.ifBlank { "未知" }}" },
                xc = xc,
                onClick = { checkUpdate() }
            )
        }

        // 卡片二:项目相关
        AboutCard(xc) {
            AboutRow("项目地址", REPO_URL, xc) { open(REPO_URL) }
            AboutDivider(xc)
            AboutRow("在 GitHub 点个 Star", "支持一下开发", xc) { open(REPO_URL) }
            AboutDivider(xc)
            AboutRow("更新日志", "查看历史版本更新内容", xc) { open("$REPO_URL/releases") }
            AboutDivider(xc)
            AboutRow("开源许可声明", "GPL-3.0 与第三方素材许可", xc) { open("$REPO_URL/blob/main/THIRD-PARTY-NOTICES.md") }
        }

        // 卡片三:反馈与开发者
        AboutCard(xc) {
            AboutRow("问题反馈", "提交 Issue", xc) { open("$REPO_URL/issues") }
            AboutDivider(xc)
            AboutRow("开发者", "kusesad-1122", xc) { open("https://github.com/kusesad-1122") }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "© 2026 XINCODE · 开源免费,遵循 GPL-3.0",
            fontSize = 10.sp, fontFamily = XinFont, color = xc.faint,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }

    // 主动检查发现新版 → 复用启动时的同一个更新弹窗
    updateInfo?.let { info ->
        UpdateDialog(
            info = info,
            currentVersion = version,
            onDismiss = { updateInfo = null },
            onSkip = {
                scope.launch {
                    UpdateChecker.skipVersion(info.version) { k, v -> app.database.settingDao().put(k, v) }
                }
                updateInfo = null
            }
        )
    }
}

@Composable
private fun AboutCard(xc: XinColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(xc.bgElevated, RoundedCornerShape(12.dp)),
        content = content
    )
}

@Composable
private fun AboutRow(title: String, subtitle: String, xc: XinColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontFamily = XinFont, color = xc.ink)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 10.sp, fontFamily = XinFont, color = xc.sub, maxLines = 2)
            }
        }
        Text("›", fontSize = 16.sp, fontFamily = XinFont, color = xc.faint)
    }
}

@Composable
private fun AboutDivider(xc: XinColors) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(0.5.dp).background(xc.divider))
}
