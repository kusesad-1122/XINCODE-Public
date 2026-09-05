package com.xincode.app.ide.designer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.*
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Mono = XinUiFont

@Composable
fun UiDesignerScreen(
    database: AppDatabase,
    workspaceRoot: String,
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    val state = remember { UiDesignerState() }
    var projectRoot by remember { mutableStateOf(workspaceRoot.ifBlank { "/sdcard" }) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var layoutInput by remember { mutableStateOf(state.layoutName) }
    var activeTab by remember { mutableStateOf(0) } // 0 可视 1 XML 2 资源
    var propKeyInput by remember { mutableStateOf("") }
    var propValInput by remember { mutableStateOf("") }
    var showPropDialog by remember { mutableStateOf(false) }
    var resourceQuery by remember { mutableStateOf("") }

    LaunchedEffect(projectRoot) {
        state.projectRoot = projectRoot
        state.resources = ResourceParser.parseProjectResources(projectRoot)
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(title = "UI 设计师", subtitle = "布局充气器·资源解析·拖放·可视化编辑", onBack = onBack, modifier = Modifier.padding(horizontal = 12.dp)) {
            XinHeaderAction(label = "保存", onClick = { layoutInput = state.layoutName; showSaveDialog = true })
        }

        // 项目路径 + 标签
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(projectRoot, fontSize = 10.sp, fontFamily = Mono, color = xc.sub, modifier = Modifier.weight(1f))
            Text("切换", fontSize = 10.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green.copy(0.12f)).clickable {
                // 简单切换到工作区
                scope.launch(Dispatchers.IO) {
                    val ws = database.settingDao().get("workspace_root") ?: ""
                    withContext(Dispatchers.Main) { projectRoot = ws.ifBlank { "/sdcard" } }
                }
            }.padding(horizontal = 8.dp, vertical = 4.dp))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("可视化" to 0, "XML" to 1, "资源" to 2).forEach { (label, idx) ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if(activeTab==idx) xc.green else xc.bgElevated).border(1.dp, if(activeTab==idx) xc.green else xc.border, RoundedCornerShape(8.dp)).clickable { activeTab = idx }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 11.sp, fontFamily = Mono, color = if(activeTab==idx) Color.White else xc.sub)
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                0 -> VisualEditorTab(state, xc, scope, onAddProp = { k,v -> state.updateProp(k,v) }, onShowProp = { showPropDialog = true })
                1 -> XmlTab(state, xc)
                2 -> ResourceTab(state.resources, resourceQuery, onQuery = { resourceQuery = it }, xc)
            }
        }

        // 底部 widget 托盘
        WidgetPalette(state, xc)
    }

    if (showSaveDialog) {
        AlertDialog(onDismissRequest = { showSaveDialog=false }, title = { Text("保存布局", fontFamily = Mono) }, text = {
            Column {
                TextField(value = layoutInput, onValueChange = { layoutInput=it }, label = { Text("布局名 (不含 .xml)", fontSize = 11.sp, fontFamily = Mono) }, modifier = Modifier.fillMaxWidth(), colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono))
                Text("将保存到 $projectRoot/app/src/main/res/layout/$layoutInput.xml", fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
            }
        }, confirmButton = {
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        val dir = File(projectRoot, "app/src/main/res/layout")
                        dir.mkdirs()
                        File(dir, "$layoutInput.xml").writeText(state.generateXml())
                        withContext(Dispatchers.Main) { state.layoutName = layoutInput; showSaveDialog=false }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { showSaveDialog=false }
                    }
                }
            }) { Text("保存", fontFamily = Mono, color = xc.green) }
        }, dismissButton = { TextButton(onClick = { showSaveDialog=false }) { Text("取消", fontFamily = Mono, color = xc.sub) } }, containerColor = xc.bg)
    }

    if (showPropDialog) {
        AlertDialog(onDismissRequest = { showPropDialog=false }, title = { Text("编辑属性", fontFamily = Mono) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = propKeyInput, onValueChange = { propKeyInput=it }, label = { Text("属性名 如 android:text", fontSize = 11.sp, fontFamily = Mono) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextField(value = propValInput, onValueChange = { propValInput=it; resourceQuery=it }, label = { Text("属性值 支持 @string/@color 自动完成", fontSize = 11.sp, fontFamily = Mono) }, modifier = Modifier.fillMaxWidth())
                if (propValInput.startsWith("@") || propValInput.startsWith("?")) {
                    val suggests = ResourceParser.suggestResourceValues(propValInput, state.resources).take(5)
                    suggests.forEach { s ->
                        Text(s, fontSize = 10.sp, fontFamily = Mono, color = xc.green, modifier = Modifier.clickable { propValInput = s })
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                if (propKeyInput.isNotBlank()) state.updateProp(propKeyInput.trim(), propValInput)
                showPropDialog=false
                propKeyInput=""; propValInput=""
            }) { Text("应用", fontFamily = Mono, color = xc.green) }
        }, dismissButton = { TextButton(onClick = { showPropDialog=false }) { Text("取消", fontFamily = Mono) } }, containerColor = xc.bg)
    }
}

