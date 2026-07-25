package com.xincode.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「发现新版本」弹窗:展示版本号 + Release 说明,并提供下载跳转。
 * 只在 [UpdateChecker.check] 返回非 null 时显示;用户可「稍后」或「跳过此版本」。
 */
@Composable
fun UpdateDialog(
    info: UpdateChecker.UpdateInfo,
    currentVersion: String,
    onDismiss: () -> Unit,
    onSkip: () -> Unit
) {
    val xc = LocalXinColors.current
    val ctx = LocalContext.current

    fun open(url: String) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = xc.bg,
        title = {
            Text(
                "发现新版本 ${info.version}",
                fontFamily = XinFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = xc.ink
            )
        },
        text = {
            // 更新说明可能很长,限高并允许滚动,避免顶掉按钮(与输入栏那次溢出同类问题)。
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "当前版本 ${currentVersion.ifBlank { "未知" }}",
                    fontFamily = XinFont, fontSize = 11.sp, color = xc.sub
                )
                if (info.notes.isNotBlank()) {
                    Text(
                        "\n${info.notes}",
                        fontFamily = XinFont, fontSize = 12.sp, color = xc.ink, lineHeight = 18.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // 有直链 APK 就直接下载,否则打开 Release 页面。
                open(info.apkUrl ?: info.pageUrl)
                onDismiss()
            }) {
                Text(if (info.apkUrl != null) "下载更新" else "前往下载", fontFamily = XinFont, color = xc.green)
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDismiss) {
                    Text("稍后", fontFamily = XinFont, color = xc.sub)
                }
                TextButton(onClick = onSkip) {
                    Text("跳过此版本", fontFamily = XinFont, color = xc.faint, fontSize = 12.sp)
                }
            }
        }
    )
}
