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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.data.GroupMemberEntity
import com.xincode.data.GroupMessageEntity
import com.xincode.data.GroupRoomEntity
import com.xincode.data.SessionEntity
import com.xincode.provider.OpenAiClient
import com.xincode.security.KeystoreProvider
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/** 房间列表。 */
@Composable
fun GroupRoomsScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    onBack: () -> Unit,
    /** 打开某个成员的工作台后,由外层把界面切到主对话页。 */
    onOpenWorkbench: () -> Unit = {}
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    val rooms by database.groupRoomDao().observeRooms().collectAsState(initial = emptyList())
    var openRoom by remember { mutableStateOf<Long?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    openRoom?.let { rid ->
        GroupRoomChatScreen(database, keystore, rid, onBack = { openRoom = null }, onOpenWorkbench = onOpenWorkbench)
        return
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            Text("群聊房间", fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Text("+ 新建", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    newName = ""; showAdd = true
                })
        }
        Text(
            "多个智能体同处一室,用 @名字 点谁谁回答,@所有人 叫全部。成员之间也能互相 @,讨论会自己往下走。",
            fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // 一键把六个角色摆好。自己一个个加成员太费事,而且加完还得挨个写身份卡。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp)).background(xc.bgElevated)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    scope.launch {
                        val rid = withContext(Dispatchers.IO) { PresetTeam.install(database) }
                        openRoom = rid
                    }
                }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("＋ 装一个产品团队", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    fontFamily = Mono, color = xc.green)
                Text(
                    PresetTeam.ROLES.joinToString(" / ") { it.name },
                    fontSize = 10.sp, fontFamily = Mono, color = xc.faint,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Text(
                    "每个角色都配好了专属身份卡,关注点各不相同。已装过则直接进房间。",
                    fontSize = 9.sp, fontFamily = Mono, color = xc.faint, lineHeight = 13.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
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
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val dao = database.groupRoomDao()
                                    // 成员和消息要一起删,否则留下永远看不到的孤儿行
                                    dao.deleteMembersOf(r.id); dao.deleteMessagesOf(r.id); dao.deleteRoom(r)
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
                            database.groupRoomDao().insertRoom(GroupRoomEntity(name = n))
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
    onBack: () -> Unit,
    onOpenWorkbench: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    val messages by database.groupRoomDao().observeMessages(roomId).collectAsState(initial = emptyList())
    val members by database.groupRoomDao().observeMembers(roomId).collectAsState(initial = emptyList())
    val identities by database.identityDao().observeAll().collectAsState(initial = emptyList())

    val app = LocalContext.current.applicationContext as XincodeApplication
    val room by database.groupRoomDao().observeRoom(roomId).collectAsState(initial = null)

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showMentionPicker by remember { mutableStateOf(false) }
    var speaking by remember { mutableStateOf("") }
    // 持有正在跑的那条链,停止按钮要能掐断它
    var runningJob by remember { mutableStateOf<Job?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send() {
        val text = input.trim()
        if (text.isBlank() || busy) return
        if (members.isEmpty()) return
        input = ""
        busy = true
        runningJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.groupRoomDao().insertMessage(
                        GroupMessageEntity(roomId = roomId, sender = "", content = text)
                    )
                }
                GroupRoomEngine.onMessage(
                    database, keystore, roomId, text, senderName = "",
                    runWorkTurn = { sid, prompt -> app.runGroupWorkTurn(sid, prompt) },
                    ensureWorkSession = { mem -> ensureWorkSession(database, roomId, mem) },
                    onSpeaking = { speaking = it }
                )
            } finally {
                // 停止时这里也要跑到,否则界面永远停在「正在输入」
                busy = false
                speaking = ""
                runningJob = null
            }
        }
    }

    /** 把 @名字 插到输入框末尾,自动补空格,已经 @ 过就不重复插。 */
    fun insertMention(name: String) {
        val tag = "@$name"
        if (MentionRouting.isMentioned(input, name)) return
        input = (input.trimEnd() + " " + tag).trim() + " "
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
            if (room?.fullAccess == true) {
                Text("完全访问", fontSize = 10.sp, fontFamily = Mono, color = xc.red,
                    modifier = Modifier.padding(end = 10.dp))
            }
            Text("设置", fontSize = 12.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.padding(end = 12.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showSettings = true })
            Text("成员 ${members.size}", fontSize = 12.sp, fontFamily = Mono, color = xc.green,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { showMembers = true })
        }

        if (members.isEmpty()) {
            Text("先添加成员(点右上角「成员」),否则 @ 谁都没人应。",
                fontSize = 11.sp, fontFamily = Mono, color = xc.red,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages.size) { i ->
                val m = messages[i]
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            m.isDigest -> "▸ 之前的对话摘要"
                            m.sender.isBlank() -> "你"
                            else -> m.sender
                        },
                        fontSize = 10.sp, fontFamily = Mono,
                        color = if (m.sender.isBlank()) xc.sub else xc.green
                    )
                    // 群成员的输出和主对话一样几乎必然带 Markdown,
                    // 用裸 Text 会把 **重点** 的星号原样显示出来。
                    if (m.isDigest) {
                        Text(m.content, fontSize = 12.sp, fontFamily = Mono,
                            color = xc.faint, lineHeight = 18.sp,
                            modifier = Modifier.padding(top = 2.dp))
                    } else {
                        MarkdownContent(m.content, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            if (busy) {
                item {
                    Text(
                        if (speaking.isNotBlank()) "$speaking 正在输入…" else "…",
                        fontSize = 11.sp, fontFamily = Mono, color = xc.faint,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
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
                        if (busy) runningJob?.cancel() else send()
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
                                            app.switchToSession(mem.workSessionId)
                                            onOpenWorkbench()
                                        })
                                }
                                Text("换模型", fontSize = 10.sp, fontFamily = Mono, color = xc.sub,
                                    modifier = Modifier.padding(end = 10.dp).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        showMembers = false; modelPickerFor = mem
                                    })
                                Text("移除", fontSize = 10.sp, fontFamily = Mono, color = xc.red,
                                    modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        scope.launch { withContext(Dispatchers.IO) { database.groupRoomDao().deleteMember(mem) } }
                                    })
                            }
                            Text(
                                buildString {
                                    append(if (mem.model.isBlank()) "模型:跟随活跃配置" else "模型:${mem.model}")
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
 * 确保成员有自己的工作会话,返回会话 id。
 *
 * 会话标题带上房间名和成员名,因为它会出现在主对话的会话列表里 ——
 * 一堆没头没尾的会话比没有更糟,你得一眼看出这是谁的工作台。
 */
private suspend fun ensureWorkSession(
    database: AppDatabase,
    roomId: Long,
    member: GroupMemberEntity
): Long = withContext(Dispatchers.IO) {
    if (member.workSessionId > 0) return@withContext member.workSessionId
    val roomName = database.groupRoomDao().getRoom(roomId)?.name ?: "群聊"
    val sid = database.sessionDao().upsert(
        SessionEntity(title = "🔧 $roomName · ${member.displayName}")
    )
    database.groupRoomDao().updateMember(member.copy(workSessionId = sid))
    sid
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

    val cfg = configs.firstOrNull { it.id == pickedConfig }

    // 配置里【已启用】的模型。这是之前唯一的来源,也是「只显示一个模型」的原因:
    // 用户没在供应商页勾选多个时,这里就只有一个,而这个列表并不代表供应商真正提供了什么。
    val enabled = remember(cfg) {
        runCatching {
            val arr = org.json.JSONArray(cfg?.enabledModelIds ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    // 已启用 + 刚拉到的 + 配置自己的默认模型,去重后一起给用户选
    val models = remember(enabled, fetched, cfg) {
        (enabled + fetched + listOfNotNull(cfg?.model?.takeIf { it.isNotBlank() })).distinct()
    }

    fun refresh() {
        val c = cfg ?: return
        val key = runCatching {
            keystore.decrypt(Base64.decode(c.apiKeyEnc, Base64.NO_WRAP))
        }.getOrNull()
        if (key.isNullOrBlank()) { status = "✗ 这个配置没有可用的 API Key"; return }
        loading = true; status = ""
        scope.launch {
            val r = openAiClient.listModels(c.baseUrl, key)
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

                if (pickedConfig != 0L) {
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
                        database.groupRoomDao().updateMember(
                            member.copy(providerConfigId = pickedConfig, model = pickedModel)
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