@Composable
private fun VisualEditorTab(state: UiDesignerState, xc: XinColors, scope: kotlinx.coroutines.CoroutineScope, onAddProp: (String,String)->Unit, onShowProp: ()->Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // 选中节点的可视化属性编辑器
        state.selectedNode?.let { node ->
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).border(1.dp, Color(0xFF20232E), RoundedCornerShape(12.dp)).padding(10.dp)) {
                Column {
                    Text("${node.type}  #${node.id}", fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Color(0xFFD7DAE0))
                    Spacer(Modifier.height(6.dp))
                    // props
                    node.props.entries.take(6).forEach { (k,v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(k, fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF6B7089), modifier = Modifier.weight(1f))
                            Text(v.take(22), fontSize = 10.sp, fontFamily = Mono, color = Color(0xFFD7DAE0), modifier = Modifier.weight(1f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(xc.green).clickable { onShowProp() }.padding(horizontal = 10.dp, vertical = 5.dp)) { Text("编辑属性", fontSize = 10.sp, fontFamily = Mono, color = Color.White) }
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2E3F)).clickable { state.moveUp() }.padding(horizontal = 8.dp, vertical = 5.dp)) { Text("上移", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFFD7DAE0)) }
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2E3F)).clickable { state.moveDown() }.padding(horizontal = 8.dp, vertical = 5.dp)) { Text("下移", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFFD7DAE0)) }
                        Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFE0685C).copy(0.2f)).clickable { state.removeSelected() }.padding(horizontal = 8.dp, vertical = 5.dp)) { Text("删除", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFFE0685C)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // 预览区（布局充气器模拟）
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.White).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
            if (state.nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("拖放下方小部件到此处\n布局充气器实时预览", fontSize = 11.sp, fontFamily = Mono, color = Color(0xFF9AA0B5), lineHeight = 15.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.nodes) { idx, node ->
                        val selected = state.selectedIndex == idx
                        Box(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Color(0xFFEFF6FF) else Color(0xFFF6F7F9))
                                .border(1.dp, if (selected) xc.green else Color(0xFFE5E7EB), RoundedCornerShape(8.dp))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { state.selectedIndex = idx }
                                .padding(10.dp)
                        ) {
                            Column {
                                Text("${node.type} id=${node.id}", fontSize = 10.sp, fontFamily = Mono, color = Color(0xFF6B7280))
                                // 模拟渲染
                                when (node.type) {
                                    "TextView" -> Text(node.props["android:text"] ?: "Text", color = Color.Black, fontSize = 14.sp)
                                    "Button" -> Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF3B82F6)).padding(horizontal = 16.dp, vertical = 8.dp)) { Text(node.props["android:text"] ?: "Button", color = Color.White, fontSize = 12.sp) }
                                    "EditText" -> Box(Modifier.fillMaxWidth().border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(6.dp)).padding(8.dp)) { Text(node.props["android:hint"] ?: "hint", color = Color(0xFF9CA3AF), fontSize = 11.sp) }
                                    "ImageView" -> Box(Modifier.size(48.dp).background(Color(0xFFE5E7EB), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Text("IMG", fontSize = 10.sp, color = Color(0xFF6B7280)) }
                                    else -> Text("<${node.type}>", fontSize = 11.sp, color = Color(0xFF6B7280), fontFamily = Mono)
                                }
                                Text(node.props.entries.joinToString(" ") { "${it.key}=\"${it.value}\"" }.take(90), fontSize = 8.sp, fontFamily = Mono, color = Color(0xFF9CA3AF))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun XmlTab(state: UiDesignerState, xc: XinColors) {
    val xml = state.generateXml()
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0F1117)).padding(12.dp)) {
        Column(Modifier.fillMaxSize().horizontalScroll(scroll).verticalScroll(scroll)) {
            Text(xml, fontSize = 10.sp, fontFamily = Mono, color = Color(0xFFD7DAE0), lineHeight = 13.sp)
        }
    }
}

@Composable
private fun ResourceTab(resources: ResourceSet, query: String, onQuery: (String)->Unit, xc: XinColors) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("解析资源引用 · 自动完成", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.TextField(value = query, onValueChange = onQuery, placeholder = { Text("输入 @string / @color 过滤", fontSize = 10.sp, fontFamily = Mono) }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 11.sp))
        Spacer(Modifier.height(8.dp))
        val filteredStrings = if (query.isBlank()) resources.strings.take(20) else resources.strings.filter { it.name.contains(query.trim().removePrefix("@string/"), true) }.take(20)
        Text("strings.xml (${resources.strings.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
        filteredStrings.forEach { s -> Text("@string/${s.name} = \"${s.value.take(30)}\"", fontSize = 10.sp, fontFamily = Mono, color = xc.sub) }
        Spacer(Modifier.height(6.dp))
        Text("colors.xml (${resources.colors.size})", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
        resources.colors.take(10).forEach { c -> Text("@color/${c.name} = ${c.value}", fontSize = 10.sp, fontFamily = Mono, color = xc.sub) }
        Spacer(Modifier.height(6.dp))
        Text("layouts (${resources.layouts.size})  ids (${resources.ids.size})", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
    }
}

@Composable
private fun WidgetPalette(state: UiDesignerState, xc: XinColors) {
    Column(Modifier.fillMaxWidth().background(xc.bgElevated).border(1.dp, xc.border, RoundedCornerShape(0.dp)).padding(8.dp)) {
        Text("Android 小部件 · 拖放添加", fontSize = 10.sp, fontFamily = Mono, color = xc.sub)
        Spacer(Modifier.height(6.dp))
        WidgetCatalog.byCategory().forEach { (cat, widgets) ->
            Text(cat, fontSize = 9.sp, fontFamily = Mono, color = xc.faint, modifier = Modifier.padding(vertical = 2.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                widgets.forEach { w ->
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(xc.bg).border(1.dp, xc.border, RoundedCornerShape(10.dp)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { state.addWidget(w) }.padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(w.icon, fontSize = 16.sp, fontFamily = Mono, color = xc.ink)
                            Text(w.displayName, fontSize = 9.sp, fontFamily = Mono, color = xc.sub)
                            Text(w.type, fontSize = 8.sp, fontFamily = Mono, color = xc.faint)
                        }
                    }
                }
            }
        }
    }
}
