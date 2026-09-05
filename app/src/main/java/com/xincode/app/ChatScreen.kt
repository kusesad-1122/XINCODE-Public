package com.xincode.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.graphics.asImageBitmap
import com.xincode.data.ProjectEntity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.xincode.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import androidx.compose.ui.text.style.TextOverflow

/** Pending attachment waiting to be sent with the next message. */
data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val absolutePath: String = "",
    val sizeBytes: Long = 0,
    val mimeType: String = "",
    val content: String
)

/**
 * 处理选择器返回的一个 URI。**必须在 IO 线程调用**(内部有同步文件读写)。
 *
 * 大小【不设上限】(用户要求)。真正做到无上限的办法不是"敢读多大读多大",而是让大文件
 * 根本不进消息体:
 *  - 图片:流式复制到私有目录,只记路径,由 describe_image 按需读;
 *  - 文本:小文件照旧内联进消息(方便直接看);超过 [INLINE_TEXT_LIMIT] 的同样只落盘给路径,
 *    让模型用 file_read 按需读、分段读。否则几 MB 的日志会把上下文顶爆,
 *    换来一个来自服务端的 context_length_exceeded —— 报错指向不明,用户根本猜不到是附件太大。
 *
 * 这里的阈值只决定"内联还是给路径",不拦截任何文件。
 */
private const val INLINE_TEXT_LIMIT = 256 * 1024

private suspend fun processAttachmentUri(
    context: android.content.Context,
    uri: Uri,
    pending: MutableState<List<Attachment>>
) {
    // Toast 必须回主线程弹,否则在 IO 线程上没有 Looper 会直接抛异常。
    suspend fun toast(msg: String, long: Boolean = false) = withContext(Dispatchers.Main) {
        Toast.makeText(context, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }
    suspend fun addAttachment(a: Attachment) = withContext(Dispatchers.Main) {
        pending.value = pending.value + a
    }

    val resolver = context.contentResolver
    var fileName = "unknown"
    var size = 0L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: "unknown"
            if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
        }
    }

    val mime = resolver.getType(uri).orEmpty()
    val ext = fileName.substringAfterLast('.', "").lowercase()

    /** 流式复制到应用私有目录,返回落盘后的文件;失败返回 null。全程不占内存。 */
    fun copyToPrivate(): java.io.File? {
        return try {
            val dir = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = java.io.File(dir, "${System.currentTimeMillis()}_$safeName")
            val stream = resolver.openInputStream(uri) ?: return null
            stream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            dest
        } catch (_: Exception) { null }
    }

    // ---- 图片:落盘存路径,不读内容 ----
    // 以前图片和文本文件走同一条路,两道坎都过不去:图片扩展名不在白名单里会被拒,
    // 就算放行,reader().readText() 把二进制按 UTF-8 读出来也只是一堆乱码。
    if (mime.startsWith("image/") || ext in imageExts) {
        val dest = copyToPrivate() ?: run { toast("无法读取图片: $fileName"); return }
        addAttachment(Attachment(
            fileName = fileName,
            absolutePath = dest.absolutePath,
            sizeBytes = if (size > 0) size else dest.length(),
            mimeType = mime.ifBlank { "image/$ext" },
            content = ""      // 图片内容不入消息,只留路径
        ))
        return
    }

    // ---- 文本文件:白名单 + 按大小决定内联还是给路径 ----
    val nameNoExt = fileName.substringBeforeLast('.')
    val allowed = whiteList.contains(ext) || whiteListNoExt.contains(nameNoExt) ||
        whiteListNoExt.contains(fileName)
    if (!allowed) {
        toast("暂不支持该文件类型: $fileName")
        return
    }

    // 大文本走路径,不读进内存 —— 既不会 OOM,也不会顶爆上下文。
    if (size > INLINE_TEXT_LIMIT) {
        val dest = copyToPrivate() ?: run { toast("读取失败: $fileName"); return }
        addAttachment(Attachment(
            fileName = fileName,
            absolutePath = dest.absolutePath,
            sizeBytes = if (size > 0) size else dest.length(),
            mimeType = mime,
            content = ""
        ))
        toast("文件较大,已按路径附带,AI 会按需读取")
        return
    }

    try {
        val content = resolver.openInputStream(uri)?.use { it.reader().readText() } ?: ""
        addAttachment(Attachment(
            fileName = fileName,
            sizeBytes = size,
            mimeType = mime,
            content = content
        ))
    } catch (e: OutOfMemoryError) {
        // size 取不到(部分 provider 不给 SIZE 列)时可能漏过上面的阈值判断,这里兜底。
        toast("文件太大,内存装不下:$fileName", long = true)
    } catch (e: Exception) {
        toast("读取失败: ${e.message}")
    }
}

private val whiteList = setOf(
    "txt","md","markdown","log","json","yaml","yml","toml","ini","properties","conf","cfg",
    "xml","html","htm","csv","tsv","kt","java","py","sh","bash","zsh","rs","go","c","cpp",
    "cc","h","hpp","js","ts","tsx","jsx","css","scss","less","vue","svelte","gradle",
    "gradle.kts","pro","cmake","dockerfile","gitignore","gitattributes","editorconfig",
    "sql","graphql","gql","env","env.example"
)
private val whiteListNoExt = setOf("README","LICENSE","Makefile","Dockerfile","CMakeLists.txt")

/** 图片扩展名。MIME 缺失时(部分文件管理器不给 type)靠它兜底判断。 */
private val imageExts = setOf("jpg","jpeg","png","webp","gif","bmp","heic","heif","avif")

/** 人类可读体积,用于附件 chip。 */
private fun humanSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1fG", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format("%.1fM", bytes / (1024.0 * 1024))
    bytes >= 1024L -> "${bytes / 1024}K"
    else -> "${bytes}B"
}

// Palette now sourced from [LocalXinColors] — supports light/dark switching.
// Kept as local vals inside each composable so existing code paths compile unchanged.

private val JetBrainsMono = XinUiFont

// -- Unified icon button (terminal aesthetic, all monochrome) --

