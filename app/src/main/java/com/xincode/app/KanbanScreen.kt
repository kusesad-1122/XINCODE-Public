package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.data.KanbanTaskEntity
import com.xincode.data.KanbanTaskEntity.Companion.STATUS_DOING
import com.xincode.data.KanbanTaskEntity.Companion.STATUS_DONE
import com.xincode.data.KanbanTaskEntity.Companion.STATUS_TODO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/**
 * 看板:跨会话的长期待办。
 *
 * 没做拖拽——手机上纵向列表里的拖拽跟页面滚动会抢手势,做出来很难用。
 * 改成每张卡上直接给「← →」两个按钮在三列之间搬,单手就能操作,也更明确。
 */
@Composable
fun KanbanScreen(
    database: AppDatabase,
    planState: PlanState,
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()

    val tasks by database.kanbanTaskDao().observeAll().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<KanbanTaskEntity?>(null) }

    fun add(title: String, status: String = STATUS_TODO) {
        if (title.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                val dao = database.kanbanTaskDao()
                dao.insert(KanbanTaskEntity(
                    title = title.trim(),
                    status = status,
                    position = dao.maxPosition(status) + 1
                ))
            }
        }
    }

    fun move(task: KanbanTaskEntity, forward: Boolean) {
        val order = listOf(STATUS_TODO, STATUS_DOING, STATUS_DONE)
        val idx = order.indexOf(task.status).coerceAtLeast(0)
        val next = (if (forward) idx + 1 else idx - 1).coerceIn(0, order.size - 1)
        if (next == idx) return
        scope.launch {
            withContext(Dispatchers.IO) {
                database.kanbanTaskDao().setStatus(task.id, order[next])
            }
        }
    }

    val columns = listOf(
        Triple(STATUS_TODO, "待办", xc.sub),
        Triple(STATUS_DOING, "进行中", xc.green),
        Triple(STATUS_DONE, "已完成", xc.faint)
    )

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text("看板", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Text("+ 新建", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    newTitle = ""; showAdd = true
                })
        }

        // 把 AI 当前的计划一键固化成看板任务
        if (planState.visible && planState.steps.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp)).background(xc.activeBg).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "AI 当前有 ${planState.steps.size} 步计划",
                    fontSize = 11.sp, fontFamily = Mono, color = xc.ink, modifier = Modifier.weight(1f)
                )
                Text("导入看板", fontSize = 11.sp, fontFamily = Mono, color = xc.green,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        // 计划是回合内的临时清单,回合结束就没了;想留住就搬到看板来。
                        planState.steps.forEach { step ->
                            add(step.text, when (step.status) {
                                PlanStepStatus.DONE -> STATUS_DONE
                                PlanStepStatus.IN_PROGRESS -> STATUS_DOING
                                else -> STATUS_TODO
                            })
                        }
                    })
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            columns.forEach { (status, label, color) ->
                val items = tasks.filter { it.status == status }
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 12.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, color = color)
                    Spacer(Modifier.width(6.dp))
                    Text("${items.size}", fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                        modifier = Modifier.weight(1f))
                    if (status == STATUS_DONE && items.isNotEmpty()) {
                        Text("清空", fontSize = 10.sp, fontFamily = Mono, color = xc.red,
                            modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                scope.launch { withContext(Dispatchers.IO) { database.kanbanTaskDao().clearDone() } }
                            })
                    }
                }
                if (items.isEmpty()) {
                    Text("—", fontSize = 11.sp, fontFamily = Mono, color = xc.faint,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                items.forEach { task ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(8.dp)).background(xc.bgElevated).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("‹", fontSize = 15.sp, fontFamily = Mono,
                            color = if (status == STATUS_TODO) xc.bgElevated else xc.sub,
                            modifier = Modifier
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() },
                                    enabled = status != STATUS_TODO) { move(task, false) }
                                .padding(horizontal = 6.dp))
                        Column(
                            Modifier.weight(1f)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { editing = task }
                        ) {
                            Text(
                                task.title, fontSize = 12.sp, fontFamily = Mono,
                                color = if (status == STATUS_DONE) xc.faint else xc.ink,
                                lineHeight = 17.sp
                            )
                            if (task.note.isNotBlank()) {
                                Text(task.note, fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                                    lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        Text("›", fontSize = 15.sp, fontFamily = Mono,
                            color = if (status == STATUS_DONE) xc.bgElevated else xc.sub,
                            modifier = Modifier
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() },
                                    enabled = status != STATUS_DONE) { move(task, true) }
                                .padding(horizontal = 6.dp))
                    }
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新建任务", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
            text = {
                TextField(
                    value = newTitle, onValueChange = { newTitle = it },
                    placeholder = { Text("要做什么", fontSize = 12.sp, fontFamily = Mono, color = xc.faint) },
                    modifier = Modifier.fillMaxWidth().border(0.5.dp, xc.border, RoundedCornerShape(4.dp)),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = Mono)
                )
            },
            confirmButton = {
                TextButton(onClick = { add(newTitle); showAdd = false }) {
                    Text("添加", fontFamily = Mono, color = xc.green)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消", fontFamily = Mono, color = xc.sub) }
            },
            containerColor = xc.bg
        )
    }

    editing?.let { task ->
        var title by remember(task.id) { mutableStateOf(task.title) }
        var note by remember(task.id) { mutableStateOf(task.note) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("编辑任务", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
            text = {
                Column {
                    TextField(
                        value = title, onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth().border(0.5.dp, xc.border, RoundedCornerShape(4.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = Mono)
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = note, onValueChange = { note = it },
                        placeholder = { Text("备注(可选)", fontSize = 12.sp, fontFamily = Mono, color = xc.faint) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp)
                            .border(0.5.dp, xc.border, RoundedCornerShape(4.dp)),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = Mono)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("删除这个任务", fontSize = 12.sp, fontFamily = Mono, color = xc.red,
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            scope.launch { withContext(Dispatchers.IO) { database.kanbanTaskDao().delete(task) } }
                            editing = null
                        })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            database.kanbanTaskDao().update(
                                task.copy(title = title.trim(), note = note.trim(),
                                    updatedAt = System.currentTimeMillis())
                            )
                        }
                    }
                    editing = null
                }) { Text("保存", fontFamily = Mono, color = xc.green) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消", fontFamily = Mono, color = xc.sub) }
            },
            containerColor = xc.bg
        )
    }
}
