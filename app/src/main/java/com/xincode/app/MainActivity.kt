package com.xincode.app

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import com.xincode.data.IdentityEntity
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xincode.app.R
import com.xincode.data.SessionEntity
import com.xincode.tools.RootDiagnosticResult
import com.xincode.tools.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private val JetBrainsMono = FontFamily(Font(R.font.jetbrains_mono, FontWeight.Normal))
private val Ink: Color @Composable get() = LocalXinColors.current.ink

class MainActivity : ComponentActivity() {

    private lateinit var voiceInputHelper: VoiceInputHelper
    private lateinit var ttsHelper: TtsHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("XINCODE", "MainActivity.onCreate START")
        val app = application as XincodeApplication

        voiceInputHelper = VoiceInputHelper(this)
        ttsHelper = TtsHelper(this)
        ttsHelper.initialize()

        setContent {
            XinTheme(dark = app.darkMode) {
            var currentPage by remember { mutableStateOf("chat") }
            // 终端页可从「对话页顶栏」或「环境配置页」进入;记录来源,退出时精确回到来处(修返回逻辑 bug)。
            var terminalOrigin by remember { mutableStateOf("chat") }
            var rootDiagResult by remember { mutableStateOf<RootDiagnosticResult?>(null) }
            var editingIdentityId by remember { mutableStateOf<Long?>(null) }
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val drawerScope = rememberCoroutineScope()

            // 数据库开不起来被自动重建过:必须告诉用户,不能让数据「无声消失」。
            // 只在本次进程内提示一次,消掉后不再打扰。
            var dbRecovery by remember {
                mutableStateOf(com.xincode.data.AppDatabase.lastRecoveredFailure)
            }
            if (dbRecovery != null) {
                val backup = com.xincode.data.AppDatabase.lastBackupName
                AlertDialog(
                    onDismissRequest = { dbRecovery = null },
                    title = { Text("数据库已重建") },
                    text = {
                        Text(
                            buildString {
                                append("上次的数据文件打不开,应用已新建了一个空数据库,否则会一直闪退。\n\n")
                                if (backup != null) {
                                    append("旧数据没有删除,已改名保留在应用私有目录:\n$backup\n\n")
                                    append("如果里面有重要内容,先别卸载应用 —— 卸载会连备份一起清掉。")
                                } else {
                                    append("旧数据文件已损坏且无法保留。")
                                }
                                append("\n\n原因:$dbRecovery")
                            }
                        )
                    },
                    confirmButton = { TextButton(onClick = { dbRecovery = null }) { Text("知道了") } }
                )
            }

            // 启动时静默检查更新:失败/限流/已最新一律安静跳过,只有真有新版才弹窗。
            var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
            LaunchedEffect(Unit) {
                updateInfo = UpdateChecker.check(
                    context = this@MainActivity,
                    settingGet = { k -> app.database.settingDao().get(k) },
                    settingPut = { k, v -> app.database.settingDao().put(k, v) }
                )
            }
            updateInfo?.let { info ->
                UpdateDialog(
                    info = info,
                    currentVersion = UpdateChecker.currentVersion(this@MainActivity),
                    onDismiss = { updateInfo = null },
                    onSkip = {
                        drawerScope.launch {
                            UpdateChecker.skipVersion(info.version) { k, v -> app.database.settingDao().put(k, v) }
                        }
                        updateInfo = null
                    }
                )
            }

            // Collect sessions from Room
            val sessions by app.sessionListFlow.collectAsState(initial = emptyList())
            val currentSessionId = app.currentSessionId
            val starredSessions by app.starredSessionsFlow.collectAsState(initial = emptyList())
            val ungroupedSessions by app.ungroupedSessionsFlow.collectAsState(initial = emptyList())
            val goalSessions by app.goalSessionsFlow.collectAsState(initial = emptyList())
            val projects by app.projectListFlow.collectAsState(initial = emptyList())
            val groupRooms by app.database.groupRoomDao().observeRooms().collectAsState(initial = emptyList())
            // 从侧栏直接点进某个房间时带上 id,群聊页据此跳过列表直接开那间
            var openRoomId by remember { mutableStateOf<Long?>(null) }
            val allIdentities by app.identityListFlow.collectAsState(initial = emptyList())
            // 群聊专用的角色卡不进主对话列表 —— 它们写的是团队里的一个位置,
            // 单独拿来跟你对话没有意义,混在一起只会把真正能用的卡淹掉。
            val identities = remember(allIdentities) {
                allIdentities.filter { it.scope != IdentityEntity.SCOPE_GROUP }
            }
            val activeIdentityId = app.activeIdentityId

            // Build project→sessions map from full sessions list
            val projectSessionsMap = remember(sessions) {
                sessions.filter { it.projectId != null }.groupBy { it.projectId!! }
            }

            // 侧滑/返回:抽屉开→关;子页→回上一页;聊天页→连续两次(2s 内)才退出应用。
            var lastBackMs by remember { mutableStateOf(0L) }
            BackHandler(enabled = drawerState.isOpen) { drawerScope.launch { drawerState.close() } }
            BackHandler(enabled = !drawerState.isOpen && currentPage != "chat") {
                currentPage = if (currentPage == "terminal") terminalOrigin else parentPageOf(currentPage)
            }
            BackHandler(enabled = !drawerState.isOpen && currentPage == "chat") {
                val now = System.currentTimeMillis()
                if (now - lastBackMs < 2000) this@MainActivity.finish()
                else { lastBackMs = now; Toast.makeText(this@MainActivity, "再滑一次返回退出", Toast.LENGTH_SHORT).show() }
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerTonalElevation = 0.dp,
                        drawerContainerColor = LocalXinColors.current.bg,
                        drawerShape = RoundedCornerShape(0.dp)
                    ) {
                        SidebarContent(
                        currentSessionId = currentSessionId,
                        starredSessions = starredSessions,
                        ungroupedSessions = ungroupedSessions,
                        projects = projects,
                        projectSessionsMap = projectSessionsMap,
                        onCreateNew = {
                            val newId = app.createNewSession()
                            app.switchToSession(newId)
                            currentPage = "chat"
                        },
                        onSelectSession = { id ->
                            app.switchToSession(id)
                            currentPage = "chat"
                        },
                        onRenameSession = { id, title ->
                            app.renameSession(id, title)
                        },
                        onDeleteSession = { id ->
                            app.deleteSession(id)
                            if (id == currentSessionId) {
                                currentPage = "chat"
                            }
                        },
                        onNavigateToSettings = { currentPage = "settings" },
                        onClose = { drawerScope.launch { drawerState.close() } },
                        onCreateProject = { name -> app.createProject(name) },
                        onCreateNewInProject = { projectId ->
                            val newId = app.createSessionInProject(projectId)
                            app.switchToSession(newId)
                            currentPage = "chat"
                        },
                        onRenameProject = { id, name -> app.renameProject(id, name) },
                        onDeleteProject = { id -> app.deleteProject(id) },
                        onSetProjectWorkspace = { id, path -> app.updateProjectWorkspace(id, path) },
                        onToggleProject = { id -> app.toggleProjectExpanded(id) },
                        onMoveSessionToProject = { sessionId, projectId -> app.moveSessionToProject(sessionId, projectId) },
                        onSetSessionStarred = { id, starred -> app.setSessionStarred(id, starred) },
                        identities = identities,
                        activeIdentityId = activeIdentityId,
                        onSetActiveIdentity = { id -> app.setActiveIdentity(id) },
                        onCreateIdentity = { editingIdentityId = null; currentPage = "identity_edit" },
                        onNavigateToIdentityList = { currentPage = "identity_list" },
                        onSearchMessages = { q -> app.searchMessages(q) },
                        goalSessions = goalSessions,
                        goalLiveStatus = { id -> app.goalRunStatus[id] ?: "" },
                        groupRooms = groupRooms,
                        onOpenGroupRooms = { currentPage = "group_rooms" },
                        onOpenGroupRoom = { rid ->
                            openRoomId = rid
                            currentPage = "group_rooms"
                        },
                        onCreateGoal = {
                            val newId = app.createGoalSession()
                            app.switchToSession(newId)
                            currentPage = "chat"
                        },
                        onSelectGoal = { id ->
                            app.switchToSession(id)
                            currentPage = "chat"
                        }
                    )
                    }
                },
                gesturesEnabled = drawerState.isOpen
            ) {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(200))
                    },
                    label = "page_transition"
                ) { page ->
                    when (page) {
                    "chat" -> {
                        val active = runBlocking { app.database.providerConfigDao().getActive() }
                        val names = active?.enabledModelIds ?: emptyList()
                        val skillNames = remember { mutableStateOf<List<String>>(emptyList()) }
                        val mcpNames = remember { mutableStateOf<List<String>>(emptyList()) }
                        LaunchedEffect(Unit) {
                            skillNames.value = withContext(Dispatchers.IO) {
                                app.database.skillDao().getAll().map { it.name }
                            }
                            mcpNames.value = withContext(Dispatchers.IO) {
                                app.database.mcpServerDao().getAll().map { it.name }
                            }
                        }
                        val curIsGoal = goalSessions.any { it.id == app.currentSessionId }
                        val curGoal = goalSessions.firstOrNull { it.id == app.currentSessionId }
                        ChatScreen(
                            chatState = app.agentChatState,
                            currentModel = app.currentModelLabel,
                            availableModels = names,
                            isGoalSession = curIsGoal,
                            goalStatusCode = curGoal?.goalStatus ?: "",
                            goalLiveText = app.goalRunStatus[app.currentSessionId] ?: "",
                            goalRunning = app.isGoalRunning(app.currentSessionId),
                            onStartGoal = { text -> app.startGoalForSession(app.currentSessionId, text) },
                            onStopGoal = { app.stopGoal(app.currentSessionId) },
                            powerMode = app.currentPowerMode,
                            onSwitchModel = { modelId -> app.switchModel(modelId) },
                            thinkingEnabled = app.thinkingEnabled,
                            thinkingLevel = app.thinkingLevel,
                            onThinkingEnabledChange = { app.updateThinkingEnabled(it) },
                            onThinkingLevelChange = { app.updateThinkingLevel(it) },
                            onNavigateToSettings = { currentPage = "settings" },
                            onNavigateToWorkflow = { currentPage = "workflow" },
                            onNavigateToGoal = { currentPage = "goal" },
                            onNavigateToAgentScene = { currentPage = "agent_scene" },
                            onNavigateToStats = { currentPage = "stats" },
                            onNavigateToTerminal = { terminalOrigin = "chat"; currentPage = "terminal" },
                            subAgentActive = app.subAgentScene.brainBusy,
                            ttsHelper = ttsHelper,
                            onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                            planState = app.planState,
                            skillNames = skillNames.value,
                            onRegenerate = { msgId -> app.regenerateFromMessage(msgId) },
                            onDeleteMessage = { msgId -> app.deleteMessage(msgId) },
                            onCompactContext = { app.compactContext() },
                            onInsertSkill = { name -> app.insertSkillIntoInput(name) },
                            mcpNames = mcpNames.value,
                            onInsertMcp = { name -> app.insertMcpIntoInput(name) },
                            onNavigateToMcp = { currentPage = "mcp" },
                            onSetConversationWorkspace = { path -> app.setConversationWorkspace(path) },
                            onSetWebSearchEnabled = { enabled -> app.setWebSearchEnabled(enabled) },
                            permissionMode = app.permissionModeState,
                            onUpdatePermissionMode = { mode -> app.updatePermissionMode(mode) },
                            collabMode = app.collabModeEnabled,
                            onSetCollabMode = { on -> app.setCollabMode(on) }
                        )
                    }
                    "settings" -> SettingsScreen(
                        onBack = { currentPage = "chat" },
                        onNavigateToSupplierConfig = { currentPage = "supplier" },
                        onNavigateToModelMarket = { currentPage = "model_market" },
                        onNavigateToGit = { currentPage = "git_config" },
                        onNavigateToAuditLog = { currentPage = "audit" },
                        onNavigateToMemoryStorage = { currentPage = "memory_storage" },
                        onNavigateToSkills = { currentPage = "skills" },
                        onNavigateToMcp = { currentPage = "mcp" },
                        onNavigateToCuratedMemory = { currentPage = "curated_memory" },
                        onNavigateToCron = { currentPage = "cron_jobs" },
                        onNavigateToContextCompress = { currentPage = "context_compress" },
                        workspaceRoot = app.workspaceRootGlobal,
                        onUpdateWorkspaceRoot = { path -> app.updateWorkspaceRoot(path) },
                        onNavigateToAuxModels = { currentPage = "aux_models" },
                        onNavigateToFunctionModels = { currentPage = "function_models" },
                        onNavigateToLanDevices = { currentPage = "lan_devices" },
                        onNavigateToLogs = { currentPage = "logs" },
                        onNavigateToCodeIndex = { currentPage = "code_index" },
                        onNavigateToUsageStats = { currentPage = "usage_stats" },
                        onNavigateToKanban = { currentPage = "kanban" },
                        onNavigateToGroupRooms = { currentPage = "group_rooms" },
                        onNavigateToProfiles = { currentPage = "profiles" },
                        onNavigateToSubAgents = { currentPage = "sub_agents" },
                        onNavigateToEnvConfig = { currentPage = "env_config" },
                        onNavigateToAbout = { currentPage = "about" },
                        darkMode = app.darkMode,
                        onUpdateDarkMode = { app.updateDarkMode(it) },
                        rootDetector = app.rootDetector,
                        permissionMode = app.permissionModeState,
                        onUpdatePermissionMode = { mode -> app.updatePermissionMode(mode) },
                        onRootDiagnostic = {
                            GlobalScope.launch {
                                try {
                                    val id = RootShellManager.execute("id")
                                    val whoami = RootShellManager.execute("whoami")
                                    val lsSd = RootShellManager.execute("ls /sdcard | head -10")
                                    val catBuild = RootShellManager.execute("cat /system/build.prop 2>/dev/null | head -3")
                                    val lsData = RootShellManager.execute("ls /data/data 2>/dev/null | head -10")
                                    val errors = mutableListOf<String>()
                                    if (id.exitCode != 0) errors.add("id failed: ${id.stderr.take(60)}")
                                    if (whoami.exitCode != 0) errors.add("whoami failed: ${whoami.stderr.take(60)}")
                                    rootDiagResult = com.xincode.tools.RootDiagnosticResult(
                                        id = id.stdout.take(200),
                                        whoami = whoami.stdout.take(200),
                                        lsSdcard = lsSd.stdout.take(300),
                                        catSystemBuild = catBuild.stdout.take(200),
                                        lsDataData = lsData.stdout.take(200),
                                        errors = errors
                                    )
                                } catch (_: Exception) {}
                            }
                        },
                        rootDiagnosticResult = rootDiagResult,
                        searchApiKey = app.webSearchTool.apiKey,
                        onUpdateSearchApiKey = { key -> app.updateSearchApiKey(key) },
                        onOpenDrawer = { drawerScope.launch { drawerState.open() } }
                    )
                    "model_market" -> ModelMarketScreen(
                        database = app.database,
                        keystore = app.keystore,
                        openAiClient = app.openAiClient,
                        onBack = { currentPage = "settings" }
                    )
                    "git_config" -> GitConfigScreen(
                        database = app.database,
                        keystore = app.keystore,
                        onBack = { currentPage = "settings" }
                    )
                    "supplier" -> SupplierConfigScreen(
                        database = app.database,
                        keystore = app.keystore,
                        openAiClient = app.openAiClient,
                        onBack = { currentPage = "settings" }
                    )
                    "audit" -> AuditLogScreen(
                        onBack = { currentPage = "settings" },
                        database = app.database
                    )
                    "workflow" -> WorkflowScreen(
                        agentCore = app.agentCore,
                        workflowState = app.workflowState,
                        onBack = { currentPage = "chat" },
                        onNavigateToReplay = { currentPage = "replay" }
                    )
                    "replay" -> {
                        val traj = runBlocking { app.database.trajectoryDao().getAll().firstOrNull() }
                        if (traj != null) {
                            ReplayScreen(trajectory = traj, onBack = { currentPage = "workflow" })
                        } else {
                            currentPage = "workflow"
                        }
                    }
                    "goal" -> GoalScreen(
                        goalRunner = app.goalRunner,
                        onBack = { currentPage = "chat" }
                    )
                    "memory_storage" -> MemoryStorageScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "skills" -> SkillScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "mcp" -> McpServerScreen(
                        mcpManager = app.mcpManager,
                        onBack = { currentPage = "settings" }
                    )
                    "curated_memory" -> CuratedMemoryScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "cron_jobs" -> CronJobsScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "aux_models" -> AuxModelsScreen(
                        database = app.database,
                        keystore = app.keystore,
                        onBack = { currentPage = "settings" }
                    )
                    "lan_devices" -> LanDiscoveryScreen(onBack = { currentPage = "settings" })
                    "logs" -> LogViewerScreen(onBack = { currentPage = "settings" })
                    "code_index" -> CodeIndexScreen(database = app.database, onBack = { currentPage = "settings" })
                    "usage_stats" -> UsageStatsScreen(database = app.database, onBack = { currentPage = "settings" })
                    "kanban" -> KanbanScreen(database = app.database, planState = app.planState, runner = app.kanbanRunner, onBack = { currentPage = "settings" })
                    "group_rooms" -> GroupRoomsScreen(database = app.database, keystore = app.keystore,
                        initialRoomId = openRoomId,
                        onConsumedInitialRoom = { openRoomId = null },
                        // 成员工作台现在是群聊【内嵌】的一层,不再跳出到主对话页
                        onBack = { openRoomId = null; currentPage = "chat" })
                    "profiles" -> ProfilesScreen(database = app.database, onBack = { currentPage = "settings" })
                    "function_models" -> FunctionModelsScreen(
                        database = app.database,
                        keystore = app.keystore,
                        onBack = { currentPage = "settings" }
                    )
                    "sub_agents" -> SubAgentsScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "context_compress" -> ContextCompressionScreen(
                        database = app.database,
                        onBack = { currentPage = "settings" }
                    )
                    "about" -> AboutScreen(app = app, onBack = { currentPage = "settings" })
                    "env_config" -> EnvConfigScreen(
                        onBack = { currentPage = "settings" },
                        onOpenTerminal = { terminalOrigin = "env_config"; currentPage = "terminal" }
                    )
                    "agent_scene" -> AgentSceneScreen(
                        scene = app.subAgentScene,
                        database = app.database,
                        onBack = { currentPage = "chat" }
                    )
                    "stats" -> StatsScreen(
                        database = app.database,
                        chatState = app.agentChatState,
                        sessionId = app.currentSessionId,
                        onBack = { currentPage = "chat" }
                    )
                    "terminal" -> TerminalScreen(
                        terminal = app.terminalState,
                        onBack = { currentPage = terminalOrigin }
                    )
                    "identity_list" -> {
                        val sessionCounts = remember(sessions) {
                            sessions.filter { it.identityId != null }.groupingBy { it.identityId!! }.eachCount()
                        }
                        IdentityListScreen(
                            identities = identities,
                            activeId = activeIdentityId,
                            onBack = { currentPage = "chat" },
                            onCreateNew = { editingIdentityId = null; currentPage = "identity_edit" },
                            onEdit = { id -> editingIdentityId = id; currentPage = "identity_edit" },
                            onSetActive = { id -> app.setActiveIdentity(id) },
                            onToggleStar = { id, starred -> app.setIdentityStarred(id, starred) },
                            onRename = { id, name -> app.renameIdentity(id, name) },
                            onDelete = { id -> app.deleteIdentity(id) },
                            sessionCountForIdentity = { id -> sessionCounts[id] ?: 0 }
                        )
                    }
                    "identity_edit" -> {
                        val target = identities.firstOrNull { it.id == editingIdentityId }
                        IdentityEditScreen(
                            identity = target,
                            onBack = { currentPage = "identity_list" },
                            onSave = { r ->
                                if (target != null) {
                                    app.updateIdentity(target.copy(
                                        name = r.name, systemPrompt = r.systemPrompt, temperature = r.temperature,
                                        description = r.description, openingStatement = r.openingStatement,
                                        marks = r.marks, allowedTools = r.allowedTools
                                    ))
                                } else {
                                    app.createIdentity(r)
                                }
                                currentPage = "identity_list"
                            },
                            onDelete = if (target != null) {
                                { app.deleteIdentity(target.id); currentPage = "identity_list" }
                            } else null
                        )
                    }
                }
                }
            }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::ttsHelper.isInitialized) ttsHelper.shutdown()
        if (::voiceInputHelper.isInitialized) voiceInputHelper.reset()
    }
}
/** 各子页返回时的「上一页」映射:设置类子页回设置,其余回聊天。 */
private fun parentPageOf(page: String): String = when (page) {
    "settings" -> "chat"
    "supplier", "model_market", "git_config", "audit", "memory_storage", "skills", "mcp", "curated_memory",
    "cron_jobs", "aux_models", "function_models", "sub_agents", "env_config", "context_compress", "about",
    "lan_devices", "logs", "usage_stats", "kanban", "group_rooms", "profiles", "code_index" -> "settings"
    "replay" -> "workflow"
    "identity_edit" -> "identity_list"
    "identity_list" -> "settings"
    "workflow", "goal", "agent_scene" -> "chat"
    else -> "chat"
}