@Composable
private fun XinIcon(
    icon: ImageVector,
    size: Dp = 18.dp,
    tint: Color = LocalXinColors.current.ink,
    onClick: (() -> Unit)? = null,
    contentDescription: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val mod = if (onClick != null) {
        Modifier.clickable(indication = null, interactionSource = interactionSource) { onClick() }
    } else Modifier
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = mod.size(size).padding(2.dp),
        tint = tint
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatState: ChatStateLike,
    conversationTitle: String = "新聊天",
    assistantName: String = "默认助手",
    currentModel: String = "",
    supplierId: String = "",
    providerName: String = "",
    availableModels: List<String> = emptyList(),
    onSwitchModel: (String) -> Unit = {},
    /** 打开「本对话的供应商/模型选择器」(支持跨厂商切换)。 */
    onOpenConversationModelPicker: () -> Unit = {},
    thinkingEnabled: Boolean = false,
    thinkingLevel: Int = 2,
    onThinkingEnabledChange: (Boolean) -> Unit = {},
    onThinkingLevelChange: (Int) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWorkflow: () -> Unit = {},
    onNavigateToGoal: () -> Unit = {},
    onNavigateToAgentScene: () -> Unit = {},
    onNavigateToStats: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {},
    subAgentActive: Boolean = false,
    ttsHelper: TtsHelper? = null,
    voiceInputHelper: VoiceInputHelper? = null,
    powerMode: com.xincode.core.PowerMode = com.xincode.core.PowerMode.NORMAL,
    onOpenDrawer: () -> Unit = {},
    onNewChat: () -> Unit = {},
    planState: PlanState? = null,
    tokenStats: TokenStats = TokenStats.EMPTY,
    onRegenerate: (Long) -> Unit = {},
    onDeleteMessage: (Long) -> Unit = {},
    onCompactContext: () -> Unit = {},
    skillNames: List<String> = emptyList(),
    onInsertSkill: (String) -> Unit = {},
    mcpNames: List<String> = emptyList(),
    onInsertMcp: (String) -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onSetConversationWorkspace: (String) -> Unit = {},
    onSetWebSearchEnabled: (Boolean) -> Unit = {},
    // 计划模式:输入框内切换。PLAN=只读+规划、不执行写/命令;ASK=正常聊天。
    permissionMode: com.xincode.security.PermissionMode = com.xincode.security.PermissionMode.ASK,
    onUpdatePermissionMode: (com.xincode.security.PermissionMode) -> Unit = {},
    // 协作模式(主脑+子智能体):与计划模式一起在输入框模式卡片里选。
    collabMode: Boolean = false,
    onSetCollabMode: (Boolean) -> Unit = {},
    // ---- Goal/Work 模式 ----
    isGoalSession: Boolean = false,
    goalStatusCode: String = "",          // ""/running/achieved/failed(来自 DB)
    goalLiveText: String = "",            // 实时状态小字(第 N 轮/裁判评估中…)
    goalRunning: Boolean = false,
    onStartGoal: (String) -> Unit = {},
    onStopGoal: () -> Unit = {},
    projects: List<ProjectEntity> = emptyList(),
    currentProjectId: Long? = null,
    onMoveSessionToProject: (Long, Long?) -> Unit = { _, _ -> },
    onRenameSession: (Long, String) -> Unit = { _, _ -> },
    onDeleteSession: (Long) -> Unit = {},
    onTogglePin: (Long, Boolean) -> Unit = { _, _ -> },
    isStarred: Boolean = false,
    currentSessionId: Long = 0L
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    // 回车行为开关(App 层可观察设置):true=回车发送;false=回车换行。读它即响应式。
    val enterToSend = (context.applicationContext as XincodeApplication).enterToSend
    var pendingModelIdx by remember { mutableStateOf<String?>(null) }
    val xc = LocalXinColors.current
    val Bg = xc.bg
    val Ink = xc.ink
    val Sub = xc.sub
    val Faint = xc.faint
    val Green = xc.green
    val Red = xc.red
    val Border = xc.border

    // Menu visibility
    var showMainMenu by remember { mutableStateOf(false) }
    var effortExpanded by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showSelectModelSheet by remember { mutableStateOf(false) }
    var showEffortSheet by remember { mutableStateOf(false) }
    var showToolAccessSheet by remember { mutableStateOf(false) }
    var showAddToProjectSheet by remember { mutableStateOf(false) }
    var renameSessionDialog by remember { mutableStateOf(false) }
    var renameSessionTitle by remember { mutableStateOf("") }
    var toolAccessMode by remember { mutableStateOf("Always available") }

    val voiceState = voiceInputHelper?.state?.collectAsState()?.value ?: VoiceInputHelper.State.IDLE
    val voicePartialText = voiceInputHelper?.partialText?.collectAsState()?.value.orEmpty()
    val voiceFinalText = voiceInputHelper?.finalText?.collectAsState()?.value.orEmpty()
    val voiceErrorText = voiceInputHelper?.errorMsg?.collectAsState()?.value.orEmpty()
    val voiceFeedback = voiceUiFeedback(voiceState, voicePartialText, voiceErrorText)
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) voiceInputHelper?.startListening()
        else Toast.makeText(context, "需要录音权限才能使用语音输入", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(voiceFinalText) {
        if (voiceFinalText.isNotBlank()) {
            chatState.input.value = listOf(chatState.input.value.trim(), voiceFinalText.trim())
                .filter { it.isNotBlank() }
                .joinToString(" ")
            voiceInputHelper?.reset()
        }
    }

    LaunchedEffect(voiceState, voiceErrorText) {
        if (voiceState == VoiceInputHelper.State.ERROR && voiceErrorText.isNotBlank()) {
            Toast.makeText(context, voiceErrorText, Toast.LENGTH_LONG).show()
        }
    }

    // TTS state
    val ttsEnabled by (ttsHelper?.enabled?.collectAsState() ?: remember { mutableStateOf(false) })
    val lastMsgCount = remember { mutableStateOf(0) }

    // Live token stats + 上下文占用 —— 消息变化时刷新;流式期间每秒轮询一次(修「刷新不及时」)。
    var liveTokenStats by remember { mutableStateOf(tokenStats) }
    var contextUsage by remember { mutableStateOf(ContextUsage.EMPTY) }
    LaunchedEffect(chatState.messages.size, chatState.isStreaming.value) {
        val agentChat = chatState as? AgentChatState ?: return@LaunchedEffect
        do {
            liveTokenStats = agentChat.getSessionTokenStats()
            contextUsage = agentChat.getContextUsage()
            if (chatState.isStreaming.value) kotlinx.coroutines.delay(500)
        } while (chatState.isStreaming.value)
    }

    // ---- attachments ----
    val pendingAttachments = remember { mutableStateOf<List<Attachment>>(emptyList()) }
    // 附件读取一律走 IO 线程。ActivityResult 回调跑在主线程,而复制/读取都是同步 IO——
    // 取消大小上限之后,选一个大文件会直接把 UI 卡死触发 ANR。原来有 200KB 限制时
    // 这个问题被掩盖着,现在必须显式挪走。
    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { result: List<Uri>? ->
        if (result == null) return@rememberLauncherForActivityResult
        scope.launch {
            result.forEach { uri ->
                withContext(Dispatchers.IO) { processAttachmentUri(context, uri, pendingAttachments) }
            }
        }
    }
    // 相册取图。
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { processAttachmentUri(context, uri, pendingAttachments) }
        }
    }
    // 相机拍照后直接复制到应用私有附件目录，保持与相册图片相同的按需读取路径。
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: android.graphics.Bitmap? ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        scope.launch {
            val attachment = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
                    val file = java.io.File(dir, "camera_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { output ->
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)) { "拍照图片保存失败" }
                    }
                    Attachment(
                        fileName = file.name,
                        absolutePath = file.absolutePath,
                        sizeBytes = file.length(),
                        mimeType = "image/jpeg",
                        content = ""
                    )
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                if (attachment != null) pendingAttachments.value = pendingAttachments.value + attachment
                else Toast.makeText(context, "拍照图片保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) cameraLauncher.launch(null)
        else Toast.makeText(context, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }

    // 「+」卡片 + 文件夹/技能选择器 状态;联网搜索、深度分析 开关。
    var showPlusCard by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showSkillPicker by remember { mutableStateOf(false) }
    var showMcpPicker by remember { mutableStateOf(false) }
    var showStatsPopup by remember { mutableStateOf(false) }   // 点圆环弹出的统计卡片
    var expandingPrompt by remember { mutableStateOf(false) }  // 正在扩展提示词
    var promptExpansionJob by remember { mutableStateOf<Job?>(null) }
    var showInspirationMenu by remember { mutableStateOf(false) }
    var showModeCard by remember { mutableStateOf(false) }     // 计划/协作模式卡片
    var webSearchOn by remember { mutableStateOf(com.xincode.tools.WebSearchGate.enabled) }

    LaunchedEffect(chatState.messages.size, ttsEnabled) {
        val newCount = chatState.messages.size
        if (newCount > lastMsgCount.value && ttsEnabled && !chatState.isStreaming.value) {
            val lastMsg = chatState.messages.lastOrNull()
            if (lastMsg != null && lastMsg.role == "assistant" && lastMsg.content.isNotBlank()) {
                ttsHelper?.speak(lastMsg.content)
            }
        }
        lastMsgCount.value = newCount
    }

    // Model display name (shorten long IDs smartly)
    val modelDisplayName = remember(currentModel) {
        when {
            currentModel.length <= 15 -> currentModel
            currentModel.startsWith("deepseek-") -> currentModel.removePrefix("deepseek-")
            else -> {
                val parts = currentModel.split("-")
                if (parts.size >= 3) parts.takeLast(2).joinToString("-")
                else currentModel.take(15)
            }
        }
    }
    val effortLabels = listOf("低", "中", "高", "超高", "极致")
    val effortLabel = thinkingLevelLabel(thinkingLevel)

    fun expandCurrentPrompt() {
        val draft = chatState.input.value.trim()
        if (draft.isBlank()) {
            showInspirationMenu = true
            return
        }
        if (expandingPrompt) {
            promptExpansionJob?.cancel()
            return
        }
        showInspirationMenu = false
        expandingPrompt = true
        Toast.makeText(context, "正在优化提示词…", Toast.LENGTH_SHORT).show()
        promptExpansionJob = scope.launch {
            try {
                val app = context.applicationContext as XincodeApplication
                PromptExpander.expand(
                    app.database,
                    app.keystore,
                    PromptExpander.Kind.TASK,
                    draft
                ).onSuccess {
                    chatState.input.value = it
                    Toast.makeText(context, "提示词已优化", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "优化失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            } catch (cancelled: CancellationException) {
                Toast.makeText(context, "已停止提示词优化", Toast.LENGTH_SHORT).show()
                throw cancelled
            } finally {
                expandingPrompt = false
                promptExpansionJob = null
            }
        }
    }

    // Bind scope on (re)composition — keyed on the chatState INSTANCE so切换会话换绑新实例时会重新布线
    // (statusLine / isStreaming 收集器绑到新会话那一对)。旧实例仍在其自有 scope 后台运行,不受影响。
    LaunchedEffect(chatState) {
        // M1 修复:用 LaunchedEffect 自身的协程作用域(this)布线,而非 composition 级 scope ——
        // 这样切会话(key=chatState 变化)时,旧实例的状态收集器随本 effect 一并取消,不再泄漏被驱逐的 core。
        val legacy = chatState as? ChatState
        if (legacy != null) {
            legacy.init(this)
            legacy.loadHistory()            // 旧版 ChatState 仍自行加载
        }
        // AgentChatState 的历史由 app(冷启动 + switchToSession)负责加载,这里只布线,避免打断后台正在跑的会话。
        (chatState as? AgentChatState)?.init(this)
    }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(chatState.messages.size) {
        if (chatState.messages.isNotEmpty()) {
            listState.animateScrollToItem(chatState.messages.size - 1)
        }
    }

    val hasMessages = chatState.messages.isNotEmpty()
    Column(Modifier.fillMaxSize().background(Bg)) {
        // ---- Claude-style top bar: hamburger, quiet title, settings/actions ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChatActionIcon(
                icon = Icons.Outlined.Menu,
                contentDescription = "打开侧边栏",
                onClick = onOpenDrawer
            )
            if (hasMessages) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            showMainMenu = true
                            effortExpanded = false
                        }
                ) {
                    Text(
                        conversationTitle,
                        fontSize = 20.sp,
                        lineHeight = 25.sp,
                        fontFamily = XinSerifFont,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        assistantSubtitle(assistantName, currentModel, providerName),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = XinUiFont,
                        color = Sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(xc.bgElevated)
                    .border(BorderStroke(0.8.dp, xc.border), CircleShape)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onNewChat() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新建聊天", tint = xc.ink, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Box {
                IconButton(onClick = { showTopMenu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多选项", tint = xc.ink, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(
                    expanded = showTopMenu,
                    onDismissRequest = { showTopMenu = false },
                    modifier = Modifier.background(xc.bgElevated).widthIn(min = 180.dp)
                ) {
                    Text(
                        conversationTitle,
                        fontSize = 12.sp,
                        fontFamily = XinUiFont,
                        color = xc.faint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(xc.border))
                    DropdownMenuItem(
                        text = { Text(t("Share"), fontFamily = XinUiFont) },
                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = xc.ink, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showTopMenu = false
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, chatState.formatForExport())
                                putExtra(Intent.EXTRA_SUBJECT, "XINCODE 对话导出")
                            }
                            context.startActivity(Intent.createChooser(intent, "分享对话"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(t("Rename"), fontFamily = XinUiFont) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, tint = xc.ink, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showTopMenu = false
                            renameSessionTitle = conversationTitle
                            renameSessionDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (isStarred) t("Unpin") else t("Pin"), fontFamily = XinUiFont) },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null, tint = xc.ink, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showTopMenu = false
                            onTogglePin(currentSessionId, !isStarred)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(t("Add to project"), fontFamily = XinUiFont) },
                        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null, tint = xc.ink, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showTopMenu = false
                            showAddToProjectSheet = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(t("Delete"), fontFamily = XinUiFont, color = xc.red) },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = xc.red, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            showTopMenu = false
                            onDeleteSession(currentSessionId)
                        }
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Border))

        if (renameSessionDialog) {
            AlertDialog(
                onDismissRequest = { renameSessionDialog = false },
                title = { Text(t("重命名会话"), fontFamily = XinSerifFont, color = xc.ink) },
                text = {
                    TextField(
                        value = renameSessionTitle,
                        onValueChange = { renameSessionTitle = it },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = xc.bgElevated,
                            unfocusedContainerColor = xc.bgElevated,
                            cursorColor = xc.green,
                            focusedTextColor = xc.ink,
                            unfocusedTextColor = xc.ink
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontFamily = XinUiFont)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (renameSessionTitle.isNotBlank()) {
                            onRenameSession(currentSessionId, renameSessionTitle.trim())
                        }
                        renameSessionDialog = false
                    }) { Text(t("保存"), color = xc.green, fontFamily = XinUiFont) }
                },
                dismissButton = {
                    TextButton(onClick = { renameSessionDialog = false }) { Text(t("取消"), color = xc.sub, fontFamily = XinUiFont) }
                },
                containerColor = xc.bgElevated
            )
        }

        // ---- Goal/Work 模式横幅 ----
        if (isGoalSession) {
            GoalBanner(goalStatusCode, goalLiveText, goalRunning, onStopGoal)
        }

        // Live plan card (only shows when an agent_plan has been published)
        if (planState != null) {
            PlanCard(planState = planState)
        }

        val turnGroups by remember(chatState.messages.toList()) {
            derivedStateOf { chatState.messages.toList().groupByTurn() }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (turnGroups.isEmpty()) {
                item(key = "claude_greeting_hero") {
                    ClaudeGreetingHero()
                }
            }
            items(turnGroups, key = { it.key }) { group ->
                val userMessage = group.userMessage
                val assistantMessage = group.assistantMessage
                when {
                    group.isFlat && userMessage != null ->
                        MessageBubble(
                            userMessage,
                            isStreamingMessage = chatState.isStreaming.value && userMessage == chatState.messages.lastOrNull(),
                            onDelete = { onDeleteMessage(userMessage.id) },
                            // 点用户消息的「重答」= 用同样的问题重新问一遍
                            onRegenerate = { onRegenerate(userMessage.id) }
                        )
                    assistantMessage != null ->
                        AgentTurnBlock(
                            group = group,
                            supplierId = supplierId,
                            assistantName = currentModel.ifBlank { assistantName },
                            isStreaming = chatState.isStreaming.value,
                            onRegenerate = { onRegenerate(assistantMessage.id) }
                        )
                    group.isFlat && group.toolMessages.isNotEmpty() ->
                        group.toolMessages.forEach { toolMsg ->
                            toolMsg.contentBlock?.let { block ->
                                when (block) {
                                    is MessageContent.ToolCall -> ToolCallRow(block, modifier = Modifier)
                                    is MessageContent.FileRead -> CodeBlock(block, Modifier.padding(vertical = 4.dp))
                                    is MessageContent.FileEdit -> DiffBlock(block, Modifier.padding(vertical = 4.dp))
                                    else -> {}
                                }
                            }
                        }
                    else ->
                        AgentTurnBlock(
                            group = group,
                            supplierId = supplierId,
                            assistantName = currentModel.ifBlank { assistantName },
                            isStreaming = chatState.isStreaming.value,
                            onRegenerate = null
                        )
                }
            }
            // Confirmation card (rendered in chat flow)
            val agentState = chatState as? AgentChatState
            val confirmReq = agentState?.pendingConfirm?.value
            if (confirmReq != null) {
                item(key = "confirm_card") {
                    ConfirmCard(
                        command = confirmReq.command,
                        isIrreversible = confirmReq.isIrreversible,
                        onDeny = { agentState.denyConfirmation() },
                        onAllowOnce = { agentState.approveOnceConfirmation() },
                        onAlwaysAllow = { agentState.approveAlwaysConfirmation() }
                    )
                }
            }
        }

        // ---- Scroll-to-bottom FAB — only visible when not at the tail ----
        val nearBottom by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val visibleLast = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                val total = info.totalItemsCount
                total == 0 || visibleLast >= total - 2
            }
        }
        val showFab = !nearBottom && chatState.messages.isNotEmpty()
        val fabScale by animateFloatAsState(
            targetValue = if (showFab) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
            label = "fabScale"
        )
        if (fabScale > 0.02f) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(36.dp)
                    .graphicsLayer(scaleX = fabScale, scaleY = fabScale, alpha = fabScale)
                    .background(Ink, RoundedCornerShape(18.dp))
                    .border(1.dp, Border, RoundedCornerShape(18.dp))
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        scope.launch { listState.animateScrollToItem(chatState.messages.size - 1) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, "跳到底部", tint = Bg, modifier = Modifier.size(18.dp))
            }
        }
        }

        // Token 统计不再单独占一条 —— 改为输入框发送键旁的「上下文圆环」,点击弹出统计卡片(见下)。

        // Pulse animation for streaming/tool execution
        val pulseTransition = rememberInfiniteTransition(label = "toolPulse")
        val pulseAlpha by pulseTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        // ---- status line ----
        if (chatState.statusLine.value.isNotEmpty()) {
            val isError = chatState.statusLine.value.startsWith("✗")
            Text(
                chatState.statusLine.value,
                fontSize = 11.sp,
                fontFamily = JetBrainsMono,
                color = if (isError) Red else Faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    .alpha(if (!isError && chatState.isStreaming.value) pulseAlpha else 1f)
            )
        }

        // ---- slash command popup ----
        // 仅在还在敲【命令名本身】(斜杠后无空格)时显示菜单;一旦出现空格(如 /skill 后接任务)即隐藏。
        val activeSlashCommand = remember(chatState.input.value) {
            val v = chatState.input.value
            if (v.startsWith("/") && !v.drop(1).contains(' ')) v.substring(1).trim() else null
        }
        if (activeSlashCommand != null) {
            SlashCommandMenu(
                query = activeSlashCommand,
                skillNames = skillNames,
                onCompact = {
                    onCompactContext()
                    chatState.input.value = ""
                },
                // 选技能 → 输入框生成 /技能名 (由 onInsertSkill 处理),不再清空、不塞模板。
                onSelectSkill = { name -> onInsertSkill(name) },
                onClose = { chatState.input.value = "" }
            )
        }

        // ---- 「+」底部抽屉:媒体、搜索、记忆和工具权限 ----
        if (showPlusCard) {
            ModalBottomSheet(
                onDismissRequest = { showPlusCard = false },
                containerColor = xc.bgElevated,
                scrimColor = Color.Black.copy(alpha = 0.35f),
                dragHandle = {
                    Box(Modifier.padding(top = 10.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(xc.border))
                }
            ) {
                val curProj = projects.firstOrNull { it.id == currentProjectId }
                AddToChatSheet(
                    webSearchOn = webSearchOn,
                    projectName = curProj?.name ?: "None",
                    toolAccessMode = toolAccessMode,
                    mcpNames = mcpNames,
                    skillNames = skillNames,
                    onClose = { showPlusCard = false },
                    onCamera = {
                        showPlusCard = false
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            cameraLauncher.launch(null)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onPhotos = {
                        showPlusCard = false
                        imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onFiles = {
                        showPlusCard = false
                        attachLauncher.launch(arrayOf("*/*"))
                    },
                    onToggleWebSearch = {
                        webSearchOn = !webSearchOn
                        com.xincode.tools.WebSearchGate.enabled = webSearchOn
                        onSetWebSearchEnabled(webSearchOn)
                    },
                    onAddProject = {
                        showPlusCard = false
                        showAddToProjectSheet = true
                    },
                    onToolAccess = {
                        showPlusCard = false
                        showToolAccessSheet = true
                    },
                    onMcp = {
                        showPlusCard = false
                        showMcpPicker = true
                    },
                    onSkill = {
                        showPlusCard = false
                        showSkillPicker = true
                    }
                )
            }
        }

        // ---- Select model 底部抽屉 (Screenshot 23:11:53) ----
        if (showSelectModelSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSelectModelSheet = false },
                containerColor = xc.bgElevated,
                scrimColor = Color.Black.copy(alpha = 0.35f),
                dragHandle = {
                    Box(Modifier.padding(top = 10.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(xc.border))
                }
            ) {
                SelectModelBottomSheet(
                    currentModel = currentModel,
                    availableModels = availableModels,
                    effortLabel = thinkingLevelLabel(thinkingLevel),
                    permissionLabel = when {
                        collabMode && permissionMode == com.xincode.security.PermissionMode.ALLOW_ALL -> "协同 · 完全访问"
                        collabMode -> "协同 · 正常"
                        permissionMode == com.xincode.security.PermissionMode.ALLOW_ALL -> "完全访问"
                        permissionMode == com.xincode.security.PermissionMode.PLAN -> "计划模式"
                        else -> "正常"
                    },
                    contextLabel = "已用 ${(contextUsage.ratio * 100).toInt()}%",
                    collabOn = collabMode,
                    onClose = { showSelectModelSheet = false },
                    onSelectModel = { modelId ->
                        onSwitchModel(modelId)
                        showSelectModelSheet = false
                    },
                    onOpenEffort = {
                        showSelectModelSheet = false
                        showEffortSheet = true
                    },
                    onOpenMode = {
                        showSelectModelSheet = false
                        showModeCard = true
                    },
                    onOpenStats = {
                        showSelectModelSheet = false
                        showStatsPopup = true
                    },
                    onOpenEnhance = {
                        showSelectModelSheet = false
                        expandCurrentPrompt()
                    },
                    onOpenGoal = {
                        showSelectModelSheet = false
                        onNavigateToGoal()
                    },
                    onToggleCollab = { onSetCollabMode(!collabMode) }
                )
            }
        }

        // ---- Effort 底部抽屉 (Screenshot 23:11:58) ----
        if (showEffortSheet) {
            ModalBottomSheet(
                onDismissRequest = { showEffortSheet = false },
                containerColor = xc.bgElevated,
                scrimColor = Color.Black.copy(alpha = 0.35f),
                dragHandle = {
                    Box(Modifier.padding(top = 10.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(xc.border))
                }
            ) {
                EffortBottomSheet(
                    currentLevel = thinkingLevel,
                    onBack = {
                        showEffortSheet = false
                        showSelectModelSheet = true
                    },
                    onSelectLevel = { level ->
                        onThinkingLevelChange(level)
                        onThinkingEnabledChange(level > 0)
                        showEffortSheet = false
                    }
                )
            }
        }

        // ---- Tool access 底部抽屉 (Screenshot 23:13:00) ----
        if (showToolAccessSheet) {
            ModalBottomSheet(
                onDismissRequest = { showToolAccessSheet = false },
                containerColor = xc.bgElevated,
                scrimColor = Color.Black.copy(alpha = 0.35f),
                dragHandle = {
                    Box(Modifier.padding(top = 10.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(xc.border))
                }
            ) {
                ToolAccessBottomSheet(
                    currentMode = toolAccessMode,
                    onBack = { showToolAccessSheet = false },
                    onSelectMode = { mode ->
                        toolAccessMode = mode
                        showToolAccessSheet = false
                    }
                )
            }
        }

        // ---- Add to project 底部抽屉 (Screenshot 23:13:05) ----
        if (showAddToProjectSheet) {
            ModalBottomSheet(
                onDismissRequest = { showAddToProjectSheet = false },
                containerColor = xc.bgElevated,
                scrimColor = Color.Black.copy(alpha = 0.35f),
                dragHandle = {
                    Box(Modifier.padding(top = 10.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(xc.border))
                }
            ) {
                AddToProjectBottomSheet(
                    projects = projects,
                    currentProjectId = currentProjectId,
                    onBack = { showAddToProjectSheet = false },
                    onSelectProject = { project: ProjectEntity ->
                        onMoveSessionToProject(currentSessionId, project.id)
                        showAddToProjectSheet = false
                    }
                )
            }
        }

        // ---- 模式卡片(普通聊天 / 计划模式 / 协作模式，由模型面板“访问权限”进入) ----
        if (showModeCard) {
            Popup(
                alignment = Alignment.BottomStart,
                offset = IntOffset(0, -70),
                onDismissRequest = { showModeCard = false }
            ) {
                ModeCard(
                    permissionMode = permissionMode,
                    collabMode = collabMode,
                    onPick = { mode, collab ->
                        onSetCollabMode(collab)
                        onUpdatePermissionMode(mode)
                        showModeCard = false
                    }
                )
            }
        }

        // ---- 上下文用量卡片(由模型面板“上下文用量”进入) ----
        if (showStatsPopup) {
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, -70),
                onDismissRequest = { showStatsPopup = false }
            ) {
                ContextStatsCard(
                    usage = contextUsage,
                    stats = liveTokenStats,
                    model = currentModel,
                    chatState = chatState,
                    onClose = { showStatsPopup = false }
                )
            }
        }

        // ---- 提示词增强卡片(由模型面板“增强提示词”进入，也可空输入时从灵感入口进入) ----
        if (showInspirationMenu) {
            Popup(
                alignment = Alignment.BottomCenter,
                offset = IntOffset(0, -76),
                onDismissRequest = { showInspirationMenu = false }
            ) {
                InspirationCard(
                    initialText = chatState.input.value,
                    onApply = { combined ->
                        chatState.input.value = combined
                        showInspirationMenu = false
                    },
                    onDismiss = { showInspirationMenu = false }
                )
            }
        }
        if (showFolderPicker) {
            DirectoryPickerDialog(
                initialPath = "",
                onConfirm = { path -> onSetConversationWorkspace(path); showFolderPicker = false },
                onDismiss = { showFolderPicker = false }
            )
        }
        if (showSkillPicker) {
            SkillPickerDialog(skillNames = skillNames, onPick = { onInsertSkill(it); showSkillPicker = false }, onDismiss = { showSkillPicker = false })
        }
        if (showMcpPicker) {
            McpPickerDialog(
                mcpNames = mcpNames,
                onPick = { onInsertMcp(it); showMcpPicker = false },
                onManage = { showMcpPicker = false; onNavigateToMcp() },
                onDismiss = { showMcpPicker = false }
            )
        }

        // ---- floating input card ----
        // 统一「提交」动作:回车键(回车发送模式)与 [→] 键共用。运行中=中途插话(注入不打断);
        // Goal 未跑=以输入启动目标;否则正常发送(含附件拼接)。
        val submitInput: () -> Unit = submit@{
            if (chatState.isStreaming.value) {
                if (chatState.input.value.isNotBlank()) chatState.send()  // send() 内部走 steer 注入
                return@submit
            }
            if (isGoalSession && !goalRunning) {
                val g = chatState.input.value.trim()
                if (g.isNotEmpty()) { chatState.input.value = ""; onStartGoal(g) }
                return@submit
            }
            if (pendingAttachments.value.isNotEmpty()) {
                val attachmentText = buildString {
                    append(chatState.input.value.trim())
                    append("\n\n---\n附件:\n")
                    pendingAttachments.value.forEach { att ->
                        if (att.absolutePath.isNotEmpty()) {
                            // 走路径的附件:内容不进消息体,所以多大都不占上下文。
                            // 图片交给 describe_image(未配视觉模型时该工具不暴露),
                            // 大文本交给 file_read 按需读、分段读。
                            val isImg = att.mimeType.startsWith("image/")
                            if (isImg) {
                                append("\n### ${att.fileName}(图片,路径:${att.absolutePath})\n")
                            } else {
                                append("\n### ${att.fileName}(文件较大未内联,路径:${att.absolutePath},请用 file_read 按需读取)\n")
                            }
                        } else {
                            append("\n### ${att.fileName}\n```\n${att.content}\n```\n")
                        }
                    }
                    append("\n---\n")
                }
                chatState.input.value = attachmentText
                pendingAttachments.value = emptyList()
            }
            chatState.send()
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(xc.bgElevated)
                .border(0.8.dp, Border, RoundedCornerShape(28.dp))
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // Line 1: attachment chips (only if any)
                if (pendingAttachments.value.isNotEmpty()) {
                    val chipScrollState = rememberScrollState()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chipScrollState)
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        pendingAttachments.value.forEach { att ->
                            val isImage = att.mimeType.startsWith("image/") || att.fileName.endsWith(".jpg", true) || att.fileName.endsWith(".png", true) || att.fileName.endsWith(".jpeg", true)
                            if (isImage && att.absolutePath.isNotEmpty()) {
                                val bitmap = remember(att.absolutePath) {
                                    try {
                                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                                        android.graphics.BitmapFactory.decodeFile(att.absolutePath, opts)
                                    } catch (_: Exception) { null }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(xc.bgElevated)
                                        .border(1.dp, Border, RoundedCornerShape(12.dp))
                                ) {
                                    if (bitmap != null) {
                                        androidx.compose.foundation.Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = att.fileName,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    } else {
                                        Icon(
                                            Icons.Outlined.Image,
                                            contentDescription = null,
                                            tint = Sub,
                                            modifier = Modifier.align(Alignment.Center).size(24.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(3.dp)
                                            .size(18.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(Color.Black.copy(alpha = 0.65f))
                                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                                pendingAttachments.value = pendingAttachments.value.filter { it.id != att.id }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = "删除", tint = Color.White, modifier = Modifier.size(11.dp))
                                    }
                                }
                            } else {
                                Row(
                                    Modifier
                                        .height(28.dp)
                                        .border(1.dp, Border, RoundedCornerShape(6.dp))
                                        .background(xc.bg, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Outlined.Description,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Ink
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        att.fileName,
                                        fontSize = 12.sp,
                                        fontFamily = JetBrainsMono,
                                        color = Ink,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (att.sizeBytes > 0) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            humanSize(att.sizeBytes),
                                            fontSize = 10.sp,
                                            fontFamily = JetBrainsMono,
                                            color = Sub
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "删除",
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) {
                                                pendingAttachments.value =
                                                    pendingAttachments.value.filter { it.id != att.id }
                                            },
                                        tint = Ink
                                    )
                                }
                            }
                        }
                    }
                }

                // Line 2: text field
                TextField(
                    value = chatState.input.value,
                    onValueChange = { chatState.input.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true,
                    singleLine = enterToSend,
                    maxLines = if (enterToSend) 1 else 6,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Ink,
                        focusedTextColor = Ink,
                        unfocusedTextColor = Ink,
                        disabledTextColor = Faint
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontFamily = XinUiFont),
                    keyboardOptions = KeyboardOptions(imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default),
                    keyboardActions = KeyboardActions(onSend = { submitInput() }),
                    placeholder = {
                        Text(
                            when {
                                chatState.isStreaming.value -> t("插话给正在工作的 AI(不打断)…")
                                isGoalSession && !goalRunning -> t("输入目标,让 XINCODE 自主完成…")
                                else -> "Reply to Claude..."
                            },
                            color = Sub, fontSize = 16.sp, fontFamily = XinUiFont
                        )
                    }
                )

                Spacer(Modifier.height(8.dp))

                // Line 3: Claude mobile bottom control bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // [+] Circle button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(xc.bg)
                            .border(BorderStroke(0.8.dp, xc.border), CircleShape)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                showPlusCard = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "Add to chat",
                            tint = Ink,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Model & Effort pill capsule: e.g. "Sonnet 5 High"
                    val modelNameText = modelDisplayName.ifBlank { "Sonnet 5" }
                    val effortLabelText = thinkingLevelLabel(thinkingLevel)
                    val pillText = if (thinkingEnabled) "$modelNameText $effortLabelText" else modelNameText
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(xc.bg)
                            .border(0.8.dp, Border, RoundedCornerShape(18.dp))
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                showSelectModelSheet = true
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            pillText,
                            fontSize = 13.sp,
                            fontFamily = XinUiFont,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Microphone button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            when {
                                voiceInputHelper == null -> Toast.makeText(context, "语音输入组件尚未初始化", Toast.LENGTH_LONG).show()
                                voiceState == VoiceInputHelper.State.STARTING || voiceState == VoiceInputHelper.State.LISTENING -> voiceInputHelper.finishListening()
                                voiceState == VoiceInputHelper.State.PROCESSING -> Toast.makeText(context, "正在整理识别结果，请稍候", Toast.LENGTH_SHORT).show()
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voiceInputHelper.startListening()
                                else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.MicNone,
                            contentDescription = "语音输入",
                            tint = if (voiceFeedback.active) Red else Ink,
                            modifier = Modifier.size(21.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    // Send or Stop button
                    val streaming = chatState.isStreaming.value
                    val hasText = chatState.input.value.isNotBlank() || pendingAttachments.value.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (streaming && !hasText) xc.ink
                                else if (hasText) xc.ink
                                else xc.border
                            )
                            .clickable(
                                enabled = hasText || streaming,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                if (streaming && !hasText) {
                                    if (isGoalSession && goalRunning) onStopGoal() else chatState.stop()
                                } else {
                                    submitInput()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (streaming && !hasText) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                            contentDescription = if (streaming && !hasText) "停止" else "发送",
                            tint = if (hasText || streaming) Color.White else xc.faint,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }

    // ---- model switch warning ----
    if (pendingModelIdx != null) {
        val targetName = pendingModelIdx ?: ""
        AlertDialog(
            onDismissRequest = { pendingModelIdx = null },
            title = { Text("切换模型", fontFamily = XinSerifFont, color = xc.ink) },
            text = { Text("切换到「$targetName」？\n\n新模型可能不兼容当前对话中的工具调用记录，建议开启新会话。", fontFamily = XinUiFont, fontSize = 13.sp, color = xc.sub, lineHeight = 18.sp) },
            confirmButton = { TextButton(onClick = { val name = pendingModelIdx; pendingModelIdx = null; name?.let { onSwitchModel(it) } }) { Text("切换", fontFamily = XinUiFont, color = xc.red) } },
            dismissButton = { TextButton(onClick = { pendingModelIdx = null }) { Text("取消", fontFamily = XinUiFont, color = xc.sub) } },
            containerColor = xc.bgElevated
        )
    }
}

private data class InspirationTemplate(val title: String, val description: String, val prompt: String)

private val inspirationTemplates = listOf(
    InspirationTemplate(
        "规划任务",
        "拆成步骤、风险和验收条件",
        "请把我的目标拆成可执行步骤，列出依赖、风险和每一步的验收条件。先确认当前环境，再开始执行。"
    ),
    InspirationTemplate(
        "排查问题",
        "先找证据，再给修复方案",
        "请帮我排查这个问题。先收集现象、日志和复现步骤，区分已确认原因与推测，再给出最小修复并验证。"
    ),
    InspirationTemplate(
        "优化代码",
        "兼顾可读性、边界与测试",
        "请审查并优化这段代码，保留现有行为，重点检查错误路径、空值、性能和可维护性，同时补充可失败的测试。"
    )
)

@Composable
private fun InspirationCard(
    initialText: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalXinColors.current
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var editor by remember(initialText) { mutableStateOf(initialText) }
    Column(
        Modifier
            .widthIn(min = 300.dp, max = 360.dp)
            .background(colors.bgElevated, RoundedCornerShape(24.dp))
            .border(1.dp, colors.border, RoundedCornerShape(24.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = colors.green,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("从一个清晰起点开始", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.ink)
                Text("可多选组合，也可直接修改完整内容", fontSize = 10.sp, color = colors.sub)
            }
            ChatActionIcon(Icons.Outlined.Close, "关闭灵感面板", onClick = onDismiss)
        }
        Spacer(Modifier.height(8.dp))
        inspirationTemplates.forEachIndexed { index, item ->
            val checked = index in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (checked) colors.green.copy(alpha = 0.14f) else colors.activeBg)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        selected = if (checked) selected - index else selected + index
                        if (checked) {
                            editor = editor.replace(item.prompt, "")
                                .replace(Regex("\\n{3,}"), "\n\n")
                                .trim()
                        } else if (!editor.contains(item.prompt)) {
                            editor = listOf(editor.trim(), item.prompt)
                                .filter { it.isNotBlank() }
                                .joinToString("\n\n")
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text((if (checked) "✓ " else "") + item.title,
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.ink)
                    Text(item.description, fontSize = 10.sp, color = colors.sub)
                }
                Icon(
                    imageVector = if (checked) Icons.Outlined.Check else Icons.Outlined.Add,
                    contentDescription = null,
                    tint = colors.faint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TextField(
            value = editor,
            onValueChange = { editor = it },
            placeholder = { Text("组合后的提示词会出现在这里，可继续修改", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.bg,
                unfocusedContainerColor = colors.bg,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = colors.ink,
                unfocusedTextColor = colors.ink,
                cursorColor = colors.green
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 17.sp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.sub) }
            TextButton(
                enabled = editor.isNotBlank(),
                onClick = { onApply(editor.trim()) }
            ) { Text("放入输入框", color = if (editor.isNotBlank()) colors.green else colors.faint) }
        }
    }
}

/**
 * Slash-command menu shown while the input begins with '/'.
 * Built-in items: `/compact`. Every user-defined skill is also listed and
 * expands into its prompt when selected.
 */
@Composable
private fun SlashCommandMenu(
    query: String,
    skillNames: List<String>,
    onCompact: () -> Unit,
    onSelectSkill: (String) -> Unit,
    onClose: () -> Unit
) {
    val xc = LocalXinColors.current
    data class Item(val label: String, val desc: String, val onClick: () -> Unit)
    val q = query.lowercase()
    val builtins = listOf(
        Item("/compact", "总结当前对话为摘要，释放上下文") { onCompact() }
    )
    val skillItems = skillNames.map { s -> Item("/skill: $s", "注入技能 '$s' 的内容", { onSelectSkill(s) }) }
    val filtered = (builtins + skillItems).filter { q.isBlank() || it.label.lowercase().contains(q) }

    AnimatedVisibility(
        visible = filtered.isNotEmpty() || q.isBlank(),
        enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 },
        exit = fadeOut(tween(100)) + slideOutVertically(tween(120)) { it / 2 }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 4.dp)
                .background(xc.bgElevated, RoundedCornerShape(12.dp))
                .border(1.dp, xc.border, RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Slash 命令 · ${filtered.size} 项",
                    fontSize = 10.sp,
                    fontFamily = JetBrainsMono,
                    color = xc.sub,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "✕",
                    fontSize = 12.sp,
                    fontFamily = JetBrainsMono,
                    color = xc.sub,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onClose() }
                )
            }
            if (filtered.isEmpty()) {
                Text(
                    "无匹配命令。可用: /compact, /skill: <name>",
                    fontSize = 11.sp,
                    fontFamily = JetBrainsMono,
                    color = xc.faint,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            filtered.take(6).forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { item.onClick() }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label, fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.ink)
                        Text(item.desc, fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
                    }
                    Text("↵", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.faint)
                }
            }
        }
    }
}

