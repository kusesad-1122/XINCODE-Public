package com.xincode.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.data.AppDatabase
import com.xincode.data.GroupMemberEntity
import com.xincode.data.GroupMessageEntity
import com.xincode.data.GroupRoomEntity
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/** 房间列表。 */
@Composable
fun GroupRoomsScreen(
    database: AppDatabase,
    keystore: KeystoreProvider,
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    val rooms by database.groupRoomDao().observeRooms().collectAsState(initial = emptyList())
    var openRoom by remember { mutableStateOf<Long?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    openRoom?.let { rid ->
        GroupRoomChatScreen(database, keystore, rid, onBack = { openRoom = null })
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
            "多个智能体同处一室,用 @名字 点谁谁回答,@all 叫全部。历史过长会自动压缩。",
            fontSize = 10.sp, fontFamily = Mono, color = xc.faint, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

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
    onBack: () -> Unit
) {
    val xc = LocalXinColors.current
    val scope = rememberCoroutineScope()
    val messages by database.groupRoomDao().observeMessages(roomId).collectAsState(initial = emptyList())
    val members by database.groupRoomDao().observeMembers(roomId).collectAsState(initial = emptyList())
    val identities by database.identityDao().observeAll().collectAsState(initial = emptyList())

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
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
        scope.launch {
            withContext(Dispatchers.IO) {
                database.groupRoomDao().insertMessage(
                    GroupMessageEntity(roomId = roomId, sender = "", content = text)
                )
            }
            GroupRoomEngine.onMessage(database, keystore, roomId, text, senderName = "")
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(xc.bg)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", fontSize = 13.sp, fontFamily = Mono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onBack() })
            Spacer(Modifier.weight(1f))
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
                    Text(m.content, fontSize = 12.sp, fontFamily = Mono,
                        color = if (m.isDigest) xc.faint else xc.ink, lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (busy) {
                item {
                    Text("…", fontSize = 14.sp, fontFamily = Mono, color = xc.faint,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input, onValueChange = { input = it },
                placeholder = {
                    Text(
                        if (members.isEmpty()) "先添加成员" else "@${members.firstOrNull()?.displayName ?: "名字"} 说点什么",
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
            Text(if (busy) "…" else "发送", fontSize = 13.sp, fontFamily = Mono,
                color = if (busy || members.isEmpty()) xc.faint else xc.green,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { send() }
                    .padding(horizontal = 12.dp, vertical = 8.dp))
        }
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
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("@${mem.displayName}", fontSize = 12.sp, fontFamily = Mono,
                                color = xc.ink, modifier = Modifier.weight(1f))
                            Text("移除", fontSize = 10.sp, fontFamily = Mono, color = xc.red,
                                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    scope.launch { withContext(Dispatchers.IO) { database.groupRoomDao().deleteMember(mem) } }
                                })
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
