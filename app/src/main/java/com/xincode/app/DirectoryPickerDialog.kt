package com.xincode.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import java.io.File

private val PickerMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * 文件系统目录浏览选择器(非 SAF):直接以 java.io.File 浏览真实路径,
 * 因为 root/ProcessBuilder 需要的是真实文件路径,SAF 的 tree Uri 无法直接用。
 *
 * 功能:显示/可编辑当前路径、进入子目录、返回上一级、新建文件夹、选用当前目录。
 * 供【全局工作区设置】与【每个项目的独立工作目录】共用。
 *
 * @param initialPath 初始目录(空则从 /storage/emulated/0 开始)
 * @param onConfirm   选定目录的绝对路径(已去尾部斜杠)
 */
@Composable
fun DirectoryPickerDialog(
    initialPath: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val xc = LocalXinColors.current
    var currentPath by remember { mutableStateOf(sanitizeDir(initialPath.ifBlank { "/storage/emulated/0" })) }
    var showNewFolder by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    val dirs = remember(currentPath) { listSubDirs(currentPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择工作目录", fontFamily = PickerMono, color = xc.ink, fontSize = 15.sp) },
        text = {
            Column {
                Text("AI 的产出/写入默认落在此目录;它仍可读取目录之外的文件。",
                    fontSize = 11.sp, fontFamily = PickerMono, color = xc.sub)
                Spacer(Modifier.height(8.dp))
                // 可直接编辑/粘贴路径
                TextField(
                    value = currentPath,
                    onValueChange = { currentPath = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = PickerMono),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink
                    )
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("↑ 上一级", fontFamily = PickerMono, fontSize = 12.sp, color = xc.sub,
                        modifier = Modifier.clickable {
                            File(currentPath).parentFile?.let { currentPath = it.absolutePath }
                        })
                    Spacer(Modifier.width(20.dp))
                    Text("＋ 新建文件夹", fontFamily = PickerMono, fontSize = 12.sp, color = xc.green,
                        modifier = Modifier.clickable { newFolderName = ""; showNewFolder = true })
                }
                Spacer(Modifier.height(8.dp))
                if (dirs.isEmpty()) {
                    Text("(无子目录,或此处无读取权限)", fontFamily = PickerMono, fontSize = 11.sp, color = xc.faint)
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        for (d in dirs) {
                            Text(
                                "📁 ${d.name}",
                                fontFamily = PickerMono, fontSize = 12.sp, color = xc.ink,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPath = d.absolutePath }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentPath.trim().trimEnd('/').ifBlank { "/" }) }) {
                Text("选用此目录", fontFamily = PickerMono, color = xc.green)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", fontFamily = PickerMono, color = xc.sub) }
        },
        containerColor = xc.bg
    )

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            title = { Text("新建文件夹", fontFamily = PickerMono, color = xc.ink, fontSize = 14.sp) },
            text = {
                TextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    placeholder = { Text("文件夹名", fontSize = 12.sp, fontFamily = PickerMono, color = xc.faint) },
                    textStyle = TextStyle(fontSize = 12.sp, fontFamily = PickerMono),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newFolderName.trim()
                    if (n.isNotEmpty()) {
                        val nd = File(currentPath, n)
                        try { nd.mkdirs() } catch (_: Exception) {}
                        if (nd.isDirectory) currentPath = nd.absolutePath
                    }
                    showNewFolder = false
                }) { Text("创建", fontFamily = PickerMono, color = xc.green) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolder = false }) { Text("取消", fontFamily = PickerMono, color = xc.sub) }
            },
            containerColor = xc.bg
        )
    }
}

/** 把输入规整成一个存在的目录:本身是目录→用它;是文件→用其父;都不成立→回退共享存储根。 */
private fun sanitizeDir(p: String): String {
    return try {
        val f = File(p)
        when {
            f.isDirectory -> f.absolutePath
            f.parentFile?.isDirectory == true -> f.parentFile!!.absolutePath
            else -> "/storage/emulated/0"
        }
    } catch (_: Exception) { "/storage/emulated/0" }
}

/** 列出 [path] 下的子目录(隐藏以 . 开头者),按名排序;无权限/异常→空表。 */
private fun listSubDirs(path: String): List<File> = try {
    File(path).listFiles()
        ?.filter { it.isDirectory && !it.name.startsWith(".") }
        ?.sortedBy { it.name.lowercase() }
        ?: emptyList()
} catch (_: Exception) { emptyList() }