/** 「+」卡片上排的方块动作(图标 + 标签)。 */
@Composable
private fun PlusAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, ink: Color, sub: Color, onClick: () -> Unit) {
    Column(
        Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, fontFamily = JetBrainsMono, color = sub)
    }
}

/** 「+」卡片下排的一行(图标 + 标题 + 说明/状态 + 可选开关点)。 */
@Composable
private fun PlusRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, sub: String, ink: Color, subC: Color, toggled: Boolean?, onClick: () -> Unit) {
    val green = LocalXinColors.current.green
    Row(
        Modifier.fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = if (toggled == true) green else ink, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontFamily = JetBrainsMono, color = ink)
            if (sub.isNotBlank()) Text(sub, fontSize = 10.sp, fontFamily = JetBrainsMono, color = subC)
        }
        if (toggled != null) {
            Text(if (toggled) "●" else "○", fontSize = 14.sp, fontFamily = JetBrainsMono, color = if (toggled) green else subC)
        }
    }
}

/** 技能选择对话框:列出可用技能,点选插入。统一样式:圆角卡片 + 行圆角选中态。 */
@Composable
private fun SkillPickerDialog(skillNames: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val xc = LocalXinColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text(t("选择技能"), fontSize = 15.sp, fontFamily = XinSerifFont, color = xc.ink) },
        text = {
            if (skillNames.isEmpty()) {
                Text(t("暂无技能(可在 设置→Skills 管理)"), fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.faint)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    skillNames.forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(xc.bg)
                                .border(0.5.dp, xc.border, RoundedCornerShape(12.dp))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(name) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(xc.activeBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    name.firstOrNull()?.uppercase() ?: "S",
                                    fontSize = 13.sp, fontFamily = XinUiFont, color = xc.green
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(name, fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(t("关闭"), fontFamily = JetBrainsMono, color = xc.sub) } },
        containerColor = xc.bgElevated
    )
}

