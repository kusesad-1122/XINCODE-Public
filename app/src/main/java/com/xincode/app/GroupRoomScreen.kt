package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.data.GroupMemberEntity
import com.xincode.data.GroupMessageEntity
import com.xincode.data.GroupRoomEntity
import com.xincode.data.MessageEntity
import com.xincode.provider.OpenAiClient
import com.xincode.security.KeystoreProvider
import com.xincode.tools.WorkspaceContext
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = XinUiFont

/** Workbench is a product surface, never a protocol/debug log surface. */
internal fun workbenchVisibleMessages(messages: List<MessageEntity>): List<MessageEntity> =
    messages.filter { message ->
        val content = message.content.trim()
        val rawToolProtocol = content.startsWith("{") &&
            (content.contains("\"__tool_call__\"") ||
                (content.contains("\"tool_name\"") && content.contains("\"params_summary\"")))
        message.role == "assistant" && content.isNotBlank() && !rawToolProtocol
    }

/** 房间列表。 */
@Composable
fun GroupRoomsScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    onBack: () -> Unit,
    /** 从侧栏直接点进某个房间时带的 id;非空则跳过列表直接进那间。 */
    initialRoomId: Long? = null,
    onConsumedInitialRoom: () -> Unit = {}
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as XincodeApplication
    val rooms by database.groupRoomDao().observeRooms().collectAsState(initial = emptyList())
    var openRoom by remember { mutableStateOf<Long?>(null) }
    // 侧栏点进来的房间只认一次:消费掉之后再返回列表就不会被弹回房间里
    LaunchedEffect(initialRoomId) {
        if (initialRoomId != null) { openRoom = initialRoomId; onConsumedInitialRoom() }
    }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    openRoom?.let { rid ->
        GroupRoomChatScreen(database, keystore, rid, onBack = { openRoom = null })
        return
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "群聊房间",
            subtitle = "让多个智能体在同一任务中协作",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            XinHeaderAction(label = "新建", onClick = { newName = ""; showAdd = true })
        }
        Text(
            "多个智能体同处一室,用 @名字 点谁谁回答,@所有人 叫全部。成员之间也能互相 @,讨论会自己往下走。",
            fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // 一键把预制团队摆好。自己一个个加成员太费事,而且加完还得挨个写身份卡。
        PresetTeam.TEAMS.forEach { team ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp)).background(xc.bgElevated)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        scope.launch {
                            val rid = withContext(Dispatchers.IO) { PresetTeam.install(database, team) }
                            openRoom = rid
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("＋ 装一个${team.roomName}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        fontFamily = Mono, color = xc.green)
                    Text(
                        team.roles.joinToString(" / ") { it.name },
                        fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    Text(
                        team.blurb,
                        fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (rooms.isEmpty()) {
                item {
                    Text("还没有房间。", fontSize = 12.sp, fontFamily = Mono, color = xc.sub,
                        modifier = Modifier.padding(vertical = 20.dp))
                }
            }
            items(rooms.size) { i ->
                val r = rooms[i]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(xc.bgElevated)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { openRoom = r.id }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
                        if (r.note.isNotBlank()) {
                            Text(r.note, fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    Text("删除", fontSize = 10.sp, fontFamily = Mono, color = xc.red,
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            app.stopGroupMessage(r.id)
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    deleteRoomAndWorkSessions(database, r)
                                }
                            }
                        })
                }
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新建房间", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
            text = {
                SimpleField(newName, { newName = it }, "房间名,例如:方案评审", xc)
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newName.trim()
                    if (n.isNotBlank()) scope.launch {
                        withContext(Dispatchers.IO) {
                            database.inTransaction {
                                val dao = database.groupRoomDao()
                                val id = dao.insertRoom(GroupRoomEntity(name = n))
                                dao.updateRoom(
                                    GroupRoomEntity(
                                        id = id,
                                        name = n,
                                        workspacePath = GroupRoomIsolation.defaultWorkspacePath(
                                            WorkspaceContext.workspaceRoot,
                                            n,
                                            id
                                        )
                                    )
                                )
                            }
                        }
                    }
                    showAdd = false
                }) { Text("创建", fontFamily = Mono, color = xc.green) }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消", fontFamily = Mono, color = xc.sub) } },
            containerColor = xc.bg
        )
    }
}

