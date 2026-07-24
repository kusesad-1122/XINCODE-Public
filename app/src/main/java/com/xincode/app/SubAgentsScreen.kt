package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.AppDatabase
import com.xincode.data.SubAgentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg = Color(0xFFF9F9F6)
private val Ink = Color(0xFF1A1A17)
private val Sub = Color(0xFF86857B)
private val Faint = Color(0xFFB7B6AB)
private val Green = Color(0xFF6E8050)
private val Red = Color(0xFFA8514A)
private val Border = Color(0xFFE6E4DC)
private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * 子智能体管理:查看内置/自建的子智能体类型,可【新建】自己的类型
 *(名字 + 角色设定 + 专属技能 CSV + 工具白名单 CSV)。主脑用 dispatch_agents 指挥它们。
 */
@Composable
fun SubAgentsScreen(
    database: AppDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf<List<SubAgentEntity>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        agents = withContext(Dispatchers.IO) { database.subAgentDao().getAll() }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("← 返回", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Text(if (creating) "取消" else "＋ 新建", fontSize = 12.sp, fontFamily = JetBrainsMono, color = if (creating) Sub else Green,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { creating = !creating })
        }
        Spacer(Modifier.height(16.dp))
        Text("子智能体", fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
        Text("主脑用 dispatch_agents 把任务并行拆给这些专职子智能体,各管各的专属技能/工具。", fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint)
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(0.5.dp).background(Border))

        if (creating) {
            CreateForm(onSave = { name, desc, prompt, skills, tools ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        database.subAgentDao().upsert(SubAgentEntity(
                            name = name, description = desc, systemPrompt = prompt,
                            skillNames = skills, toolNames = tools, builtin = false
                        ))
                    }
                    creating = false; reload++
                }
            })
            Spacer(Modifier.height(12.dp))
        }

        for (a in agents) {
            Column(Modifier.fillMaxWidth().border(0.5.dp, Border).padding(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(a.name + if (a.builtin) "  内置" else "", fontSize = 13.sp, fontFamily = JetBrainsMono,
                        color = Ink, modifier = Modifier.weight(1f))
                    if (!a.builtin) Text("删除", fontSize = 11.sp, fontFamily = JetBrainsMono, color = Red,
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            scope.launch { withContext(Dispatchers.IO) { database.subAgentDao().deleteById(a.id) }; reload++ }
                        })
                }
                if (a.description.isNotBlank()) Text(a.description, fontSize = 10.sp, fontFamily = JetBrainsMono, color = Sub)
                if (a.skillNames.isNotBlank()) Text("技能: ${a.skillNames}", fontSize = 9.sp, fontFamily = JetBrainsMono, color = Green, modifier = Modifier.padding(top = 3.dp))
                if (a.toolNames.isNotBlank()) Text("工具: ${a.toolNames}", fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CreateForm(onSave: (String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var skills by remember { mutableStateOf("") }
    var tools by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().border(0.5.dp, Green).padding(10.dp)) {
        Text("新建子智能体类型", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Ink)
        Spacer(Modifier.height(6.dp))
        f("名字(如 设计师)", name) { name = it }
        f("一句话职责", desc) { desc = it }
        f("角色设定(系统提示)", prompt) { prompt = it }
        f("专属技能(逗号分隔,如 explore,code-review)", skills) { skills = it }
        f("工具白名单(逗号分隔,空=只读集)", tools) { tools = it }
        Spacer(Modifier.height(6.dp))
        Text("保存", fontSize = 12.sp, fontFamily = JetBrainsMono, color = Green,
            modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                if (name.isNotBlank()) onSave(name.trim(), desc.trim(), prompt.trim(), skills.trim(), tools.trim())
            })
    }
}

@Composable
private fun f(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, fontSize = 9.sp, fontFamily = JetBrainsMono, color = Sub)
    Box(Modifier.fillMaxWidth().border(0.5.dp, Border).background(Color.White).padding(6.dp)) {
        BasicTextField(value = value, onValueChange = onChange,
            textStyle = TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono, color = Ink),
            cursorBrush = SolidColor(Green), modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(6.dp))
}
