package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.AppDatabase
import com.xincode.data.AuditLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BgAL: Color @Composable get() = LocalXinColors.current.bg
private val InkAL: Color @Composable get() = LocalXinColors.current.ink
private val SubAL: Color @Composable get() = LocalXinColors.current.sub
private val FaintAL: Color @Composable get() = LocalXinColors.current.faint
private val GreenAL: Color @Composable get() = LocalXinColors.current.green
private val RedAL: Color @Composable get() = LocalXinColors.current.red
private val JetBrainsMonoAL = XinUiFont

@Composable
fun AuditLogScreen(onBack: () -> Unit, database: AppDatabase? = null) {
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AuditLogEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        database?.let { db ->
            entries = withContext(Dispatchers.IO) { db.auditLogDao().getRecent(200) }
        }
    }

    Column(Modifier.fillMaxSize().background(BgAL).padding(16.dp)) {
        XinPageHeader(title = "审计日志", subtitle = "最近 200 条工具调用与权限记录", onBack = onBack) {
            XinHeaderAction(label = "清空", destructive = true, onClick = {
                scope.launch { database?.let { withContext(Dispatchers.IO) { it.auditLogDao().deleteAll() } }; entries = emptyList() }
            })
        }
        Spacer(Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Text("暂无审计记录", fontSize = 12.sp, fontFamily = JetBrainsMonoAL, color = FaintAL)
        } else {
            LazyColumn { items(entries, key = { it.id }) { AuditEntryRow(it) } }
        }
    }
}

@Composable
private fun AuditEntryRow(e: AuditLogEntity) {
    val isDenied = e.decision.startsWith("Denied")
    val isConfirm = e.decision.startsWith("NeedConfirm")
    val dotColor = when { isDenied -> RedAL; isConfirm -> Color(0xFFD4A017); else -> GreenAL }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("$dotColor ${e.toolName}  ${e.capability}/${e.reversibility}", fontSize = 11.sp, fontFamily = JetBrainsMonoAL, color = InkAL)
        Text(e.decision, fontSize = 10.sp, fontFamily = JetBrainsMonoAL, color = if (isDenied) RedAL else SubAL)
        if (!e.toolArgs.isNullOrBlank()) Text("args: ${e.toolArgs.take(80)}", fontSize = 9.sp, fontFamily = JetBrainsMonoAL, color = FaintAL)
        val r = e.result
        if (r != null && r.isNotBlank()) Text("result: ${r.take(80)}", fontSize = 9.sp, fontFamily = JetBrainsMonoAL, color = FaintAL)
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFE6E4DC)))
}
