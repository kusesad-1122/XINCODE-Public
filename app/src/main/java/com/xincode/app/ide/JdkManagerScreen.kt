package com.xincode.app.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.JdkManager
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
fun JdkManagerScreen(onBack: () -> Unit, onOpenTerminal: () -> Unit = {}) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<JdkManager.JdkInfo>>(emptyList()) }
    var activeVer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val l = JdkManager.list()
            val v = JdkManager.getActiveVersion()
            withContext(Dispatchers.Main) { list = l; activeVer = v }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "JDK 管理", subtitle = "OpenJDK 11 / 17 · 一键切换 JAVA_HOME", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            XinHeaderAction(label = "刷新", onClick = { reload() })
            XinHeaderAction(label = "终端", onClick = onOpenTerminal)
        }
        if (!LinuxEnvironment.isReady()) {
            Box(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Text("Linux 环境未就绪，请先部署。", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
            }
            return@Column
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).padding(12.dp)) {
            Text("当前: ${activeVer.ifBlank { "未知" }}  JAVA_HOME: ${list.firstOrNull { it.active }?.home ?: "未设置"}", fontSize = 11.sp, fontFamily = Mono, color = xc.green)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { jdk ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, if(jdk.active) xc.green else xc.border, RoundedCornerShape(12.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OpenJDK ${jdk.version}", fontSize = 13.sp, fontFamily = Mono, color = xc.ink)
                        Text(jdk.home, fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                        Text(if(jdk.installed) (if(jdk.active) "已安装 · 当前" else "已安装") else "未安装", fontSize = 10.sp, fontFamily = Mono, color = if(jdk.installed) xc.green else xc.faint)
                    }
                    if (jdk.installed) {
                        if (jdk.active) {
                            Text("当前", fontSize = 11.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green.copy(0.12f)).padding(horizontal = 12.dp, vertical = 6.dp))
                        } else {
                            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green).clickable {
                                if (busy==null) scope.launch {
                                    busy=jdk.version
                                    withContext(Dispatchers.IO) { JdkManager.switchTo(jdk.version) }
                                    busy=null
                                    reload()
                                }
                            }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(if(busy==jdk.version) "切换中" else "切换", fontSize = 11.sp, fontFamily = Mono, color = Color.White)
                            }
                        }
                    } else {
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.bg).border(1.dp, xc.border, RoundedCornerShape(8.dp)).clickable {
                            if (busy==null) scope.launch {
                                busy=jdk.version
                                withContext(Dispatchers.IO) {
                                    val tool = com.xincode.app.EnvCatalog.categories.flatMap { it.tools }.firstOrNull { it.id=="jdk${jdk.version}" }
                                    if (tool!=null) com.xincode.app.EnvSetupManager.install(tool)
                                }
                                busy=null
                                reload()
                            }
                        }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(if(busy==jdk.version) "安装中" else "安装", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("JAVA_HOME 会写入 /etc/profile.d/jdk.sh 并通过 update-alternatives 切换，Gradle 与终端自动继承。", fontSize = 10.sp, fontFamily = Mono, color = xc.sub, lineHeight = 13.sp)
        }
    }
}
