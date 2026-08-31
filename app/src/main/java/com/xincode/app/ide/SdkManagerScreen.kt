package com.xincode.app.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.LinuxEnvironment
import com.xincode.app.LocalXinColors
import com.xincode.app.XinHeaderAction
import com.xincode.app.XinPageHeader
import com.xincode.app.XinUiFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

@Composable
fun SdkManagerScreen(onBack: () -> Unit, onOpenTerminal: () -> Unit = {}) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyPkg by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("加载中...") }
    var ready by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val isReady = LinuxEnvironment.isReady()
            val isInstalled = if (isReady) SdkManager.isInstalled() else false
            val inst = if (isInstalled) SdkManager.listInstalled() else emptyList()
            val rootInfo = if (isReady) SdkManager.sdkRootInfo() else "Linux 环境未就绪"
            withContext(Dispatchers.Main) {
                ready = isReady && isInstalled
                installed = inst
                info = rootInfo
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "SDK & NDK 管理器", subtitle = "通过终端 sdkmanager 管理 · 与环境变量联动", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            XinHeaderAction(label = "刷新", onClick = { loading=true; reload() })
            XinHeaderAction(label = "终端", onClick = onOpenTerminal)
        }

        if (!LinuxEnvironment.isReady()) {
            Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text("Linux 环境未部署。请先到 设置→环境配置 部署 Ubuntu，再回来安装 SDK/NDK。", fontSize = 12.sp, fontFamily = Mono, color = xc.sub)
            }
            return@Column
        }

        // 根信息卡
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).border(1.dp, Color(0xFF20232E), RoundedCornerShape(12.dp)).padding(12.dp)) {
            Column {
                Text("SDK Root: ${SdkManager.SDK_ROOT}  ${if (ready) "✓ 已安装" else "未安装"}", fontSize = 11.sp, fontFamily = Mono, color = if(ready) Color(0xFF7BE0A4) else xc.faint)
                Spacer(Modifier.height(6.dp))
                Text(info.take(900), fontSize = 10.sp, fontFamily = Mono, color = Color(0xFFD7DAE0), lineHeight = 13.sp)
            }
        }

        if (!ready) {
            Box(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(14.dp)) {
                Column {
                    Text("一键安装基础 SDK/NDK（约2GB，含 platform-tools、build-tools 34、platform 34、NDK 26）", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        if (busyPkg==null) scope.launch {
                            busyPkg="__install__"
                            withContext(Dispatchers.IO) { SdkManager.ensureBase { LinuxEnvironment.outputSink?.invoke(it) } }
                            busyPkg=null
                            reload()
                        }
                    }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(if (busyPkg=="__install__") "安装中..." else "安装基础包", fontSize = 12.sp, fontFamily = Mono, color = Color.White)
                    }
                    Text("输出会实时显示在终端页", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                }
            }
        }

        Text("可用包 · 点击安装/卸载", fontSize = 11.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(SdkManager.defaultPackages) { (path, desc) ->
                // 已安装判定：cmdline-tools;latest 特殊处理（任意版本视为已安装），其余需精确匹配版本
                val installedExact = when {
                    path == "cmdline-tools;latest" -> installed.any { it.startsWith("cmdline-tools;") }
                    path.contains(";") -> installed.contains(path)
                    else -> installed.contains(path)
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(path, fontSize = 12.sp, fontFamily = Mono, color = xc.ink)
                        Text(desc + if (installedExact) " · 已安装" else " · 未安装", fontSize = 10.sp, fontFamily = Mono, color = if(installedExact) Color(0xFF7BE0A4) else xc.faint)
                    }
                    if (busyPkg==path) {
                        Text("执行中...", fontSize = 11.sp, fontFamily = Mono, color = xc.faint)
                    } else if (installedExact) {
                        Text("卸载", fontSize = 11.sp, fontFamily = Mono, color = xc.red, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(xc.red.copy(0.12f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            scope.launch {
                                busyPkg=path
                                withContext(Dispatchers.IO) { SdkManager.uninstall(path) }
                                busyPkg=null
                                reload()
                            }
                        }.padding(horizontal = 12.dp, vertical = 6.dp))
                    } else {
                        Text("安装", fontSize = 11.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green.copy(0.15f)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            scope.launch {
                                busyPkg=path
                                withContext(Dispatchers.IO) { SdkManager.install(path) { LinuxEnvironment.outputSink?.invoke(it) } }
                                busyPkg=null
                                reload()
                            }
                        }.padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
            item {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Column {
                        Text("终端命令提示", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                        Text("sdkmanager --sdk_root=/opt/android-sdk --list\nsdkmanager --sdk_root=/opt/android-sdk 'platform-tools' 'ndk;26.1.10909125'\nndkmanager / sdkmanager 都可在终端页直接执行，已配置 ANDROID_HOME", fontSize = 10.sp, fontFamily = Mono, color = xc.sub, lineHeight = 13.sp)
                    }
                }
            }
        }
    }
}
