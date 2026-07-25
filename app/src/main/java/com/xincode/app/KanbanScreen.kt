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
    runner: KanbanRunner,
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
        val order = listOf(
            STATUS_TODO, KanbanTaskEntity.STATUS_READY, KanbanTaskEntity.STATUS_RUNNING,
            KanbanTaskEntity.STATUS_REVIEW, STATUS_DOING, STATUS_DONE
        )
        val idx = order.indexOf(task.status).coerceAtLeast(0)
        val next = (if (forward) idx + 1 else idx - 1).coerceIn(0, order.size - 1)
        if (next == idx) return
        scope.launch {
            withContext(Dispatchers.IO) {
                database.kanbanTaskDao().setStatus(task.id, order[next])
            }
        }
    }

    // 六种状态。前三种是你自己做,后三种是智能体在做 —— 看板因此不只是清单,是工作队列。
    val columns = listOf(
        Triple(STATUS_TODO, "待办", xc.sub),
        Triple(KanbanTaskEntity.STATUS_READY, "待智能体执行", xc.green),
        Triple(KanbanTaskEntity.STATUS_RUNNING, "执行中", xc.green),
        Triple(KanbanTaskEntity.STATUS_REVIEW, "待验收", xc.green),
        Triple(KanbanTaskEntity.STATUS_BLOCKED, "受阻", xc.red),
        Triple(STATUS_DOING, "我在做", xc.sub),
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

        // 队列控制条:有 ready 任务时给「开始执行」入口
        val readyCount = tasks.count { it.status == KanbanTaskEntity.STATUS_READY }
        if (readyCount > 0 || runner.isRunning) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp)).background(xc.activeBg).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (runner.isRunning) "智能体正在执行任务…" else "$readyCount 个任务待执行",
                    fontSize = 11.sp, fontFamily = Mono, color = xc.ink, modifier = Modifier.weight(1f)
                )
                Text(
                    if (runner.isRunning) "停止" else "开始执行",
                    fontSize = 11.sp, fontFamily = Mono,
                    color = if (runner.isRunning) xc.red else xc.green,
                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        if (runner.isRunning) runner.stop() else runner.start()
                    })
            }
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
                            // 指派对象 + 执行次数
                            val meta = buildList<String> {
                                if (task.assignee.isNotBlank()) add("@${task.assignee}")
                                if (task.runCount > 0) add("跑过 ${task.runCount} 次")
                            }.joinToString("  ·  ")
                            if (meta.isNotBlank()) {
                                Text(meta, fontSize = 9.sp, fontFamily = Mono, color = xc.faint,
                                    modifier = Modifier.padding(top = 2.dp))
                            }
                            // 执行结果:成功给摘要,失败给原因 —— 受阻时最需要看的就是这个
                            if (task.result.isNotBlank()) {
                                Text(
                                    task.result, fontSize = 9.sp, fontFamily = Mono,
                                    color = if (status == KanbanTaskEntity.STATUS_BLOCKED) xc.red else xc.faint,
                                    lineHeight = 13.sp, maxLines = 3,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                            // 就绪或受阻时给单独开跑的入口(受阻的常见操作就是改完再试一次)
                            if (status == KanbanTaskEntity.STATUS_READY || status == KanbanTaskEntity.STATUS_BLOCKED) {
                                Text(
                                    if (runner.isRunning) "排队中" else "▷ 立即执行",
                                    fontSize = 10.sp, fontFamily = Mono,
                                    color = if (runner.isRunning) xc.faint else xc.green,
                                    modifier = Modifier
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            if (!runner.isRunning) runner.runTask(task.id)
                                        }
                                        .padding(top = 4.dp)
                                )
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
        var assignee by remember(task.id) { mutableStateOf(task.assignee) }
        // SubAgentDao 只有 suspend getAll(没有 Flow),打开对话框时拉一次即可
        var subAgents by remember(task.id) { mutableStateOf<List<com.xincode.data.SubAgentEntity>>(emptyList()) }
        LaunchedEffect(task.id) {
            subAgents = withContext(Dispatchers.IO) {
                runCatching { database.subAgentDao().getAll() }.getOrDefault(emptyList())
            }
        }
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
                    Text("指派给(决定用谁的设定来执行)", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                    Column(Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth()
                            .background(if (assignee.isBlank()) xc.activeBg else xc.bg)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { assignee = "" }
                            .padding(6.dp)) {
                            Text("主智能体", fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                        }
                        subAgents.forEach { sa ->
                            Row(Modifier.fillMaxWidth()
                                .background(if (assignee == sa.name) xc.activeBg else xc.bg)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { assignee = sa.name }
                                .padding(6.dp)) {
                                Text(sa.name, fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                            }
                        }
                    }

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
                                    assignee = assignee,
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