/** 可展开的统计小卡片:折叠态 = token 一行 + 箭头;展开态 = 子智能体 token + 调用占比。 */
@Composable
private fun ExpandableStatsBar(stats: TokenStats, model: String, chatState: ChatStateLike) {
    val xc = LocalXinColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (stats.hasData) TokenStatsBar(stats, model)
                else Text("统计", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
            }
            Text(if (expanded) " ▾" else " ▸", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
        }
        if (expanded) {
            // 调用占比(从内存消息统计)。
            val counts = remember(chatState.messages.size, expanded) {
                val m = HashMap<String, Int>()
                for (msg in chatState.messages) {
                    if (msg.role != "tool") continue
                    val name = try {
                        val j = org.json.JSONObject(msg.content)
                        if (j.optBoolean("__tool_call__", false)) j.optString("tool_name", "") else ""
                    } catch (_: Exception) { "" }
                    if (name.isBlank()) continue
                    val cat = when {
                        name in listOf("web_search", "web_fetch", "web_search_batch") -> "网络"
                        name in listOf("file_read", "file_write", "file_edit", "edit", "multi_edit", "list_dir", "grep", "glob") -> "文件"
                        name in listOf("shell_exec", "su_exec", "code_exec", "env_exec") -> "终端"
                        name in listOf("invoke_skill", "skill_manage") -> "技能"
                        name in listOf("dispatch_agents", "wolfpack_run") -> "子智能体"
                        name == "agent_plan" -> "计划"
                        name in listOf("recall_memory", "save_memory") -> "记忆"
                        name.contains("__") -> "MCP"
                        else -> "其他"
                    }
                    m[cat] = (m[cat] ?: 0) + 1
                }
                m
            }
            val total = counts.values.sum()
            Column(
                Modifier.fillMaxWidth().padding(top = 6.dp)
                    .background(xc.bgElevated, RoundedCornerShape(10.dp)).padding(12.dp)
            ) {
                Text("子智能体:${AgentStats.subAgentRuns} 次 · ${formatCount(AgentStats.subAgentTokens)} tokens",
                    fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
                Spacer(Modifier.height(6.dp))
                Text("调用占比(共 $total 次)", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
                if (total == 0) {
                    Text("暂无工具调用", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint, modifier = Modifier.padding(top = 4.dp))
                } else {
                    counts.entries.sortedByDescending { it.value }.forEach { (cat, cnt) ->
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                            Text(cat, fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.ink, modifier = Modifier.weight(1f))
                            Text("$cnt 次 · ${cnt * 100 / total}%", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
                        }
                    }
                }
            }
        }
    }
}

/** Compact token accounting strip. Renders quietly above the input. */
@Composable
private fun TokenStatsBar(stats: TokenStats, model: String = "") {
    val xc = LocalXinColors.current
    val cachePct = (stats.cacheHitRatio * 100).toInt()
    val cacheColor = when {
        stats.cacheHitRatio > 0.5f -> xc.green
        stats.cacheHitRatio > 0.2f -> xc.yellow
        else -> xc.sub
    }
    val cost = Pricing.costRmb(stats, model)
    val line = buildString {
        append("in ")
        append(formatCount(stats.prompt))
        append(" · out ")
        append(formatCount(stats.completion))
        if (stats.cacheHit + stats.cacheMiss > 0) {
            append(" · cache ")
            append(cachePct)
            append("%")
        }
        append(" · Σ ")
        append(formatCount(stats.total))
        // 缓存感知的人民币成本(未知模型不显示)。
        if (cost != null) {
            append(" · ")
            append(Pricing.formatRmb(cost))
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(line, fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub,
            modifier = Modifier.weight(1f))
        if (stats.cacheHit + stats.cacheMiss > 0) {
            Text("●", fontSize = 9.sp, color = cacheColor)
        }
    }
}

private fun formatCount(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 10_000 -> "%.1fk".format(n / 1000.0)
    n < 1_000_000 -> "${n / 1000}k"
    else -> "%.1fM".format(n / 1_000_000.0)
}

// 上下文圆环配色:绿(少)→蓝(中)→黄(接近满)→红(几乎满),分段线性插值。
private val RingGreen = Color(0xFF7BE0A4)
private val RingBlue = Color(0xFF4FA3FF)
private val RingYellow = Color(0xFFF2C14E)
private val RingRed = Color(0xFFE5484D)

private fun ringColor(ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return when {
        r <= 0.45f -> lerp(RingGreen, RingBlue, r / 0.45f)
        r <= 0.75f -> lerp(RingBlue, RingYellow, (r - 0.45f) / 0.30f)
        else -> lerp(RingYellow, RingRed, (r - 0.75f) / 0.25f)
    }
}

/**
 * 上下文占用圆环:随占用比例填满,颜色从绿→蓝→黄→红渐变。窗口未知时显示为一圈淡描边 + 「?」。
 * 点击弹出统计卡片。
 */
@Composable
private fun ContextRing(usage: ContextUsage, onClick: () -> Unit) {
    val xc = LocalXinColors.current
    val ratio = usage.ratio
    // 平滑动画到目标占用(修「刷新不及时」时的跳变观感)。
    val animated by animateFloatAsState(
        targetValue = if (usage.known) ratio else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "ctxRing"
    )
    val fg = ringColor(if (usage.known) ratio else 0f)
    Box(
        Modifier.size(28.dp)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 3.dp.toPx()
            // 轨道
            drawArc(
                color = xc.border,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            // 已占用
            if (usage.known && animated > 0f) {
                drawArc(
                    color = fg,
                    startAngle = -90f, sweepAngle = animated * 360f, useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            if (usage.known) "${(ratio * 100).toInt()}" else "?",
            fontSize = 8.sp, fontFamily = JetBrainsMono,
            color = if (usage.known) fg else xc.faint
        )
    }
}

/**
 * 点圆环弹出的统计卡片(收窄):上下文占用 + 总 token + 子智能体 token + 工具/skill/MCP 调用占比(不同颜色)。
 */
@Composable
private fun ContextStatsCard(usage: ContextUsage, stats: TokenStats, model: String, chatState: ChatStateLike, onClose: () -> Unit) {
    val xc = LocalXinColors.current
    val ringC = ringColor(usage.ratio)
    // 调用占比(从内存消息统计)。
    val counts = remember(chatState.messages.size) {
        val m = HashMap<String, Int>()
        for (msg in chatState.messages) {
            if (msg.role != "tool") continue
            val name = try {
                val j = org.json.JSONObject(msg.content)
                if (j.optBoolean("__tool_call__", false)) j.optString("tool_name", "") else ""
            } catch (_: Exception) { "" }
            if (name.isBlank()) continue
            val cat = when {
                name in listOf("web_search", "web_fetch", "web_search_batch") -> "网络"
                name in listOf("file_read", "file_write", "file_edit", "edit", "multi_edit", "list_dir", "grep", "glob") -> "文件"
                name in listOf("shell_exec", "su_exec", "code_exec", "env_exec") -> "终端"
                name in listOf("invoke_skill", "skill_manage") -> "技能"
                name in listOf("dispatch_agents", "wolfpack_run") -> "子智能体"
                name == "agent_plan" -> "计划"
                name in listOf("recall_memory", "save_memory") -> "记忆"
                name.contains("__") -> "MCP"
                else -> "其他"
            }
            m[cat] = (m[cat] ?: 0) + 1
        }
        m
    }
    val total = counts.values.sum()
    val catColors = mapOf(
        "网络" to RingBlue, "文件" to RingGreen, "终端" to Color(0xFF9B87F5),
        "技能" to RingYellow, "子智能体" to Color(0xFFE58F65), "计划" to Color(0xFF57C7D4),
        "记忆" to Color(0xFFC77DBB), "MCP" to Color(0xFF6FBF73), "其他" to xc.faint
    )
    Column(
        Modifier.width(232.dp)
            .background(xc.bgElevated, RoundedCornerShape(14.dp))
            .border(1.dp, xc.border, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("上下文", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.ink, modifier = Modifier.weight(1f))
            Text("✕", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.sub,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClose() })
        }
        Spacer(Modifier.height(8.dp))
        // 上下文占用
        if (usage.known) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("占用", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.sub, modifier = Modifier.weight(1f))
                Text("${(usage.ratio * 100).toInt()}%", fontSize = 11.sp, fontFamily = JetBrainsMono, color = ringC)
            }
            Spacer(Modifier.height(3.dp))
            // 进度条
            Box(Modifier.fillMaxWidth().height(5.dp).background(xc.border, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(usage.ratio).height(5.dp).background(ringC, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.height(3.dp))
            Text("${formatCount(usage.usedTokens)} / ${formatCount(usage.windowTokens)} tokens",
                fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
        } else {
            Text("上下文窗口未配置(设置→上下文压缩→上下文长度)",
                fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
            Text("当前上下文 ≈ ${formatCount(usage.usedTokens)} tokens",
                fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub, modifier = Modifier.padding(top = 2.dp))
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(xc.border))
        Spacer(Modifier.height(8.dp))
        // 总 token
        Text("总用量 Σ ${formatCount(stats.total)}", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.ink)
        Text("in ${formatCount(stats.prompt)} · out ${formatCount(stats.completion)}" +
            (if (stats.cacheHit + stats.cacheMiss > 0) " · cache ${(stats.cacheHitRatio * 100).toInt()}%" else ""),
            fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub, modifier = Modifier.padding(top = 2.dp))
        val cost = Pricing.costRmb(stats, model)
        if (cost != null) Text("成本 ${Pricing.formatRmb(cost)}", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)

        Spacer(Modifier.height(8.dp))
        // 子智能体
        Text("子智能体 ${AgentStats.subAgentRuns} 次 · ${formatCount(AgentStats.subAgentTokens)} tokens",
            fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)

        Spacer(Modifier.height(8.dp))
        Text("调用占比(共 $total 次)", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint)
        if (total == 0) {
            Text("暂无工具调用", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint, modifier = Modifier.padding(top = 3.dp))
        } else {
            // 颜色分段条
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
                counts.entries.sortedByDescending { it.value }.forEach { (cat, cnt) ->
                    Box(Modifier.fillMaxHeight().weight(cnt.toFloat()).background(catColors[cat] ?: xc.faint))
                }
            }
            Spacer(Modifier.height(6.dp))
            counts.entries.sortedByDescending { it.value }.forEach { (cat, cnt) ->
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(catColors[cat] ?: xc.faint))
                    Spacer(Modifier.width(6.dp))
                    Text(cat, fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.ink, modifier = Modifier.weight(1f))
                    Text("$cnt · ${cnt * 100 / total}%", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
                }
            }
        }
    }
}

/**
 * 模式卡片:普通聊天 / 计划模式 / 协作模式;计划与协作各可选「正常 / 完全访问」。
 * 正常 = 危险操作会逐一确认;完全访问 = 全放行不再确认。协作 = 主脑+子智能体优先并行。
 */
@Composable
private fun ModeCard(
    permissionMode: com.xincode.security.PermissionMode,
    collabMode: Boolean,
    onPick: (com.xincode.security.PermissionMode, Boolean) -> Unit
) {
    val xc = LocalXinColors.current
    val ask = com.xincode.security.PermissionMode.ASK
    val plan = com.xincode.security.PermissionMode.PLAN
    val allowAll = com.xincode.security.PermissionMode.ALLOW_ALL
    val isFull = permissionMode == allowAll
    Column(
        Modifier.width(248.dp)
            .background(xc.bgElevated, RoundedCornerShape(14.dp))
            .border(1.dp, xc.border, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Text("模式", fontSize = 11.sp, fontFamily = JetBrainsMono, color = xc.sub)
        Spacer(Modifier.height(8.dp))

        // 普通聊天(也可选完全访问)
        Text("○ 普通聊天", fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink)
        Text("正常对话;完全访问=危险操作也不再逐一确认", fontSize = 9.sp, fontFamily = JetBrainsMono, color = xc.sub)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccessChip("正常", !collabMode && permissionMode == ask, xc.green, xc) { onPick(ask, false) }
            AccessChip("完全访问", !collabMode && permissionMode == allowAll, xc.red, xc) { onPick(allowAll, false) }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(0.5.dp).background(xc.border))

        // 计划模式(只读规划)
        Text("◑ 计划模式", fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink)
        Text("只读+规划,不写文件、不执行命令", fontSize = 9.sp, fontFamily = JetBrainsMono, color = xc.sub)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccessChip("正常", !collabMode && permissionMode == plan, xc.green, xc) { onPick(plan, false) }
        }

        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(0.5.dp).background(xc.border))

        // 协作模式(主脑+子智能体)
        Text("◆ 协作模式", fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink)
        Text("主脑指挥,复杂任务拆给子智能体并行", fontSize = 9.sp, fontFamily = JetBrainsMono, color = xc.sub)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccessChip("正常", collabMode && !isFull, xc.green, xc) { onPick(ask, true) }
            AccessChip("完全访问", collabMode && isFull, xc.red, xc) { onPick(allowAll, true) }
        }
    }
}

