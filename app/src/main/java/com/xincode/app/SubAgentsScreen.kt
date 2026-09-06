package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.data.SubAgentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bg: Color @Composable get() = LocalXinColors.current.bg
private val BgElevated: Color @Composable get() = LocalXinColors.current.bgElevated
private val Ink: Color @Composable get() = LocalXinColors.current.ink
private val Sub: Color @Composable get() = LocalXinColors.current.sub
private val Faint: Color @Composable get() = LocalXinColors.current.faint
private val Green: Color @Composable get() = LocalXinColors.current.green
private val Red: Color @Composable get() = LocalXinColors.current.red
private val Border: Color @Composable get() = LocalXinColors.current.border
private val JetBrainsMono = XinUiFont

/**
 * 子智能体管理:主脑用 dispatch_agents 并行派活,每个子智能体只带自己的
 * 技能与工具白名单。卡片展示真实能力清单;点卡片可编辑(内置的不可删)。
 */
@Composable
fun SubAgentsScreen(
    database: AppDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf<List<SubAgentEntity>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    // 修：列表此前从未加载（无 LaunchedEffect、无订阅），子智能体页恒为空。
    // 按 reload 重查，增删改后 reload++ 即刷新。
    androidx.compose.runtime.LaunchedEffect(reload) {
        agents = withContext(Dispatchers.IO) { database.subAgentDao().getAll() }
    }
    var creating by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Long?>(null) }

    fun save(name: String, desc: String, prompt: String, skills: String, tools: String, id: Long = 0, builtin: Boolean = false) {
        scope.launch {
            withContext(Dispatchers.IO) {
                database.subAgentDao().upsert(SubAgentEntity(
                    id = id,
                    name = name, description = desc, systemPrompt = prompt,
                    skillNames = skills, toolNames = tools, builtin = builtin
                ))
            }
            creating = false; editingId = null; reload++
        }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp).verticalScroll(rememberScrollState())) {
        XinPageHeader(
            title = "子智能体",
            subtitle = "主脑并行派活的专职角色 · 各带各的技能与工具",
            onBack = onBack
        ) {
            XinHeaderAction(label = if (creating) t("取消") else t("新建"), onClick = { creating = !creating; editingId = null })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            t("派活时主脑按名字点名;角色设定与白名单决定它只会做什么、能碰什么。"),
            fontSize = 10.sp, fontFamily = JetBrainsMono, color = Faint, lineHeight = 14.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        if (creating) {
            AgentForm(
                title = t("新建子智能体"),
                onSave = { name, desc, prompt, skills, tools -> save(name, desc, prompt, skills, tools) },
                onCancel = { creating = false }
            )
            Spacer(Modifier.height(12.dp))
        }

        editingId?.let { eid ->
            agents.firstOrNull { it.id == eid }?.let { target ->
                AgentForm(
                    title = tx("编辑:%s", target.name),
                    initialName = target.name,
                    initialDesc = target.description,
                    initialPrompt = target.systemPrompt,
                    initialSkills = target.skillNames,
                    initialTools = target.toolNames,
                    onSave = { name, desc, prompt, skills, tools ->
                        save(name, desc, prompt, skills, tools, id = target.id, builtin = target.builtin)
                    },
                    onCancel = { editingId = null }
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        for (a in agents) {
            SubAgentCard(
                agent = a,
                onEdit = { editingId = a.id; creating = false },
                onDelete = {
                    scope.launch { withContext(Dispatchers.IO) { database.subAgentDao().deleteById(a.id) }; reload++ }
                }
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SubAgentCard(
    agent: SubAgentEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val skills = agent.skillNames.split(",").map { it.trim() }.filter { it.isNotBlank() }
    val tools = agent.toolNames.split(",").map { it.trim() }.filter { it.isNotBlank() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BgElevated)
            .border(1.dp, Border, RoundedCornerShape(18.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onEdit() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(Green.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    agent.name.firstOrNull()?.toString() ?: "?",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Green
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(agent.name, fontSize = 14.sp, fontFamily = JetBrainsMono, color = Ink)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (agent.builtin) t("内置") else t("自建"),
                        fontSize = 9.5.sp, fontFamily = JetBrainsMono,
                        color = if (agent.builtin) Sub else Green,
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(if (agent.builtin) Bg else Green.copy(alpha = 0.12f))
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    )
                }
                if (agent.description.isNotBlank()) {
                    Text(
                        agent.description, fontSize = 11.sp, fontFamily = JetBrainsMono, color = Sub,
                        lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (!agent.builtin) {
                Text(
                    t("删除"), fontSize = 11.sp, fontFamily = JetBrainsMono, color = Red,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDelete() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(9.dp))
        // 能力清单:技能与工具白名单,一张卡看全它「会什么、能碰什么」
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CapabilityChip(
                label = if (skills.isEmpty()) t("无专属技能") else tx("技能 %s 项", skills.size.toString()),
                detail = skills.joinToString("、").ifBlank { null },
                accent = true
            )
            CapabilityChip(
                label = if (tools.isEmpty()) t("默认只读集") else tx("工具 %s 项", tools.size.toString()),
                detail = tools.joinToString("、").ifBlank { null },
                accent = false
            )
        }
        if (skills.isNotEmpty() || tools.isNotEmpty()) {
            Text(
                buildString {
                    if (skills.isNotEmpty()) append(t("技能:") + skills.joinToString("、"))
                    if (tools.isNotEmpty()) {
                        if (isNotEmpty()) append("  ·  ")
                        append(t("工具:") + tools.joinToString("、"))
                    }
                },
                fontSize = 9.sp, fontFamily = JetBrainsMono, color = Faint, lineHeight = 13.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun CapabilityChip(label: String, detail: String?, accent: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (accent) Green.copy(alpha = 0.1f) else Bg)
            .border(0.5.dp, if (accent) Green.copy(alpha = 0.3f) else Border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 10.sp, fontFamily = JetBrainsMono, color = if (accent) Green else Sub)
    }
}

/** 新建/编辑共用表单:圆角输入 + 分组标签。 */
@Composable
private fun AgentForm(
    title: String,
    initialName: String = "",
    initialDesc: String = "",
    initialPrompt: String = "",
    initialSkills: String = "",
    initialTools: String = "",
    onSave: (String, String, String, String, String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(title) { mutableStateOf(initialName) }
    var desc by remember(title) { mutableStateOf(initialDesc) }
    var prompt by remember(title) { mutableStateOf(initialPrompt) }
    var skills by remember(title) { mutableStateOf(initialSkills) }
    var tools by remember(title) { mutableStateOf(initialTools) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(BgElevated)
            .border(1.dp, Green.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title, fontSize = 13.sp, fontFamily = JetBrainsMono, color = Ink, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        f(t("名字(如 设计师)"), name) { name = it }
        f(t("一句话职责"), desc) { desc = it }
        f(t("角色设定(系统提示)"), prompt) { prompt = it }
        f(t("专属技能(逗号分隔,如 explore,code-review)"), skills) { skills = it }
        f(t("工具白名单(逗号分隔,空=只读集)"), tools) { tools = it }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                t("保存"), fontSize = 12.sp, fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold,
                color = if (name.isNotBlank()) Green else Faint,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    if (name.isNotBlank()) onSave(name.trim(), desc.trim(), prompt.trim(), skills.trim(), tools.trim())
                }
            )
            Text(
                t("取消"), fontSize = 12.sp, fontFamily = JetBrainsMono, color = Sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onCancel() }
            )
        }
    }
}

@Composable
private fun f(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, fontSize = 9.sp, fontFamily = JetBrainsMono, color = Sub, modifier = Modifier.padding(bottom = 3.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(0.5.dp, Border, RoundedCornerShape(10.dp))
            .background(Bg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = TextStyle(fontSize = 12.sp, fontFamily = JetBrainsMono, color = Ink),
            cursorBrush = SolidColor(Green), modifier = Modifier.fillMaxWidth()
        )
    }
    Spacer(Modifier.height(7.dp))
}