/** 房间内的对话。 */
@Composable
private fun GroupRoomChatScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    roomId: Long,
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    val messages by database.groupRoomDao().observeMessages(roomId).collectAsState(initial = emptyList())
    val members by database.groupRoomDao().observeMembers(roomId).collectAsState(initial = emptyList())
    val allIdentities by database.identityDao().observeAll().collectAsState(initial = emptyList())
    // 反过来:主对话专用的卡不进群聊成员选择器
    val identities = remember(allIdentities) {
        allIdentities.filter { it.scope != com.xincode.data.IdentityEntity.SCOPE_CHAT }
    }

    val app = LocalContext.current.applicationContext as XincodeApplication
    val room by database.groupRoomDao().observeRoom(roomId).collectAsState(initial = null)
    val providerConfigs by database.providerConfigDao().observeAll().collectAsState(initial = emptyList())

    var input by remember { mutableStateOf("") }
    val busy = app.isGroupRoomBusy(roomId)
    var showMembers by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMentionPicker by remember { mutableStateOf(false) }
    val speaking = app.groupRoomSpeaker(roomId)
    var expanding by remember { mutableStateOf(false) }
    // 内嵌工作台:非空时在群聊内部展开那个成员的干活现场,返回就回到这儿
    var workbenchFor by remember { mutableStateOf<GroupMemberEntity?>(null) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    // 规范排序:同一 run 的工具事件、diff 与最终正文按 phase 排在一起,不被并行回复插乱。
    val orderedMessages = remember(messages) { sortGroupMessagesCanonical(messages) }
    // 引用目标:点消息上的「引用」后,下一条用户消息会带引用块。
    var quoteTarget by remember { mutableStateOf<GroupMessageEntity?>(null) }
    // 消息操作菜单:复制 / 引用 / 重发。
    var menuFor by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(orderedMessages.size) {
        if (orderedMessages.isNotEmpty()) listState.animateScrollToItem(orderedMessages.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank()) return
        if (members.isEmpty()) return
        val qt = quoteTarget
        if (app.sendGroupMessage(
                roomId, text,
                replyToId = qt?.id ?: 0L,
                replyToSender = qt?.sender.orEmpty(),
                replyToContent = qt?.content.orEmpty()
            )
        ) {
            input = ""
            quoteTarget = null
        }
    }

    fun copyMessage(m: GroupMessageEntity) {
        clipboard.setText(AnnotatedString(m.content))
        menuFor = null
    }

    fun quoteMessage(m: GroupMessageEntity) {
        quoteTarget = m
        menuFor = null
    }

    fun resendMessage(m: GroupMessageEntity) {
        menuFor = null
        app.sendGroupMessage(roomId, m.content)
    }

    fun scrollToMessage(id: Long) {
        val idx = orderedMessages.indexOfFirst { it.id == id }
        if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
    }

    /** 把 @名字 插到输入框末尾,自动补空格,已经 @ 过就不重复插。 */
    fun insertMention(name: String) {
        val tag = "@$name"
        if (MentionRouting.isMentioned(input, name)) return
        input = (input.trimEnd() + " " + tag).trim() + " "
    }

    workbenchFor?.let { mem ->
        MemberWorkbench(database, mem, xc, onBack = { workbenchFor = null })
        return
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "群聊",
            subtitle = if (room?.fullAccess == true) "完全访问 · ${members.size} 位成员" else "${members.size} 位成员",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            XinHeaderAction(label = "设置", onClick = { showSettings = true })
            Spacer(Modifier.width(4.dp))
            XinHeaderAction(label = "成员", onClick = { showMembers = true })
        }

        if (members.isEmpty()) {
            Text("先添加成员(点右上角「成员」),否则 @ 谁都没人应。",
                fontSize = 11.sp, fontFamily = Mono, color = xc.red,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(orderedMessages.size) { i ->
                val m = orderedMessages[i]
                val isMine = m.sender.isBlank() && !m.isDigest

                // 摘要不走气泡:它不是谁说的话,是系统对前面一段的压缩,
                // 套上气泡反而像有人在发言。
                if (m.isDigest) {
                    Text("▸ 之前的对话摘要", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                    Text(m.content, fontSize = 11.sp, fontFamily = Mono,
                        color = xc.faint, lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
                } else if (m.kind == "diff") {
                    GroupDiffCard(m, xc)
                } else if (m.kind == "toolcall" || m.kind == "toolresult") {
                    GroupToolCard(m, xc)
                } else {
                    val speaker = members.firstOrNull { it.displayName == m.sender }
                    val hasBench = speaker != null && speaker.workSessionId > 0
                    val memberCfg = if (speaker != null) {
                        providerConfigs.firstOrNull { it.id == speaker.providerConfigId }
                            ?: providerConfigs.firstOrNull { it.isActive }
                    } else null
                    val displayModel = when {
                        speaker == null -> m.model
                        speaker.providerConfigId > 0L && memberCfg?.id != speaker.providerConfigId -> memberCfg?.model.orEmpty()
                        m.model.isBlank() -> memberCfg?.model.orEmpty()
                        else -> m.model
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start,
                        verticalAlignment = Alignment.Top
                    ) {
                        // 成员头像:左侧,AI 提供商标识;用户头像:右侧,RE。
                        if (!isMine) {
                            ProviderAvatar(
                                supplierId = memberCfg?.supplierId.orEmpty(),
                                size = 34.dp,
                                contentDescription = m.sender,
                                modifier = Modifier.padding(top = 16.dp, end = 6.dp)
                            )
                        }
                        Column(
                            Modifier.fillMaxWidth(0.88f),
                            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
                        ) {
                            if (!isMine) {
                                Text(
                                    buildString {
                                        append(m.sender)
                                        if (displayModel.isNotBlank()) append(" · ").append(displayModel)
                                        if (hasBench) append("  ›工作台")
                                        if (m.interrupted) append("  · 已中断")
                                    },
                                    fontSize = 10.sp, fontFamily = Mono, color = xc.green,
                                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { if (hasBench) workbenchFor = speaker }
                                )
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .wrapContentWidth(if (isMine) Alignment.End else Alignment.Start)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 12.dp, topEnd = 12.dp,
                                            // 靠自己那侧的角收窄,气泡才有指向感
                                            bottomStart = if (isMine) 12.dp else 3.dp,
                                            bottomEnd = if (isMine) 3.dp else 12.dp
                                        )
                                    )
                                    .background(if (isMine) xc.activeBg else xc.bgElevated)
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                // Box 是叠放布局,所有子项必须放进同一个 Column,否则引用块和正文会重叠
                                Column(Modifier.fillMaxWidth()) {
                                    // 引用块:先说「这是在回谁」,再是正文。多人同时被 @ 时,
                                    // 没有这一块根本分不清每条回复是在回应哪句原话。
                                    if (m.replyToContent.isNotBlank()) {
                                        Column(
                                            Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(xc.bg.copy(alpha = 0.55f))
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                                .clickable(
                                                    indication = null,
                                                    interactionSource = remember { MutableInteractionSource() }
                                                ) { if (m.replyToId > 0) scrollToMessage(m.replyToId) }
                                        ) {
                                            Text(
                                                "引用 ${m.replyToSender.ifBlank { "用户" }}",
                                                fontSize = 9.sp, fontFamily = Mono, color = xc.sub
                                            )
                                            Text(
                                                m.replyToContent,
                                                fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                                                lineHeight = 14.sp, maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(top = 2.dp)
                                            )
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    // 群成员的输出几乎必然带 Markdown,裸 Text 会把 **重点** 的星号显示出来
                                    MarkdownContent(m.content)
                                    if (m.streaming) {
                                        Text("正在生成…", fontSize = 10.sp, fontFamily = Mono, color = xc.green)
                                    }
                                    if (m.promptTokens > 0 || m.completionTokens > 0) {
                                        Text(
                                            "↑${m.promptTokens} ↓${m.completionTokens}",
                                            fontSize = 8.sp, fontFamily = Mono, color = xc.faint,
                                            modifier = Modifier.padding(top = 3.dp)
                                        )
                                    }
                                }
                            }
                            // 消息操作:复制 / 引用 / 重发
                            Row(
                                Modifier.padding(top = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (menuFor == m.id) "⋯" else "···",
                                    fontSize = 10.sp, fontFamily = Mono, color = xc.sub,
                                    modifier = Modifier.clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) { menuFor = if (menuFor == m.id) null else m.id }
                                )
                                if (menuFor == m.id) {
                                    Text("复制", fontSize = 10.sp, fontFamily = Mono, color = xc.sub,
                                        modifier = Modifier.clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { copyMessage(m) })
                                    Text("引用", fontSize = 10.sp, fontFamily = Mono, color = xc.sub,
                                        modifier = Modifier.clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { quoteMessage(m) })
                                    Text("重发", fontSize = 10.sp, fontFamily = Mono, color = xc.sub,
                                        modifier = Modifier.clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) { resendMessage(m) })
                                }
                            }
                        }
                        if (isMine) {
                            Spacer(Modifier.width(6.dp))
                            UserAvatar(
                                size = 34.dp,
                                contentDescription = "我",
                                modifier = Modifier.padding(top = 14.dp)
                            )
                        }
                    }
                }
            }
            val summarizing = app.groupRoomSummarizing(roomId)
            if (busy || summarizing) {
                item {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        if (summarizing) {
                            Text("正在总结房间历史…", fontSize = 10.sp, fontFamily = Mono, color = xc.yellow)
                        }
                        if (busy) {
                            Text(
                                if (speaking.isNotBlank()) "$speaking 正在输入…" else "…",
                                fontSize = 11.sp, fontFamily = Mono, color = xc.faint
                            )
                        }
                        val estimate = app.groupRoomContextEstimateText(roomId)
                        if (estimate.isNotBlank()) {
                            Text(estimate, fontSize = 9.sp, fontFamily = Mono, color = xc.faint)
                        }
                    }
                }
            }
        }

        // @ 选择卡片:贴着输入框往上弹,点名字就插进输入框
        if (showMentionPicker && members.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(xc.bgElevated).padding(vertical = 8.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("叫谁", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                    Spacer(Modifier.weight(1f))
                    Text("收起", fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                        modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            showMentionPicker = false
                        })
                }
                Row(
                    Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        insertMention(MentionRouting.ALL); showMentionPicker = false
                    }.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("@所有人", fontSize = 12.sp, fontFamily = Mono, color = xc.green)
                    Spacer(Modifier.width(8.dp))
                    Text("(${members.size} 个成员都会回)", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                }
                members.forEach { mem ->
                    Row(
                        Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            insertMention(mem.displayName); showMentionPicker = false
                        }.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("@${mem.displayName}", fontSize = 12.sp, fontFamily = Mono, color = xc.ink)
                    }
                }
            }
        }

        // 房间内工具确认卡:成员工作会话在等批准时,不必切去工作台。
        app.groupRoomConfirm(roomId)?.let { c ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(xc.bgElevated)
                    .border(1.dp, xc.border, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Text(
                    "等待确认 · ${c.memberName} · ${c.toolName}",
                    fontSize = 11.sp, fontFamily = Mono, color = xc.yellow
                )
                Text(
                    c.preview.take(220),
                    fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("允许一次", fontSize = 11.sp, fontFamily = Mono, color = xc.green,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            app.resolveGroupConfirm(c.sessionId, com.xincode.security.ToolConfirmResult.ALLOW_ONCE)
                        })
                    Text("始终允许", fontSize = 11.sp, fontFamily = Mono, color = xc.green,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            app.resolveGroupConfirm(c.sessionId, com.xincode.security.ToolConfirmResult.ALWAYS_ALLOW)
                        })
                    Text("拒绝", fontSize = 11.sp, fontFamily = Mono, color = xc.red,
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            app.resolveGroupConfirm(c.sessionId, com.xincode.security.ToolConfirmResult.DENY)
                        })
                }
            }
        }

        // 待发送的引用:显示在输入框上方,可取消。
        quoteTarget?.let { qt ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "引用 ${qt.sender.ifBlank { "我" }}: ${qt.content.take(48)}",
                    fontSize = 10.sp, fontFamily = Mono, color = xc.sub,
                    modifier = Modifier.weight(1f)
                )
                Text("✕", fontSize = 12.sp, fontFamily = Mono, color = xc.red,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { quoteTarget = null })
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("@", fontSize = 16.sp, fontFamily = Mono,
                color = if (members.isEmpty()) xc.faint else xc.green,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        if (members.isNotEmpty()) showMentionPicker = !showMentionPicker
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp))
            // 扩展议题:一句话的议题讨论不出东西,先让模型把它想周全
            Text(if (expanding) "…" else "✦", fontSize = 15.sp, fontFamily = Mono,
                color = if (expanding || input.isBlank()) xc.faint else xc.green,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        if (!expanding && input.isNotBlank()) {
                            expanding = true
                            scope.launch {
                                val r = PromptExpander.expand(
                                    database, keystore, PromptExpander.Kind.GROUP_TOPIC, input
                                )
                                r.getOrNull()?.let { input = it }
                                expanding = false
                            }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp))
            TextField(
                value = input, onValueChange = { input = it },
                placeholder = {
                    Text(
                        if (members.isEmpty()) "先添加成员" else "点左边的 @ 选人,或直接打字",
                        fontSize = 12.sp, fontFamily = Mono, color = xc.faint
                    )
                },
                modifier = Modifier.weight(1f).border(0.5.dp, xc.border, RoundedCornerShape(4.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = Mono)
            )
            // 跑着的时候变成停止 —— 连锁一旦滚起来,能随时叫停比什么都重要
            Text(if (busy) "停止" else "发送", fontSize = 13.sp, fontFamily = Mono,
                color = when {
                    busy -> xc.red
                    members.isEmpty() -> xc.faint
                    else -> xc.green
                },
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        if (busy) app.stopGroupMessage(roomId) else send()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }

    if (showSettings) {
        val r = room
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("房间设置", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
            text = {
                if (r == null) {
                    Text("加载中…", fontSize = 12.sp, fontFamily = Mono, color = xc.sub)
                } else {
                    Column {
                        ToggleRow(
                            "成员可以互相 @",
                            "开:成员 @ 别人时对方会真的被叫起来接话,讨论能自己推进。\n关:只有你的 @ 有效,成员说完就停。",
                            r.allowMemberMentions, xc
                        ) {
                            scope.launch { withContext(Dispatchers.IO) {
                                database.groupRoomDao().updateRoom(r.copy(allowMemberMentions = it, updatedAt = System.currentTimeMillis()))
                            } }
                        }
                        Spacer(Modifier.height(12.dp))
                        val unlimited = r.maxHops == GroupRoomEntity.UNLIMITED_HOPS
                        Text(
                            if (unlimited) "连锁跳数:不限" else "连锁最多几跳:${r.maxHops}",
                            fontSize = 12.sp, fontFamily = Mono, color = xc.ink
                        )
                        Text(
                            if (unlimited)
                                "讨论会一直往下走,直到没人再被 @,或者你点停止。适合让它们自己把事聊透。"
                            else
                                "你说一句后,成员之间最多再传几轮。到数就停,防止它们无限对聊。",
                            fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 14.sp
                        )
                        Row(Modifier.padding(top = 6.dp)) {
                            listOf(1, 2, 3, 5, 8, 15).forEach { n ->
                                Text(
                                    "$n", fontSize = 12.sp, fontFamily = Mono,
                                    color = if (r.maxHops == n) xc.green else xc.sub,
                                    modifier = Modifier
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            scope.launch { withContext(Dispatchers.IO) {
                                                database.groupRoomDao().updateRoom(r.copy(maxHops = n, updatedAt = System.currentTimeMillis()))
                                            } }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Text(
                                "不限", fontSize = 12.sp, fontFamily = Mono,
                                color = if (unlimited) xc.red else xc.sub,
                                modifier = Modifier
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        scope.launch { withContext(Dispatchers.IO) {
                                            database.groupRoomDao().updateRoom(
                                                r.copy(maxHops = GroupRoomEntity.UNLIMITED_HOPS, updatedAt = System.currentTimeMillis())
                                            )
                                        } }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        if (unlimited) {
                            Text(
                                "⚠ 无上限时请留意额度:成员互相 @ 是个闭环,你不看着它能一直聊下去。" +
                                    "仍保留一个跑飞兜底(单轮 500 条),但那是防事故的,不是给你当刹车用的。",
                                fontSize = 9.sp, fontFamily = Mono, color = xc.red, lineHeight = 13.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("工作区", fontSize = 12.sp, fontFamily = Mono, color = xc.ink)
                        Text(
                            GroupRoomEngine.workspaceOf(r),
                            fontSize = 9.sp, fontFamily = Mono, color = xc.green, lineHeight = 13.sp
                        )
                        Text(
                            "这屋人的产出都落在这里,成员之间共享 —— 架构师写的方案工程师要读得到。" +
                                "开了完全访问后,每个成员还会有自己的工作会话,干活的全过程在那里,群里只出现汇报。",
                            fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        Spacer(Modifier.height(12.dp))
                        ToggleRow(
                            "完全访问",
                            "让成员能调用工具:联网搜索、读写文件、执行命令。它们的动作会真的落到设备上,而且更慢更费额度。",
                            r.fullAccess, xc
                        ) {
                            scope.launch { withContext(Dispatchers.IO) {
                                database.groupRoomDao().updateRoom(r.copy(fullAccess = it, updatedAt = System.currentTimeMillis()))
                            } }
                        }

                        Spacer(Modifier.height(12.dp))
                        ToggleRow(
                            "滚动总结",
                            "每隔 N 轮把「旧总结 + 新消息」合并成新总结,历史投影只保留总结 + 游标后的原文;关掉则退回一次性压缩。",
                            r.summaryEnabled, xc
                        ) {
                            scope.launch { withContext(Dispatchers.IO) {
                                database.groupRoomDao().updateRoom(r.copy(summaryEnabled = it, updatedAt = System.currentTimeMillis()))
                            } }
                        }
                        if (r.summaryEnabled) {
                            Text(
                                "总结频率:每 ${r.summaryEveryTurns} 轮用户发言",
                                fontSize = 11.sp, fontFamily = Mono, color = xc.ink,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Row {
                                listOf(10, 20, 50).forEach { n ->
                                    Text(
                                        "$n", fontSize = 11.sp, fontFamily = Mono,
                                        color = if (r.summaryEveryTurns == n) xc.green else xc.sub,
                                        modifier = Modifier
                                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                                scope.launch { withContext(Dispatchers.IO) {
                                                    database.groupRoomDao().updateRoom(
                                                        r.copy(summaryEveryTurns = n, updatedAt = System.currentTimeMillis())
                                                    )
                                                } }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Text(
                                "总结模型(空 = 跟随活跃配置):${r.summaryModel.ifBlank { "默认" }}",
                                fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("关闭", fontFamily = Mono, color = xc.green) }
            },
            containerColor = xc.bg
        )
    }

    // 给某个成员单独选模型
    var modelPickerFor by remember { mutableStateOf<GroupMemberEntity?>(null) }
    modelPickerFor?.let { mem ->
        MemberModelPicker(database, keystore, app.openAiClient, mem, xc) { modelPickerFor = null }
    }

    if (showMembers) {
        var newMemberName by remember { mutableStateOf("") }
        var pickedIdentity by remember { mutableStateOf(0L) }
        var creatingIdentity by remember { mutableStateOf(false) }
        var pickedSkills by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(
            onDismissRequest = { showMembers = false },
            title = { Text("房间成员", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
            text = {
                Column {
                    members.forEach { mem ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("@${mem.displayName}", fontSize = 12.sp, fontFamily = Mono,
                                    color = xc.ink, modifier = Modifier.weight(1f))
                                // 工作台:点进去看他现在到底在干什么。只有跑过活的成员才有。
                                if (mem.workSessionId > 0) {
                                    Text("工作台", fontSize = 10.sp, fontFamily = Mono, color = xc.green,
                                        modifier = Modifier.padding(end = 10.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            showMembers = false
                                            workbenchFor = mem
                                        })
                                }
                                Text("换模型", fontSize = 10.sp, fontFamily = Mono, color = xc.sub,
                                    modifier = Modifier.padding(end = 10.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        showMembers = false; modelPickerFor = mem
                                    })
                                Text("移除", fontSize = 10.sp, fontFamily = Mono, color = xc.red,
                                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        scope.launch { withContext(Dispatchers.IO) { deleteMemberAndWorkSession(database, mem) } }
                                    })
                            }
                            Text(
                                buildString {
                                    val cfgName = providerConfigs.firstOrNull { it.id == mem.providerConfigId }?.name
                                        ?: providerConfigs.firstOrNull { it.isActive }?.name
                                    append(cfgName?.let { "配置商:$it · " } ?: "")
                                    append(if (mem.model.isBlank()) "模型:跟随配置默认" else "模型:${mem.model}")
                                    if (mem.workSessionId > 0) append("  ·  有独立工作会话")
                                },
                                fontSize = 9.sp, fontFamily = Mono, color = xc.faint
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("添加成员", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                    Spacer(Modifier.height(4.dp))
                    SimpleField(newMemberName, { newMemberName = it }, "群里的名字(用于 @)", xc)

                    // 技能多选:选中的会写进生成的身份卡里,并说明什么时候该调。
                    // 只把技能装进数据库是不够的 —— 模型不知道该在什么时候想起它们。
                    if (newMemberName.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("给它配技能(可多选)", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                        Column(Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState())) {
                            WorkSkills.SKILLS.forEach { sk ->
                                val on = sk.name in pickedSkills
                                Column(
                                    Modifier.fillMaxWidth()
                                        .background(if (on) xc.activeBg else xc.bg)
                                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                            pickedSkills = if (on) pickedSkills - sk.name else pickedSkills + sk.name
                                        }
                                        .padding(horizontal = 6.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        (if (on) "✓ " else "  ") + sk.name,
                                        fontSize = 11.sp, fontFamily = Mono,
                                        color = if (on) xc.green else xc.ink
                                    )
                                    Text(sk.desc, fontSize = 8.sp, fontFamily = Mono,
                                        color = xc.faint, lineHeight = 12.sp)
                                }
                            }
                        }
                    }

                    // 现造一张身份卡。不给这个入口的话,自建群聊只能从已有卡里挑,
                    // 而已有卡多半是给主对话写的、或者干脆没有 —— 于是自己建的房间里
                    // 全是没有性格的成员,和预制团队的差距全在这一步。
                    if (newMemberName.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (creatingIdentity) "正在生成「${newMemberName.trim()}」的身份卡…"
                            else "✦ 按这个名字生成一张身份卡",
                            fontSize = 11.sp, fontFamily = Mono,
                            color = if (creatingIdentity) xc.faint else xc.green,
                            modifier = Modifier.clickable(
                                indication = null, interactionSource = remember { MutableInteractionSource() }
                            ) {
                                if (!creatingIdentity) {
                                    creatingIdentity = true
                                    val roleName = newMemberName.trim()
                                    scope.launch {
                                        val r = PromptExpander.expand(
                                            database, keystore, PromptExpander.Kind.IDENTITY, roleName,
                                            skills = pickedSkills.toList()
                                        )
                                        r.onSuccess { body ->
                                            val newId = withContext(Dispatchers.IO) {
                                                database.identityDao().insert(
                                                    com.xincode.data.IdentityEntity(
                                                        name = roleName,
                                                        systemPrompt = body,
                                                        description = "群聊角色",
                                                        allowedTools = PresetTeam.DEFAULT_MEMBER_TOOLS,
                                                        scope = com.xincode.data.IdentityEntity.SCOPE_GROUP
                                                    )
                                                )
                                            }
                                            pickedIdentity = newId
                                        }
                                        creatingIdentity = false
                                    }
                                }
                            }
                        )
                        Text(
                            "它会补出这个角色盯什么、不管什么、什么时候该闭嘴 —— 群聊里最怕所有人说一样的话。",
                            fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text("身份卡(决定它的性格与专长)", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                    Column(Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                        Row(Modifier.fillMaxWidth()
                            .background(if (pickedIdentity == 0L) xc.activeBg else xc.bg)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { pickedIdentity = 0L }
                            .padding(6.dp)) {
                            Text("不指定(用默认设定)", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                        }
                        identities.forEach { idt ->
                            Row(Modifier.fillMaxWidth()
                                .background(if (pickedIdentity == idt.id) xc.activeBg else xc.bg)
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { pickedIdentity = idt.id }
                                .padding(6.dp)) {
                                Text(idt.name, fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newMemberName.trim()
                    // 名字必须房间内唯一,否则 @ 出来的目标是歧义的
                    if (n.isNotBlank() && members.none { it.displayName.equals(n, true) } &&
                        !n.equals(MentionRouting.ALL, true)) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                database.groupRoomDao().insertMember(
                                    GroupMemberEntity(roomId = roomId, displayName = n, identityId = pickedIdentity)
                                )
                            }
                        }
                        newMemberName = ""
                    }
                }) { Text("添加", fontFamily = Mono, color = xc.green) }
            },
            dismissButton = { TextButton(onClick = { showMembers = false }) { Text("关闭", fontFamily = Mono, color = xc.sub) } },
            containerColor = xc.bg
        )
    }
}

/**
 * 群聊内嵌的成员工作台。
 *
 * 【为什么内嵌而不是跳到主对话】把它做成「切到主对话页去看」的话,用户点一下就离开了
 * 群聊,回来还得自己找回去 —— 上下文断了,不熟悉的人根本绕不回来。工作台是「这个群聊
 * 成员的干活现场」,属于群聊内部的东西,就应该在群聊里展开、返回就回到原来的位置。
 *
 * 只读:这里是看他在干什么,不是跟他单独对话。要指挥他就回群里 @ 他。
 */
@Composable
private fun MemberWorkbench(
    database: AppDatabase,
    member: GroupMemberEntity,
    xc: XinColors,
    onBack: () -> Unit
) {
    val app = LocalContext.current.applicationContext as XincodeApplication
    val planState = remember(member.workSessionId) {
        app.planStateForSession(member.workSessionId)
    }
    val messages by database.messageDao()
        .observeBySessionId(member.workSessionId)
        .collectAsState(initial = emptyList())
    val visibleMessages = remember(messages) { workbenchVisibleMessages(messages) }
    val listState = rememberLazyListState()

    // 他还在干活时消息会不断追加,自动跟到底部,否则要一直手动往下拖
    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty()) listState.animateScrollToItem(visibleMessages.size - 1)
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        XinPageHeader(
            title = "${member.displayName} 的工作台",
            subtitle = "查看成员进度与结果 · 内部指令已隐藏",
            onBack = onBack,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        PlanCard(
            planState = planState,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        if (visibleMessages.isEmpty()) {
            Text("还没有记录 —— 他还没被叫去干过活。",
                fontSize = 12.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.padding(16.dp))
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(visibleMessages.size) { i ->
                val m = visibleMessages[i]
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        member.displayName,
                        fontSize = 9.sp, fontFamily = Mono,
                        color = xc.green
                    )
                    MarkdownContent(m.content, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Delete an internal member session before removing the relation that hides it. */
private suspend fun deleteMemberAndWorkSession(
    database: AppDatabase,
    member: GroupMemberEntity
) = database.inTransaction {
    if (member.workSessionId > 0) {
        database.messageDao().deleteBySessionId(member.workSessionId)
        database.sessionDao().getById(member.workSessionId)?.let { database.sessionDao().delete(it) }
    }
    database.groupRoomDao().deleteMember(member)
}

/** Delete a room and every internal work session atomically to avoid orphan chats. */
private suspend fun deleteRoomAndWorkSessions(
    database: AppDatabase,
    room: GroupRoomEntity
) = database.inTransaction {
    val dao = database.groupRoomDao()
    dao.getMembers(room.id).forEach { member ->
        if (member.workSessionId > 0) {
            database.messageDao().deleteBySessionId(member.workSessionId)
            database.sessionDao().getById(member.workSessionId)?.let { database.sessionDao().delete(it) }
        }
    }
    dao.deleteMembersOf(room.id)
    dao.deleteMessagesOf(room.id)
    dao.deleteSummary(room.id)
    dao.deleteRoom(room)
}

/**
 * 给单个成员挑供应商 + 模型。
 *
 * 每个角色配不同模型是有实际意义的:秘书只是记录,便宜快的模型就够;架构师和测试
 * 要真思考,值得上贵的。全房间一个模型的话,要么整体拉胯要么整体烧钱。
 */
@Composable
private fun MemberModelPicker(
    database: AppDatabase,
    keystore: KeystoreProvider,
    openAiClient: OpenAiClient,
    member: GroupMemberEntity,
    xc: XinColors,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val configs by database.providerConfigDao().observeAll().collectAsState(initial = emptyList())
    var pickedConfig by remember { mutableStateOf(member.providerConfigId) }
    var pickedModel by remember { mutableStateOf(member.model) }

    // 刷新拉回来的模型。切换供应商时清空 —— 留着上一个供应商的列表会让人选错。
    var fetched by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var manualId by remember { mutableStateOf("") }

    // Keep the model controls usable while following active, and recover gracefully if the
    // previously selected provider was deleted.
    val cfg = configs.firstOrNull { it.id == pickedConfig }
        ?: configs.firstOrNull { it.isActive }

    // 配置里【已启用】的模型。这是之前唯一的来源,也是「只显示一个模型」的原因:
    // 用户没在供应商页勾选多个时,这里就只有一个,而这个列表并不代表供应商真正提供了什么。
    val enabled = cfg?.enabledModelIds.orEmpty()

    // 在线列表成功后优先展示服务端结果；离线时再使用配置缓存。
    val models = remember(enabled, fetched, cfg) {
        ((if (fetched.isNotEmpty()) fetched else enabled) + listOfNotNull(cfg?.model?.takeIf { it.isNotBlank() })).distinct()
    }

    fun refresh() {
        val c = cfg ?: return
        val key = runCatching {
            keystore.decrypt(Base64.decode(c.apiKeyEnc, Base64.NO_WRAP))
        }.getOrNull()
        if (key.isNullOrBlank()) { status = "✗ 这个配置没有可用的 API Key"; return }
        loading = true; status = ""
        scope.launch {
            val r = openAiClient.listModels(c.baseUrl, key, c.supplierId)
            fetched = r.getOrDefault(emptyList())
            loading = false
            status = if (fetched.isEmpty())
                "✗ 拉不到列表,可在下面手填模型 ID"
            else "✓ 拉到 ${fetched.size} 个"
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("@${member.displayName} 用哪个模型", fontFamily = Mono, color = xc.ink, fontSize = 14.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("供应商", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                Row(Modifier.fillMaxWidth()
                    .background(if (pickedConfig == 0L) xc.activeBg else xc.bg)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        pickedConfig = 0L; pickedModel = ""; fetched = emptyList(); status = ""
                    }.padding(6.dp)) {
                    Text("跟随当前活跃配置", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                }
                configs.forEach { c ->
                    Row(Modifier.fillMaxWidth()
                        .background(if (pickedConfig == c.id) xc.activeBg else xc.bg)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            pickedConfig = c.id; pickedModel = ""; fetched = emptyList(); status = ""
                        }.padding(6.dp)) {
                        Text(c.name, fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                    }
                }

                if (cfg != null) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("模型", fontSize = 11.sp, fontFamily = Mono, color = xc.sub,
                            modifier = Modifier.weight(1f))
                        Text(
                            if (loading) "拉取中…" else "刷新列表",
                            fontSize = 10.sp, fontFamily = Mono,
                            color = if (loading) xc.faint else xc.green,
                            modifier = Modifier.clickable(
                                indication = null, interactionSource = remember { MutableInteractionSource() }
                            ) { if (!loading) refresh() }
                        )
                    }
                    if (status.isNotBlank()) {
                        Text(status, fontSize = 9.sp, fontFamily = Mono,
                            color = if (status.startsWith("✓")) xc.green else xc.red)
                    }

                    Row(Modifier.fillMaxWidth()
                        .background(if (pickedModel.isBlank()) xc.activeBg else xc.bg)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            pickedModel = ""
                        }.padding(6.dp)) {
                        Text("用该配置的默认模型", fontSize = 11.sp, fontFamily = Mono, color = xc.sub)
                    }
                    models.forEach { m ->
                        Row(Modifier.fillMaxWidth()
                            .background(if (pickedModel == m) xc.activeBg else xc.bg)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                pickedModel = m
                            }.padding(6.dp)) {
                            Text(m, fontSize = 11.sp, fontFamily = Mono, color = xc.ink)
                        }
                    }

                    // 手填:不少中转站不提供 /models,拉取永远是空的,
                    // 没有这个入口那些供应商就只能用默认模型 —— 和供应商配置页同一个理由。
                    Spacer(Modifier.height(8.dp))
                    Text("拉不到就手填模型 ID", fontSize = 10.sp, fontFamily = Mono, color = xc.faint)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            SimpleField(manualId, { manualId = it }, "例如 deepseek-chat", xc)
                        }
                        Text("加入", fontSize = 11.sp, fontFamily = Mono, color = xc.green,
                            modifier = Modifier
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    val id = manualId.trim()
                                    if (id.isNotBlank()) {
                                        // 直接选中,省得再点一次
                                        if (id !in models) fetched = fetched + id
                                        pickedModel = id
                                        manualId = ""
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val providerId = pickedConfig.takeIf { id -> id == 0L || configs.any { it.id == id } } ?: 0L
                        database.groupRoomDao().updateMember(
                            member.copy(providerConfigId = providerId, model = pickedModel)
                        )
                    }
                    onClose()
                }
            }) { Text("保存", fontFamily = Mono, color = xc.green) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消", fontFamily = Mono, color = xc.sub) } },
        containerColor = xc.bg
    )
}

/** 带说明文字的开关行。说明写清楚开关各自的后果,而不是只给个名字。 */
@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    xc: XinColors,
    onChange: (Boolean) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().clickable(
            indication = null, interactionSource = remember { MutableInteractionSource() }
        ) { onChange(!checked) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 12.sp, fontFamily = Mono, color = xc.ink, modifier = Modifier.weight(1f))
            Text(if (checked) "开" else "关", fontSize = 12.sp, fontFamily = Mono,
                color = if (checked) xc.green else xc.sub)
        }
        Text(desc, fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun SimpleField(value: String, onChange: (String) -> Unit, hint: String, xc: XinColors) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, fontSize = 12.sp, fontFamily = Mono, color = xc.faint) },
        modifier = Modifier.fillMaxWidth().border(0.5.dp, xc.border, RoundedCornerShape(4.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            cursorColor = xc.ink, focusedTextColor = xc.ink, unfocusedTextColor = xc.ink,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, fontFamily = Mono)
    )
}

/** 工作区变更卡:显示成员这轮改动了哪些文件(A/M/D),不进普通气泡。 */
@Composable
private fun GroupDiffCard(m: GroupMessageEntity, xc: XinColors) {
    val lines = m.content.lineSequence().toList()
    val title = lines.firstOrNull() ?: "工作区变更"
    val entries = lines.drop(1)
    Column(
        Modifier.fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(12.dp))
            .background(xc.bgElevated)
            .border(1.dp, xc.border, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(title, fontSize = 11.sp, fontFamily = Mono, color = xc.yellow, fontWeight = FontWeight.Bold)
        entries.forEach { line ->
            val op = line.take(1)
            val path = line.drop(2)
            Text(
                path,
                fontSize = 10.sp, fontFamily = Mono,
                color = when (op) {
                    "A" -> xc.green
                    "M" -> xc.yellow
                    "D" -> xc.red
                    else -> xc.sub
                },
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 工具事件卡:调用参数/退出码与输出,点击展开,不进普通气泡。 */
@Composable
private fun GroupToolCard(m: GroupMessageEntity, xc: XinColors) {
    var expanded by remember(m.id) { mutableStateOf(false) }
    val firstLine = m.content.lineSequence().firstOrNull().orEmpty()
    Column(
        Modifier.fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(12.dp))
            .background(xc.bgElevated)
            .border(1.dp, xc.border, RoundedCornerShape(12.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                expanded = !expanded
            }
            .padding(10.dp)
    ) {
        Text(
            if (m.kind == "toolcall") "工具调用 · $firstLine" else "工具结果 · $firstLine",
            fontSize = 10.sp, fontFamily = Mono, color = xc.green, fontWeight = FontWeight.Bold
        )
        Text(
            m.content.lineSequence().drop(1).joinToString("\n"),
            fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}
