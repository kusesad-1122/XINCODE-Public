package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.xincode.app.R
import com.xincode.data.McpServerEntity
import kotlinx.coroutines.launch

// --- palette ---
private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = XinUiFont

/**
 * MCP Server management screen.
 * Lists existing servers, allows adding/removing/connecting/disconnecting.
 */
@Composable
fun McpServerScreen(
    mcpManager: McpManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf(listOf<McpServerEntity>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            servers = mcpManager.getAllServers()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        XinPageHeader(title = "MCP 服务器", subtitle = "连接和管理外部工具", onBack = onBack) {
            XinHeaderAction(label = "添加", onClick = { showAddDialog = true })
        }
        Spacer(Modifier.height(12.dp))

        // Status message
        if (statusMessage.isNotBlank()) {
            Text(statusMessage, fontSize = 11.sp, fontFamily = JetBrainsMono,
                color = if (isError) Red else Green,
                modifier = Modifier.padding(bottom = 8.dp))
        }

        // Server list
        if (servers.isEmpty()) {
            Text("尚未添加 MCP 服务器", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint,
                modifier = Modifier.padding(vertical = 16.dp))
        }

        for (server in servers) {
            Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(server.name, fontSize = 13.sp, fontFamily = JetBrainsMono, color = Ink)
                        val statusText = if (server.connected) "● 已连接" else "○ 未连接"
                        Text(statusText, fontSize = 11.sp, fontFamily = JetBrainsMono,
                            color = if (server.connected) Green else Faint)
                    }
                    Text(server.url, fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
                    if (server.connected && server.toolNames.isNotBlank()) {
                        val toolCount = server.toolNames.split(",").size
                        Text("$toolCount 个工具: ${server.toolNames.take(60)}${if (server.toolNames.length > 60) "..." else ""}",
                            fontSize = 11.sp, fontFamily = JetBrainsMono, color = Faint)
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (server.connected) {
                            Text("断开", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Red,
                                modifier = Modifier.clickable(indication = null,
                                    interactionSource = remember { MutableInteractionSource() }) {
                                    scope.launch {
                                        mcpManager.disconnectServer(server.url)
                                        statusMessage = "已断开: ${server.name}"
                                        isError = false
                                        refresh()
                                    }
                                })
                        } else {
                            Text("连接", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Green,
                                modifier = Modifier.clickable(indication = null,
                                    interactionSource = remember { MutableInteractionSource() }) {
                                    scope.launch {
                                        statusMessage = "连接中..."
                                        isError = false
                                        when (val result = mcpManager.connectServer(server.name, server.url, server.authHeader)) {
                                            is McpConnectResult.Success -> {
                                                statusMessage = "连接成功: ${result.serverName} (${result.toolCount} 个工具)"
                                                isError = false
                                            }
                                            is McpConnectResult.Error -> {
                                                statusMessage = "连接失败: ${result.message}"
                                                isError = true
                                            }
                                        }
                                        refresh()
                                    }
                                })
                        }
                        Text("删除", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Faint,
                            modifier = Modifier.clickable(indication = null,
                                interactionSource = remember { MutableInteractionSource() }) {
                                scope.launch {
                                    mcpManager.deleteServer(server.url)
                                    statusMessage = "已删除: ${server.name}"
                                    isError = false
                                    refresh()
                                }
                            })
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
        }
    }

    // Add server dialog
    if (showAddDialog) {
        McpAddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url, auth ->
                showAddDialog = false
                scope.launch {
                    statusMessage = "连接中..."
                    isError = false
                    when (val result = mcpManager.connectServer(name, url, auth)) {
                        is McpConnectResult.Success -> {
                            statusMessage = "连接成功: ${result.serverName} (${result.toolCount} 个工具)"
                            isError = false
                        }
                        is McpConnectResult.Error -> {
                            // Still save the entry even if connection fails
                            statusMessage = "连接失败: ${result.message}"
                            isError = true
                        }
                    }
                    refresh()
                }
            }
        )
    }
}

@Composable
private fun McpAddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, url: String, auth: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var auth by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(Bg).padding(16.dp)
        ) {
            Text("添加 MCP 服务器", fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
            Spacer(Modifier.height(12.dp))

            Text("名称", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
            androidx.compose.material3.TextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("示例: GitHub MCP", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono)
            )
            Spacer(Modifier.height(8.dp))

            Text("URL", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
            androidx.compose.material3.TextField(
                value = url, onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://localhost:3000/mcp", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono)
            )
            Spacer(Modifier.height(8.dp))

            Text("Auth Header (可选)", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub)
            androidx.compose.material3.TextField(
                value = auth, onValueChange = { auth = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Bearer xxx", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Faint) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono)
            )
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("取消", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) { onDismiss() })
                Spacer(Modifier.width(16.dp))
                Text("添加", fontSize = 12.sp, fontFamily = JetBrainsMono, color = if (name.isNotBlank() && url.isNotBlank()) Ink else Faint,
                    modifier = Modifier.clickable(indication = null,
                        interactionSource = remember { MutableInteractionSource() }) {
                        if (name.isNotBlank() && url.isNotBlank()) onAdd(name, url, auth)
                    })
            }
        }
    }
}