@Composable
private fun ModeRowSimple(label: String, sub: String, active: Boolean, xc: XinColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink)
            Text(sub, fontSize = 9.sp, fontFamily = JetBrainsMono, color = xc.sub)
        }
        if (active) Text("✓", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.green)
    }
}

@Composable
private fun AccessChip(label: String, active: Boolean, activeColor: Color, xc: XinColors, onClick: () -> Unit) {
    Box(
        Modifier
            .border(1.dp, if (active) activeColor else xc.border, RoundedCornerShape(14.dp))
            .background(if (active) activeColor.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, fontFamily = JetBrainsMono, color = if (active) activeColor else xc.sub)
    }
}

/** Goal/Work 模式横幅:显示目标状态 + 运行中可停止。 */
@Composable
private fun GoalBanner(statusCode: String, liveText: String, running: Boolean, onStop: () -> Unit) {
    val xc = LocalXinColors.current
    val (dot, label) = when {
        running || statusCode == "running" -> xc.yellow to (liveText.ifBlank { "执行中…" })
        statusCode == "achieved" -> xc.green to "✓ 目标已达成"
        statusCode == "failed" -> xc.red to "✗ 目标未达成"
        else -> xc.faint to "输入一个目标,XINCODE 会自主执行、裁判验收,完成后通知你"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .background(xc.bgElevated, RoundedCornerShape(10.dp))
            .border(1.dp, xc.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(dot, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("Goal 模式", fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.sub)
            Text(label, fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (running || statusCode == "running") {
            Text("停止", fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.red,
                modifier = Modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onStop() }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

/** MCP 服务器选择对话框:列出已配置服务器,点选在输入框生成 @服务器 引用;底部可进管理页。 */
@Composable
private fun McpPickerDialog(mcpNames: List<String>, onPick: (String) -> Unit, onManage: () -> Unit, onDismiss: () -> Unit) {
    val xc = LocalXinColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = { Text(t("选择 MCP 服务器"), fontSize = 15.sp, fontFamily = XinSerifFont, color = xc.ink) },
        text = {
            if (mcpNames.isEmpty()) {
                Text(t("暂无 MCP 服务器(点下方「管理」去添加)"), fontSize = 12.sp, fontFamily = JetBrainsMono, color = xc.faint)
            } else {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    mcpNames.forEach { name ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(xc.bg)
                                .border(0.5.dp, xc.border, RoundedCornerShape(12.dp))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onPick(name) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(xc.activeBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("@", fontSize = 14.sp, fontFamily = JetBrainsMono, color = xc.green)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("@$name", fontSize = 13.sp, fontFamily = JetBrainsMono, color = xc.ink,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onManage) { Text(t("管理"), fontFamily = JetBrainsMono, color = xc.green) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(t("关闭"), fontFamily = JetBrainsMono, color = xc.sub) } },
        containerColor = xc.bgElevated
    )
}

private val GeneratedImageMarkerRegex = Regex("""###\s*([^(]+)\(图片,路径:([^)]+)\)""")

fun stripGeneratedImageMarkers(content: String): String =
    GeneratedImageMarkerRegex.replace(content, "").replace(Regex("""\n{3,}"""), "\n\n").trim()

/** Render image files returned by generate_image without re-encoding them. */
@Composable
fun GeneratedImagePreview(content: String) {
    val matches = remember(content) { GeneratedImageMarkerRegex.findAll(content).toList() }
    if (matches.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        matches.forEach { match ->
            val fileName = match.groupValues[1].trim()
            val path = match.groupValues[2].trim()
            val file = remember(path) { java.io.File(path) }
            val bitmap = remember(path, file.lastModified(), file.length()) {
                if (file.exists()) {
                    try {
                        // Full-resolution decode: the bitmap is only fitted on screen; the source file is untouched.
                        // Only subsample absurdly large images (>4096px) to avoid OOM; normal outputs stay pixel-exact.
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(path, bounds)
                        val sample = when {
                            bounds.outWidth <= 0 || bounds.outHeight <= 0 -> 1
                            maxOf(bounds.outWidth, bounds.outHeight) > 4096 -> 2
                            else -> 1
                        }
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        android.graphics.BitmapFactory.decodeFile(path, opts)
                    } catch (_: Exception) { null }
                } else null
            }
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = fileName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            } else {
                Text("[图片未找到: " + fileName + "]", fontSize = 12.sp, color = LocalXinColors.current.sub)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(msg: ChatState.MessageUi, isStreamingMessage: Boolean = false, onRetry: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onRegenerate: (() -> Unit)? = null) {
    if (msg.role == "user") {
        UserMessageBubble(msg = msg, onDelete = onDelete, onRegenerate = onRegenerate)
        return
    }
    val isUser = msg.role == "user"
    val isTool = msg.role == "tool"
    val isError = msg.content.startsWith("✗ ")
    val xc = LocalXinColors.current
    val Ink = xc.ink
    val Sub = xc.sub
    val Faint = xc.faint
    val Green = xc.green
    val Red = xc.red
    val Border = xc.border
    val roleLabel = when {
        isUser -> "you"
        isTool -> "❯ tool"
        else -> "xincode"
    }
    val roleColor = when {
        isUser -> Sub
        isError -> Red
        isTool -> Green
        else -> Green
    }
    val contentColor = when {
        isError -> Red
        isTool -> Faint
        else -> Ink
    }
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    var showMenu by remember(msg.id) { mutableStateOf(false) }

    // Blinking cursor while this specific assistant is streaming
    val cursorTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by cursorTransition.animateFloat(
        initialValue = 0.15f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "cursorAlpha"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showMenu = true
                }
            )
            .padding(horizontal = 0.dp, vertical = 4.dp),
        // 用户消息靠右,AI/工具消息靠左。
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Reasoning section (message-level, inside bubble, collapsible)
        ReasoningFoldable(msg, isCurrentStreaming = isStreamingMessage)

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isUser && !isTool) {
                Text(
                    "✦",
                    fontSize = 13.sp,
                    color = xc.green,
                    modifier = Modifier.padding(end = 5.dp)
                )
            }
            Text(
                if (isUser) "you" else if (isTool) "❯ tool" else "XINCODE",
                fontSize = if (!isUser && !isTool) 13.sp else 11.sp,
                fontFamily = if (!isUser && !isTool) XinSerifFont else XinCodeFont,
                fontWeight = if (!isUser && !isTool) FontWeight.SemiBold else FontWeight.Normal,
                color = if (!isUser && !isTool) xc.ink else roleColor
            )
            if (isError && onRetry != null) {
                Spacer(Modifier.width(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onRetry() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, "重试", tint = Sub, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("重试", fontSize = 10.sp, fontFamily = XinUiFont, color = Sub)
                }
            }
        }

        // 气泡。Claude 风格核心：助手回复采用纯粹的通透阅读排版，不套生硬小方盒；用户消息为圆润卡片
        val bubbleModifier = when {
            isTool -> Modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(12.dp))
                .background(xc.bgElevated)
                .border(0.5.dp, xc.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
            isUser -> Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(Alignment.End)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
                .background(xc.bgElevated)
                .border(0.8.dp, xc.border, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
            else -> Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp)
        }

        Box(bubbleModifier) {
        // Content (Markdown for assistant, plain text for user/tool)
        if (msg.role == "assistant") {
            val textWithoutImages = remember(msg.content) {
                GeneratedImageMarkerRegex.replace(msg.content, "").replace(Regex("""\n{3,}"""), "\n\n").trim()
            }
            if (msg.content.isNotEmpty()) {
                Column {
                    GeneratedImagePreview(msg.content)
                    if (textWithoutImages.isNotBlank()) {
                        MarkdownContent(textWithoutImages)
                    }
                    if (isStreamingMessage) {
                        Text(
                            "▊",
                            fontSize = 13.sp,
                            fontFamily = JetBrainsMono,
                            color = Ink,
                            modifier = Modifier.alpha(cursorAlpha)
                        )
                    }
                }
            } else {
                Text(
                    "▊",
                    fontSize = 13.sp,
                    fontFamily = JetBrainsMono,
                    color = Ink,
                    modifier = Modifier.alpha(cursorAlpha)
                )
            }
        } else {
            val toolTextWithoutImages = remember(msg.content) {
                GeneratedImageMarkerRegex.replace(msg.content, "").replace(Regex("""\n{3,}"""), "\n\n").trim()
            }
            val hasGeneratedImages = GeneratedImageMarkerRegex.containsMatchIn(msg.content)
            if (isTool && hasGeneratedImages) {
                Column {
                    GeneratedImagePreview(msg.content)
                    if (toolTextWithoutImages.isNotBlank()) {
                        Text(
                            toolTextWithoutImages,
                            fontSize = 11.sp,
                            fontFamily = JetBrainsMono,
                            color = contentColor,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
                Text(
                    msg.content.ifEmpty {
                        if (isTool) "(empty)" else ""
                    },
                    fontSize = if (isTool) 11.sp else 13.sp,
                    fontFamily = JetBrainsMono,
                    color = contentColor,
                    lineHeight = if (isTool) 16.sp else 20.sp
                )
            }
        }
        }   // 气泡 Box 结束

        // 常驻操作行。工具消息不给(它自己有展开/折叠),流式进行中也不给。
        if (!isTool && !isStreamingMessage) {
            MessageActionsRow(
                content = msg.content,
                onRegenerate = onRegenerate,
                alignEnd = isUser
            )
        }
    }
    // Subtle separator between messages
    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(0.5.dp).background(Border))

    if (showMenu) {
        MessageActionSheet(
            content = msg.content,
            reasoning = msg.reasoning,
            canDelete = onDelete != null,
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("xincode", msg.content))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                showMenu = false
            },
            onCopyReasoning = if (msg.reasoning.isNotBlank()) {
                {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("xincode-think", msg.reasoning))
                    Toast.makeText(context, "已复制思考过程", Toast.LENGTH_SHORT).show()
                    showMenu = false
                }
            } else null,
            onDelete = if (onDelete != null) { { onDelete(); showMenu = false } } else null,
            onDismiss = { showMenu = false }
        )
    }
}

/**
 * 消息下方常驻的操作行(复制 / 重答)。
 *
 * 之前这些操作只藏在长按菜单里,而且交错时间线走的是 AgentTurnBlock,那条路根本没接
 * 长按菜单 —— 等于绝大多数回复都没有任何可操作入口。所以改成常驻小字按钮,两处都用它。
 * 样式压到最低(10sp、次要色),不抢正文。
 */
@Composable
fun MessageActionsRow(
    content: String,
    onRegenerate: (() -> Unit)? = null,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (content.isBlank() && onRegenerate == null) return
    val xc = LocalXinColors.current
    val context = LocalContext.current
    Row(
        modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (content.isNotBlank()) {
            Text(
                "复制",
                fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("xincode", content))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        if (onRegenerate != null) {
            Text(
                "重答",
                fontSize = 10.sp, fontFamily = JetBrainsMono, color = xc.faint,
                modifier = Modifier
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onRegenerate() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun MessageActionSheet(
    content: String,
    reasoning: String,
    canDelete: Boolean,
    onCopy: () -> Unit,
    onCopyReasoning: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    val xc = LocalXinColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息操作", fontFamily = JetBrainsMono, color = xc.ink, fontSize = 14.sp) },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onCopy() }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("复制内容", fontFamily = JetBrainsMono, fontSize = 13.sp, color = xc.ink)
                }
                if (onCopyReasoning != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onCopyReasoning() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("复制思考过程", fontFamily = JetBrainsMono, fontSize = 13.sp, color = xc.ink)
                    }
                }
                if (onDelete != null && canDelete) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onDelete() }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("删除这条消息", fontFamily = JetBrainsMono, fontSize = 13.sp, color = xc.red)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = xc.sub, fontFamily = JetBrainsMono) } },
        containerColor = xc.bg
    )
}

/**
 * In-flow confirmation card rendered inside the message list.
 * Style: terminal aesthetic, thin border, rounded 8dp, three horizontal buttons.
 */
@Composable
private fun ConfirmCard(
    command: String,
    isIrreversible: Boolean,
    onDeny: () -> Unit,
    onAllowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit
) {
    val xc = LocalXinColors.current
    val Bg = xc.bg
    val Ink = xc.ink
    val Sub = xc.sub
    val Green = xc.green
    val Red = xc.red
    val Border = xc.border
    Column(
        Modifier
            .fillMaxWidth()
            .border(0.5.dp, Color(0x1A1A1A17), RoundedCornerShape(8.dp))
            .background(Bg, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        // Header
        Text(
            "xincode 想执行",
            fontSize = 11.sp,
            fontFamily = JetBrainsMono,
            color = Sub
        )
        Spacer(Modifier.height(6.dp))
        // Command line
        Text(
            "  ❯ $command",
            fontSize = 12.sp,
            fontFamily = JetBrainsMono,
            color = Green
        )
        // Risk warning (only for irreversible operations)
        if (isIrreversible) {
            Spacer(Modifier.height(4.dp))
            Text(
                "  ⚠ 不可逆操作，请谨慎确认",
                fontSize = 10.sp,
                fontFamily = JetBrainsMono,
                color = Red
            )
        }
        Spacer(Modifier.height(10.dp))
        // Three buttons
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Text(
                "拒绝",
                fontSize = 12.sp,
                fontFamily = JetBrainsMono,
                color = Sub,
                modifier = Modifier
                    .weight(1f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDeny() }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Box(Modifier.width(0.5.dp).height(24.dp).background(Border))
            Text(
                "仅本次",
                fontSize = 12.sp,
                fontFamily = JetBrainsMono,
                color = Ink,
                modifier = Modifier
                    .weight(1f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onAllowOnce() }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Box(Modifier.width(0.5.dp).height(24.dp).background(Border))
            Text(
                "总是允许",
                fontSize = 12.sp,
                fontFamily = JetBrainsMono,
                color = Green,
                modifier = Modifier
                    .weight(1f)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onAlwaysAllow() }
                    .padding(vertical = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun UserMessageBubble(
    msg: ChatState.MessageUi,
    onDelete: (() -> Unit)?,
    onRegenerate: (() -> Unit)?
) {
    val xc = LocalXinColors.current
    val context = LocalContext.current
    var showMenu by remember(msg.id) { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.78f),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                formatChatTime(msg.timestamp),
                fontSize = 12.sp,
                fontFamily = XinUiFont,
                color = xc.sub,
                modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
            )
            val imagePathRegex = remember { Regex("""###\s*([^(]+)\(图片,路径:([^)]+)\)""") }
            val imageMatches = remember(msg.content) { imagePathRegex.findAll(msg.content).toList() }
            val cleanContent = remember(msg.content) {
                msg.content.replace(Regex("""\n*---\n附件:[\s\S]*?---\n*"""), "").trim()
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
                    .background(xc.bgElevated)
                    .border(0.8.dp, xc.border, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Column {
                    for (match in imageMatches) {
                        val fileName = match.groupValues[1].trim()
                        val path = match.groupValues[2].trim()
                        val file = remember(path) { java.io.File(path) }
                        val bitmap = remember(path) {
                            if (file.exists()) {
                                try {
                                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                                    android.graphics.BitmapFactory.decodeFile(path, opts)
                                } catch (_: Exception) { null }
                            } else null
                        }
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = fileName,
                                modifier = Modifier
                                    .padding(bottom = if (cleanContent.isNotBlank()) 8.dp else 0.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Text("[图片: $fileName]", fontSize = 13.sp, color = xc.sub, modifier = Modifier.padding(bottom = 6.dp))
                        }
                    }
                    if (cleanContent.isNotBlank()) {
                        Text(
                            cleanContent,
                            fontSize = 15.sp,
                            lineHeight = 23.sp,
                            fontFamily = XinUiFont,
                            color = xc.ink
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChatActionIcon(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = "复制消息",
                    tint = xc.ink,
                    onClick = {
                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        manager?.setPrimaryClip(ClipData.newPlainText("xincode", msg.content))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                )
                ChatActionIcon(
                    icon = Icons.Outlined.Share,
                    contentDescription = "转发消息",
                    tint = xc.ink,
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, msg.content)
                        }
                        context.startActivity(Intent.createChooser(intent, "转发消息"))
                    }
                )
                ChatActionIcon(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = "更多消息操作",
                    tint = xc.ink,
                    onClick = { showMenu = true }
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            UserAvatar(size = 44.dp, contentDescription = "我的头像")
            Text(
                "我",
                fontSize = 12.sp,
                fontFamily = XinUiFont,
                color = xc.green,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }

    if (showMenu) {
        MessageActionSheet(
            content = msg.content,
            reasoning = "",
            canDelete = onDelete != null,
            onCopy = {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                manager?.setPrimaryClip(ClipData.newPlainText("xincode", msg.content))
                showMenu = false
            },
            onCopyReasoning = null,
            onDelete = onDelete?.let { action -> { action(); showMenu = false } },
            onDismiss = { showMenu = false }
        )
    }
}

@Composable
private fun ClaudeGreetingHero() {
    val xc = LocalXinColors.current
    Box(
        modifier = Modifier.fillMaxWidth().height(460.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(64.dp)) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val inner = 8.dp.toPx()
                val outer = 27.dp.toPx()
                repeat(12) { index ->
                    val angle = index * (Math.PI / 6.0)
                    drawLine(
                        color = xc.green,
                        start = center + androidx.compose.ui.geometry.Offset(
                            (kotlin.math.cos(angle) * inner).toFloat(),
                            (kotlin.math.sin(angle) * inner).toFloat()
                        ),
                        end = center + androidx.compose.ui.geometry.Offset(
                            (kotlin.math.cos(angle) * outer).toFloat(),
                            (kotlin.math.sin(angle) * outer).toFloat()
                        ),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "Evening thoughts",
                fontFamily = XinSerifFont,
                fontWeight = FontWeight.Medium,
                fontSize = 30.sp,
                lineHeight = 36.sp,
                color = xc.ink
            )
        }
    }
}


@Composable
private fun AddToChatSheet(
    webSearchOn: Boolean,
    projectName: String,
    toolAccessMode: String,
    mcpNames: List<String> = emptyList(),
    skillNames: List<String> = emptyList(),
    onClose: () -> Unit,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onToggleWebSearch: () -> Unit,
    onAddProject: () -> Unit,
    onToolAccess: () -> Unit,
    onMcp: () -> Unit,
    onSkill: () -> Unit
) {
    val xc = LocalXinColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        // Top bar: ✕ on left, centered "Add to chat"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = t("关闭"), tint = xc.ink)
            }
            Spacer(Modifier.weight(1f))
            Text(t("Add to chat"), fontFamily = XinSerifFont, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(36.dp))
        }

        Spacer(Modifier.height(16.dp))

        // Row of 3 square cards: Camera, Photos, Files
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AddSheetMediaCard(Modifier.weight(1f), Icons.Outlined.PhotoCamera, t("Camera"), onCamera)
            AddSheetMediaCard(Modifier.weight(1f), Icons.Outlined.Image, t("Photos"), onPhotos)
            AddSheetMediaCard(Modifier.weight(1f), Icons.Outlined.Description, t("Files"), onFiles)
        }

        Spacer(Modifier.height(18.dp))

        // Toggle row 1: Web search
        AddSheetToggleRow(
            icon = Icons.Outlined.Public,
            title = t("Web search"),
            subtitle = "",
            checked = webSearchOn,
            enabled = true,
            onCheckedChange = { onToggleWebSearch() }
        )

        // Toggle row 2: Memory
        AddSheetToggleRow(
            icon = Icons.Outlined.Refresh,
            title = t("Memory"),
            subtitle = t("Can't be changed for this chat"),
            checked = true,
            enabled = false,
            onCheckedChange = {}
        )

        // Action row 3: Add to project
        AddSheetActionRow(
            icon = Icons.Outlined.Folder,
            title = t("Add to project"),
            subtitle = projectName,
            onClick = onAddProject
        )

        // Action row 4: MCP (original entry name kept)
        AddSheetActionRow(
            icon = Icons.Outlined.Extension,
            title = t("MCP"),
            subtitle = if (mcpNames.isEmpty()) t("连接本地或远程工具服务") else tx("已配置 %s 个服务", mcpNames.size),
            onClick = onMcp
        )

        // Action row 5: Skills (original entry name kept)
        AddSheetActionRow(
            icon = Icons.Outlined.Lightbulb,
            title = t("Skills"),
            subtitle = if (skillNames.isEmpty()) t("将技能指令添加到输入框") else tx("已配置 %s 个技能", skillNames.size),
            onClick = onSkill
        )

        // Action row 6: 工具权限
        AddSheetActionRow(
            icon = Icons.Outlined.Settings,
            title = t("工具权限"),
            subtitle = t(toolAccessMode),
            onClick = onToolAccess
        )

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SelectModelBottomSheet(
    currentModel: String,
    availableModels: List<String>,
    effortLabel: String,
    permissionLabel: String,
    contextLabel: String,
    collabOn: Boolean,
    onClose: () -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenEffort: () -> Unit,
    onOpenMode: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenEnhance: () -> Unit,
    onOpenGoal: () -> Unit,
    onToggleCollab: () -> Unit
) {
    val xc = LocalXinColors.current

    // Preset / displayed model metadata (matches Screenshot 23:11:53)
    data class ModelItemUi(val id: String, val name: String, val badge: String?, val desc: String)
    val defaultModels = listOf(
        ModelItemUi("Fable 5.1", "Fable 5.1", null, "For your toughest challenges"),
        ModelItemUi("Opus 5", "Opus 5", null, "For complex tasks"),
        ModelItemUi("Sonnet 5", "Sonnet 5", null, "Most efficient for everyday tasks"),
        ModelItemUi("Haiku 4.5", "Haiku 4.5", null, "Fastest for quick answers")
    )

    val displayList = remember(availableModels, currentModel) {
        if (availableModels.isNotEmpty()) {
            availableModels.map { id ->
                val matchingDefault = defaultModels.firstOrNull { it.id.equals(id, true) }
                if (matchingDefault != null) matchingDefault
                else {
                    val desc = when {
                        id.contains("r1", true) || id.contains("reason", true) -> "推理与深度思考模型"
                        id.contains("flash", true) || id.contains("mini", true) -> "快速轻量化响应"
                        id.contains("pro", true) || id.contains("opus", true) -> "高强度复杂工程架构设计"
                        else -> "智能体通用对话与编程模型"
                    }
                    ModelItemUi(id = id, name = id, badge = if (id.contains("pro", true)) "Pro" else null, desc = desc)
                }
            }
        } else {
            defaultModels
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        // Header: ✕ on left, centered "Select model"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = t("关闭"), tint = xc.ink)
            }
            Spacer(Modifier.weight(1f))
            Text(t("Select model"), fontFamily = XinSerifFont, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(36.dp))
        }

        Spacer(Modifier.height(14.dp))

        // Model list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
        ) {
            items(displayList, key = { it.id }) { item ->
                val isSelected = item.id.equals(currentModel, true) ||
                    (currentModel.isBlank() && item.name == "Sonnet 5")

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectModel(item.id) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.name,
                                fontFamily = XinUiFont,
                                fontWeight = FontWeight.Medium,
                                fontSize = 17.sp,
                                color = xc.ink
                            )
                            if (item.badge != null) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(xc.activeBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(item.badge, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = xc.green)
                                }
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(item.desc, fontFamily = XinUiFont, fontSize = 13.sp, color = xc.sub)
                    }

                    if (isSelected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = "当前选中",
                            tint = xc.green,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(xc.border))
        Spacer(Modifier.height(10.dp))

        // Bottom pinned card: Effort
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable { onOpenEffort() }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(xc.activeBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, tint = xc.ink, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Effort"), fontFamily = XinUiFont, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = xc.ink)
                Text(effortLabel, fontFamily = XinUiFont, fontSize = 13.sp, color = xc.sub)
            }
            Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null, tint = xc.sub, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(xc.border))
        Spacer(Modifier.height(4.dp))

        // ── 更多设置：原有核心功能入口（对应 Claude 样式多出的入口行） ──
        AddSheetActionRow(
            icon = Icons.Outlined.Settings,
            title = t("访问权限"),
            subtitle = t(permissionLabel),
            onClick = onOpenMode
        )
        AddSheetActionRow(
            icon = Icons.Outlined.Info,
            title = t("上下文用量"),
            subtitle = contextLabel,
            onClick = onOpenStats
        )
        AddSheetActionRow(
            icon = Icons.Outlined.Lightbulb,
            title = t("增强提示词"),
            subtitle = t("优化当前输入"),
            onClick = onOpenEnhance
        )
        AddSheetActionRow(
            icon = Icons.Outlined.Bolt,
            title = t("目标模式"),
            subtitle = t("前往 Goal 模式"),
            onClick = onOpenGoal
        )
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onToggleCollab() }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(xc.activeBg), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Psychology, contentDescription = t("多Agent协同"), tint = xc.ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t("多Agent协同"), fontFamily = XinUiFont, fontSize = 15.sp, color = xc.ink)
                Text(if (collabOn) t("已开启：主脑+子智能体并行") else t("已关闭"), fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Switch(
                checked = collabOn,
                onCheckedChange = { onToggleCollab() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2C64E3)
                )
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun EffortBottomSheet(
    currentLevel: Int,
    onBack: () -> Unit,
    onSelectLevel: (Int) -> Unit
) {
    val xc = LocalXinColors.current

    data class EffortOption(val level: Int, val label: String)
    val options = listOf(
        EffortOption(0, "Low"),
        EffortOption(1, "Medium"),
        EffortOption(2, "High"),
        EffortOption(3, "Extra"),
        EffortOption(4, "Max")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        // Top bar: ← on left, centered "Effort"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = t("返回"), tint = xc.ink)
            }
            Spacer(Modifier.weight(1f))
            Text(t("Effort"), fontFamily = XinSerifFont, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(36.dp))
        }

        Spacer(Modifier.height(16.dp))

        options.forEach { opt ->
            val isSelected = opt.level == currentLevel
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectLevel(opt.level) }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    t(opt.label),
                    fontFamily = XinUiFont,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 17.sp,
                    color = if (isSelected) xc.green else xc.ink
                )
                Spacer(Modifier.weight(1f))
                if (isSelected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = t("已选择"),
                        tint = xc.green,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ToolAccessBottomSheet(
    currentMode: String,
    onBack: () -> Unit,
    onSelectMode: (String) -> Unit
) {
    val xc = LocalXinColors.current

    data class ToolOption(val key: String, val title: String, val desc: String)
    val options = listOf(
        ToolOption("Auto", "Auto", "模型按需自主调用工具"),
        ToolOption("On demand", "On demand", "需要时加载，消息更多、精度稍低"),
        ToolOption("Always available", "Always available", "启动即就绪，消息更少、精度更高")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        // Top bar: ← on left, centered "工具权限"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = t("返回"), tint = xc.ink)
            }
            Spacer(Modifier.weight(1f))
            Text(t("工具权限"), fontFamily = XinSerifFont, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(36.dp))
        }

        Spacer(Modifier.height(16.dp))

        options.forEach { opt ->
            val isSelected = opt.key == currentMode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onSelectMode(opt.key) }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t(opt.title),
                        fontFamily = XinUiFont,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 17.sp,
                        color = if (isSelected) xc.green else xc.ink
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(t(opt.desc), fontFamily = XinUiFont, fontSize = 13.sp, color = xc.sub)
                }
                if (isSelected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "已选择",
                        tint = xc.green,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun AddToProjectBottomSheet(
    projects: List<ProjectEntity>,
    currentProjectId: Long?,
    onBack: () -> Unit,
    onSelectProject: (ProjectEntity) -> Unit
) {
    val xc = LocalXinColors.current
    var query by remember { mutableStateOf("") }

    val filtered = remember(projects, query) {
        if (query.isBlank()) projects else projects.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        // Top bar: ← on left, centered "Add to project"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = t("返回"), tint = xc.ink)
            }
            Spacer(Modifier.weight(1f))
            Text(t("Add to project"), fontFamily = XinSerifFont, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = xc.ink)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(36.dp))
        }

        Spacer(Modifier.height(14.dp))

        // Search projects input
        TextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(xc.bg)
                .border(0.8.dp, xc.border, RoundedCornerShape(16.dp)),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = xc.sub) },
            placeholder = { Text(t("Search projects"), fontFamily = XinUiFont, fontSize = 15.sp, color = xc.faint) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = xc.green,
                focusedTextColor = xc.ink,
                unfocusedTextColor = xc.ink
            ),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = XinUiFont, fontSize = 15.sp)
        )

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
        ) {
            items(filtered, key = { it.id }) { proj ->
                val isSelected = proj.id == currentProjectId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectProject(proj) }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = if (isSelected) xc.green else xc.sub,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        proj.name,
                        fontFamily = XinUiFont,
                        fontSize = 16.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) xc.green else xc.ink,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = xc.green,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AddSheetMediaCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    val xc = LocalXinColors.current
    Column(
        modifier = modifier
            .heightIn(min = 104.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(xc.bg)
            .border(0.8.dp, xc.border, RoundedCornerShape(18.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(46.dp).clip(androidx.compose.foundation.shape.CircleShape).background(xc.activeBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = xc.ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontFamily = XinUiFont, fontSize = 13.sp, color = xc.ink)
    }
}

@Composable
private fun AddSheetToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val xc = LocalXinColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(xc.activeBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = title, tint = xc.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = XinUiFont, fontSize = 15.sp, color = xc.ink)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontFamily = XinUiFont, fontSize = 11.sp, color = xc.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF2C64E3)
            )
        )
    }
}

@Composable
private fun AddSheetActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val xc = LocalXinColors.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(xc.activeBg), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = title, tint = xc.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = XinUiFont, fontSize = 15.sp, color = xc.ink)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontFamily = XinUiFont, fontSize = 12.sp, color = xc.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null, tint = xc.sub, modifier = Modifier.size(20.dp))
    }
}
