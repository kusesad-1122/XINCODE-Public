package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.data.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Terminal palette — consistent with ChatScreen/WorkflowScreen
private val SkBg = Color(0xFFF9F9F6)
private val SkInk = Color(0xFF1A1A17)
private val SkSub = Color(0xFF86857B)
private val SkFaint = Color(0xFFB7B6AB)
private val SkBorder = Color(0xFFE6E4DC)
private val SkMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))

/**
 * Skills CRUD screen — create, list, edit, delete skills.
 */
@Composable
fun SkillScreen(
    database: AppDatabase,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<SkillEntity>>(emptyList()) }
    var editing by remember { mutableStateOf<SkillEntity?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        skills = withContext(Dispatchers.IO) { database.skillDao().getAll() }
    }

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            skills = database.skillDao().getAll()
        }
    }

    Column(Modifier.fillMaxSize().background(SkBg)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, "back", tint = SkInk)
            }
            Text("Skills", fontSize = 15.sp, fontFamily = SkMono, color = SkInk,
                modifier = Modifier.weight(1f))
            IconButton(onClick = {
                editing = null
                showCreate = true
            }) {
                Icon(Icons.Outlined.Add, "new skill", tint = SkInk)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(SkBorder))

        when {
            editing != null || showCreate -> {
                SkillEditor(
                    initial = editing ?: SkillEntity(name = "", description = "", content = ""),
                    isNew = showCreate,
                    onSave = { entity ->
                        scope.launch(Dispatchers.IO) {
                            database.skillDao().upsert(entity)
                            withContext(Dispatchers.Main) {
                                editing = null; showCreate = false; refresh()
                            }
                        }
                    },
                    onCancel = { editing = null; showCreate = false }
                )
            }
            skills.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有技能\n点右上角 + 创建一个", fontFamily = SkMono, fontSize = 13.sp,
                        color = SkSub, lineHeight = 20.sp)
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    items(skills, key = { it.id }) { skill ->
                        SkillCard(skill = skill,
                            onClick = { editing = skill; showCreate = false },
                            onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    database.skillDao().deleteById(skill.id)
                                    withContext(Dispatchers.Main) { refresh() }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillCard(skill: SkillEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(skill.name, fontSize = 13.sp, fontFamily = SkMono, color = SkInk,
                modifier = Modifier.weight(1f))
            // Hermes-① 出处标记:内置(只读)/ 自建(后台复盘生成)/ 用户自建不显示。
            val badge = when (skill.source) {
                "bundled" -> "内置"
                "agent" -> "自建"
                else -> ""
            }
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 9.sp, fontFamily = SkMono,
                    color = if (skill.source == "agent") Color(0xFF6E8050) else SkSub,
                    modifier = Modifier.padding(end = 6.dp))
            }
            IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Delete, "delete", tint = SkSub, modifier = Modifier.size(14.dp))
            }
        }
        if (skill.description.isNotBlank()) {
            Text(skill.description, fontSize = 11.sp, fontFamily = SkMono, color = SkSub, maxLines = 2)
        }
        // Content preview
        if (skill.content.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(skill.content.take(100) + if (skill.content.length > 100) "…" else "",
                fontSize = 10.sp, fontFamily = SkMono, color = SkFaint, maxLines = 2)
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(0.5.dp).background(SkBorder))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除技能", fontFamily = SkMono, color = SkInk) },
            text = { Text("确定删除「${skill.name}」？此操作不可撤回。", fontFamily = SkMono, fontSize = 12.sp, color = SkInk) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("删除", fontFamily = SkMono, color = Color(0xFFA8514A))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", fontFamily = SkMono, color = SkSub)
                }
            },
            containerColor = SkBg
        )
    }
}

@Composable
private fun SkillEditor(initial: SkillEntity, isNew: Boolean, onSave: (SkillEntity) -> Unit, onCancel: () -> Unit) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var desc by remember(initial) { mutableStateOf(initial.description) }
    var content by remember(initial) { mutableStateOf(initial.content) }

    val fieldColors = @Composable {
        TextFieldDefaults.colors(
            focusedContainerColor = SkBg,
            unfocusedContainerColor = SkBg,
            focusedIndicatorColor = SkInk,
            unfocusedIndicatorColor = SkBorder,
            cursorColor = SkInk,
            focusedTextColor = SkInk,
            unfocusedTextColor = SkInk
        )
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(if (isNew) "新建技能" else "编辑技能", fontSize = 13.sp, fontFamily = SkMono, color = SkInk)
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(SkBorder))

        Text("名称", fontSize = 10.sp, fontFamily = SkMono, color = SkSub)
        TextField(value = name, onValueChange = { name = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = SkMono),
            colors = fieldColors())

        Text("描述（可选）", fontSize = 10.sp, fontFamily = SkMono, color = SkSub)
        TextField(value = desc, onValueChange = { desc = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = SkMono),
            colors = fieldColors())

        Text("内容（Markdown 指令，注入系统提示）", fontSize = 10.sp, fontFamily = SkMono, color = SkSub)
        TextField(value = content, onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = SkMono, lineHeight = 18.sp),
            colors = fieldColors())

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text("取消", fontFamily = SkMono, color = SkSub) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                if (name.isNotBlank()) {
                    onSave(initial.copy(
                        name = name.trim(),
                        description = desc.trim(),
                        content = content.trim(),
                        updatedAt = System.currentTimeMillis()
                    ))
                }
            }) { Text("保存", fontFamily = SkMono, color = SkInk) }
        }
    }
}