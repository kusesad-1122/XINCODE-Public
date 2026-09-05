package com.xincode.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 本会话产出的一个文件(由写文件/编辑/下载/生图类工具产生)。 */
data class SessionArtifact(
    val name: String,
    val path: String,
    /** 展示分类键:MD / IMG / CODE / FILE */
    val kindKey: String
)

/**
 * 从会话消息里收集「产出文件」:扫描工具调用记录里的 file_write / file_edit /
 * multi_edit / download_file 的 path 参数,以及 generate_image 的路径标记。
 * 不扫磁盘,只认真实发生过的工具调用,按路径去重、按发生顺序排列。
 */
fun collectSessionArtifacts(messages: List<ChatState.MessageUi>): List<SessionArtifact> {
    val seen = LinkedHashMap<String, SessionArtifact>()
    for (m in messages) {
        val block = m.contentBlock as? MessageContent.ToolCall ?: continue
        when (block.toolName) {
            "file_write", "file_edit", "multi_edit", "download_file" -> {
                val path = try {
                    org.json.JSONObject(block.fullParams).optString("path")
                } catch (_: Exception) {
                    ""
                }
                if (path.isNotBlank()) {
                    seen[path] = SessionArtifact(path.substringAfterLast('/'), path, artifactKindKey(path))
                }
            }
            "generate_image" -> {
                // stdout 带「### 名称(图片,路径:xxx)」标记,从中抽路径
                val path = Regex("路径[:：]\\s*([^,，)）\\s]+)").find(block.stdout)?.groupValues?.get(1).orEmpty()
                if (path.isNotBlank()) {
                    seen[path] = SessionArtifact(path.substringAfterLast('/'), path, "IMG")
                }
            }
        }
    }
    return seen.values.toList()
}

private fun artifactKindKey(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "md", "markdown" -> "MD"
        "png", "jpg", "jpeg", "webp", "gif" -> "IMG"
        "txt", "json", "xml", "yaml", "yml", "kt", "java", "py", "js", "ts",
        "html", "css", "gradle", "kts", "sh", "toml" -> "CODE"
        else -> "FILE"
    }
}

/** 「对话产出」底部抽屉:仿 Claude 的 Artifacts,列出本次对话生成过的文档/文件。 */
@Composable
fun ArtifactsSheet(artifacts: List<SessionArtifact>, onClose: () -> Unit) {
    val context = LocalContext.current
    val xc = LocalXinColors.current
    val fmtCopied = t("已复制路径")
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("对话产出"),
                fontSize = 16.sp, lineHeight = 21.sp,
                fontFamily = XinSerifFont, fontWeight = FontWeight.SemiBold,
                color = xc.ink, modifier = Modifier.weight(1f)
            )
            Text(
                t("关闭"), fontSize = 12.sp, fontFamily = XinUiFont, color = xc.sub,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onClose() }.padding(4.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            t("本会话由写文件/下载/生图产生的文档都在这里,点按条目复制路径"),
            fontSize = 11.sp, fontFamily = XinUiFont, color = xc.faint
        )
        Spacer(Modifier.height(12.dp))

        if (artifacts.isEmpty()) {
            Text(
                t("还没有产出文件"),
                fontSize = 12.sp, fontFamily = XinUiFont, color = xc.faint,
                modifier = Modifier.padding(vertical = 20.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                artifacts.forEach { a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(xc.bg)
                            .border(0.8.dp, xc.border, RoundedCornerShape(14.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                    ?.setPrimaryClip(ClipData.newPlainText("path", a.path))
                                Toast.makeText(context, fmtCopied, Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(34.dp).background(xc.activeBg, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(17.dp), tint = xc.green)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.name, fontSize = 13.sp, fontFamily = XinCodeFont, color = xc.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                a.path, fontSize = 10.sp, fontFamily = XinCodeFont, color = xc.faint,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (a.kindKey) {
                                "MD" -> "MD"
                                "IMG" -> t("图片")
                                "CODE" -> t("代码")
                                else -> t("文件")
                            },
                            fontSize = 10.sp, fontFamily = XinUiFont, color = xc.sub
                        )
                    }
                }
            }
        }
    }
}
