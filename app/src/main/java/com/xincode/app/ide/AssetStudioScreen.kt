package com.xincode.app.ide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.LocalXinColors
import com.xincode.app.XinHeaderAction
import com.xincode.app.XinPageHeader
import com.xincode.app.XinUiFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Mono = XinUiFont

@Composable
fun AssetStudioScreen(
    workspaceRoot: String,
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    var iconName by remember { mutableStateOf("ic_custom") }
    var selectedShape by remember { mutableStateOf("rounded") } // rounded, circle, square, vector
    var bgColor by remember { mutableStateOf(Color(0xFF3B82F6)) }
    var fgColor by remember { mutableStateOf(Color.White) }
    var iconText by remember { mutableStateOf("X") }
    var size by remember { mutableStateOf(96f) }
    var corner by remember { mutableStateOf(16f) }
    var projectRoot by remember { mutableStateOf(workspaceRoot.ifBlank { "/sdcard" }) }
    var status by remember { mutableStateOf("") }

    fun generateVectorXml(): String {
        val bgHex = String.format("#%06X", 0xFFFFFF and bgColor.toArgb())
        val fgHex = String.format("#%06X", 0xFFFFFF and fgColor.toArgb())
        return when (selectedShape) {
            "circle" -> """
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="$bgHex" android:pathData="M12,12m-10,0a10,10 0,1 1,20 0a10,10 0,1 1,-20 0"/>
    <path android:fillColor="$fgHex" android:pathData="M12,8L16,12L12,16L8,12Z"/>
</vector>
""".trimIndent()
            "square" -> """
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="$bgHex" android:pathData="M2,2h20v20h-20z"/>
</vector>
""".trimIndent()
            else -> """
<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="$bgHex" android:pathData="M4,4h16v16h-16z" android:strokeColor="$fgHex" android:strokeWidth="1"/>
    <path android:fillColor="$fgHex" android:pathData="M12,7L17,12L12,17L7,12Z"/>
</vector>
""".trimIndent()
        }
    }

    fun generateDrawableXml(): String {
        val bgHex = String.format("#%06X", 0xFFFFFF and bgColor.toArgb())
        return when (selectedShape) {
            "circle" -> """
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="$bgHex" />
</shape>
""".trimIndent()
            "square" -> """
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="$bgHex" />
</shape>
""".trimIndent()
            else -> """
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="$bgHex" />
    <corners android:radius="${corner.toInt()}dp" />
</shape>
""".trimIndent()
        }
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "Asset Studio", subtitle = "图标与绘图制作 · Vector/ Shape 生成", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            XinHeaderAction(label = "导出", onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        val dir = File(projectRoot, "app/src/main/res/drawable")
                        dir.mkdirs()
                        val xml = if (selectedShape=="vector") generateVectorXml() else generateDrawableXml()
                        // also add text overlay as vector if needed
                        File(dir, "$iconName.xml").writeText(xml)
                        withContext(Dispatchers.Main) { status = "已导出到 drawable/$iconName.xml" }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { status = "失败: ${e.message}" }
                    }
                }
            })
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("项目: $projectRoot", fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
                TextField(value = iconName, onValueChange = { iconName = it.filter { c -> c.isLetterOrDigit() || c=='_' } }, label = { Text("资源名", fontSize = 10.sp, fontFamily = Mono) }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 11.sp))
            }
            Spacer(Modifier.width(8.dp))
            val previewDp = size.toInt().dp.coerceIn(48.dp, 120.dp)
            val iconSizeFloat = size
            Canvas(Modifier.size(previewDp).clip(
                when(selectedShape){ "circle"-> CircleShape else-> RoundedCornerShape(corner.toInt().dp) }
            ).background(bgColor)) {
                // DrawScope.size 为 px 尺寸，取短边 1/3 为半径，避免 dp/px 混用
                val rPx = minOf(size.width, size.height) * 0.35f
                // iconSizeFloat 保留给外部逻辑使用，避免与 DrawScope.size 命名冲突
                @Suppress("UNUSED_VARIABLE") val _outer = iconSizeFloat
                drawCircle(color = fgColor, radius = rPx, style = Fill)
            }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("rounded" to "圆角", "circle" to "圆形", "square" to "方形", "vector" to "矢量").forEach { (id, label) ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if(selectedShape==id) xc.green else xc.bgElevated).border(1.dp, if(selectedShape==id) xc.green else xc.border, RoundedCornerShape(8.dp)).clickable { selectedShape=id }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 10.sp, fontFamily = Mono, color = if(selectedShape==id) Color.White else xc.sub)
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text("背景色", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                listOf(Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B), Color(0xFFEF4444), Color(0xFF8B5CF6), Color(0xFF111827)).forEach { c ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(c).border(2.dp, if(bgColor==c) xc.green else Color.Transparent, CircleShape).clickable { bgColor=c })
                }
            }
            Text("前景/图标文字", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(value = iconText, onValueChange = { iconText = it.take(2) }, modifier = Modifier.width(80.dp), singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 14.sp, color = xc.ink))
                Slider(value = corner, onValueChange = { corner=it }, valueRange = 0f..32f, modifier = Modifier.weight(1f))
                Text("${corner.toInt()}dp", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
            }
            Text("大小 ${size.toInt()}dp", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
            Slider(value = size, onValueChange = { size=it }, valueRange = 48f..120f)
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).padding(12.dp)) {
            val xml = if (selectedShape=="vector") generateVectorXml() else generateDrawableXml()
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("预览 XML", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF7BE0A4))
                Text(xml, fontSize = 9.sp, fontFamily = Mono, color = Color(0xFFD7DAE0), lineHeight = 11.sp)
                if (status.isNotBlank()) Text(status, fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF7BE0A4), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
