package com.xincode.app

import android.app.Application
import android.util.Log
import com.xincode.core.AgentCore
import com.xincode.core.PowerMode
import com.xincode.core.ToolRegistry
import com.xincode.core.ToolSessionContext
import com.xincode.data.AppDatabase
import com.xincode.data.SessionEntity
import com.xincode.data.GlobalSettingsEntity
import com.xincode.data.ProjectEntity
import com.xincode.data.ProjectsRepository
import com.xincode.data.IdentityEntity
import com.xincode.data.IdentityRepository
import com.xincode.provider.OpenAiClient
import com.xincode.security.KeystoreProvider
import com.xincode.security.PermissionMode
import com.xincode.security.SecurityGateImpl
import com.xincode.security.ToolConfirmResult
import com.xincode.tools.FileReadTool
import com.xincode.tools.FileWriteTool
import com.xincode.tools.ListDirTool
import com.xincode.tools.DeleteFileTool
import com.xincode.tools.MakeDirectoryTool
import com.xincode.tools.DownloadFileTool
import com.xincode.tools.SleepTool
import com.xincode.tools.EditTool
import com.xincode.tools.MultiEditTool
import com.xincode.tools.GrepTool
import com.xincode.tools.GlobTool
import com.xincode.tools.ShellExecTool
import com.xincode.tools.SuExecTool
import com.xincode.tools.WebSearchTool
import com.xincode.tools.WebFetchTool
import com.xincode.tools.RootShellManager
import com.xincode.tools.WorkspaceContext
import com.xincode.provider.HttpCacheProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runBlocking
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal fun finalAssistantContentSince(
    messages: List<ChatState.MessageUi>,
    startIndex: Int
): String = messages
    .drop(startIndex.coerceIn(0, messages.size))
    .lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
    ?.content
    .orEmpty()
    .trim()

/** 群聊房间内等待用户批准的工具确认卡。 */
data class GroupConfirmRequest(
    val sessionId: Long,
    val memberName: String,
    val toolName: String,
    val preview: String
)

class XincodeApplication : Application() {

    /** Long-lived work that must survive navigation away from its Compose screen. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var database: AppDatabase
        private set
    lateinit var keystore: KeystoreProvider
        private set
    lateinit var openAiClient: OpenAiClient

    /** 用量记账用的当前模型/供应商名。切换配置时刷新;取不到时留空,不影响记账。 */
    @Volatile
    var currentModelName: String = ""
    @Volatile
    var currentProviderName: String = ""
    /** 功能模型配置绑定的 client —— 见 FunctionModels。未单独配置时行为与 openAiClient 一致。 */
    lateinit var reviewClient: OpenAiClient
    lateinit var subAgentClient: OpenAiClient
    lateinit var wolfpackClient: OpenAiClient
    lateinit var cronClient: OpenAiClient
    lateinit var compactClient: OpenAiClient
        private set
    lateinit var chatState: ChatState
        private set

    // ---- per-session agent pool ----
    // 每个会话拥有【独立】的 AgentCore + AgentChatState。切换会话时,旧会话的实例留在池中【继续在后台跑】,
    // 它的运行循环只往自己固定的 sessionId 写(不会污染新会话),也不会被 stop() 打断(避免 HTTP 400)。
    // UI 通过可观察的 [_active] 绑定当前会话的那一对;切换即换绑,旧对照常后台运行。
    private class SessionAgents(
        val sessionId: Long,
        val core: AgentCore,
        val chat: AgentChatState,
        var collector: kotlinx.coroutines.Job? = null
    )
    private val sessionPool = HashMap<Long, SessionAgents>()
    private var _active by mutableStateOf<SessionAgents?>(null)
    private val active: SessionAgents get() = _active ?: error("agents not initialized")
    /** 当前前台会话的 AgentCore(可观察:切换会话时 Compose 会重组绑定新实例)。 */
    val agentCore: AgentCore get() = active.core
    /** 当前前台会话的 AgentChatState(可观察)。 */
    val agentChatState: AgentChatState get() = active.chat
    /** sessionId -> 是否忙碌(由各 core 的状态收集器在主线程维护)。用于前台服务保活判断。 */
    private val coreBusy = HashMap<Long, Boolean>()
private val groupRoomJobs = mutableStateMapOf<Long, Job>()
private val groupRoomSpeakers = mutableStateMapOf<Long, String>()
private val groupRoomSummarizing = mutableStateMapOf<Long, Boolean>()
private val groupRoomContextEstimate = mutableStateMapOf<Long, String>()
val groupRoomConfirms = mutableStateMapOf<Long, GroupConfirmRequest>()
private val groupSessionRoom = HashMap<Long, Long>()
private val groupSessionMember = HashMap<Long, String>()
    private lateinit var shellExecTool: ShellExecTool
    private lateinit var backgroundReviewRunner: BackgroundReviewRunner
    lateinit var securityGate: SecurityGateImpl
        private set
    lateinit var rootDetector: RootDetector
        private set
    lateinit var workflowState: WorkflowState
        private set
    lateinit var goalRunner: GoalRunner
        private set
    lateinit var mcpManager: McpManager
        private set
    lateinit var batteryMonitor: BatteryMonitor
        private set
    lateinit var suExecTool: SuExecTool
        private set
    lateinit var webSearchTool: WebSearchTool
        private set
    lateinit var projectsRepository: ProjectsRepository
        private set
    lateinit var identityRepository: IdentityRepository
        private set
    var currentPowerMode by mutableStateOf(PowerMode.NORMAL)
        private set
    var permissionModeState by mutableStateOf(PermissionMode.ASK)
        private set
    private lateinit var toolRegistry: ToolRegistry

    var currentModelLabel by mutableStateOf("")
        private set
    var thinkingEnabled by mutableStateOf(false)
        private set
    var thinkingLevel by mutableStateOf(2)  // 0=Low,1=Medium,2=High,3=Extra,4=Max
        private set

    /** Dark mode toggle — persisted to Room `dark_mode` setting. */
    var darkMode by mutableStateOf(false)
        private set

    /** 输入框回车行为:true=回车直接发送(换行用输入法的组合键);false=回车换行(发送靠 [→] 键)。持久化到 `enter_to_send`。 */
    var enterToSend by mutableStateOf(true)
        private set

    /** Live task cards are isolated by the AgentCore session that published them. */
    private val planStates = SessionPlanStore()
    val planState: PlanState
        get() = planStateForSession(currentSessionId)

    fun planStateForSession(sessionId: Long): PlanState = planStates.forSession(sessionId)

    /** 看板执行器:把 ready 的任务交给隔离 AgentCore 去跑。 */
    lateinit var kanbanRunner: KanbanRunner
        private set

    /** 「智能体指挥室」像素动画状态(Application 级),由 dispatch_agents 实时更新。 */
    val subAgentScene = SubAgentSceneState()

    /** 可视终端(Application 级):部署/工具安装/AI(env_exec)/用户手输命令都实时汇聚上屏。 */
    val terminalState = TerminalState()

    /** Active identity card id — new sessions lock onto this at creation. Switching only affects future sessions. */
    var activeIdentityId by mutableStateOf(IdentityRepository.DEFAULT_IDENTITY_ID)
        private set

    /** Non-null when root init failed (visible to UI). */
    @Volatile
    var rootInitError: String? = null
        private set

    /** 最近一次未捕获崩溃的摘要(供设置页/日志排查);进程重启后由 crash.log 恢复。 */
    @Volatile
    var lastCrashSummary: String? = null

    /**
     * 全局崩溃兜底。此前 App 没有任何 UncaughtExceptionHandler:
     *  - 后台线程(工具执行、脚本沙箱、网络回调)里任何漏网的 Throwable 都会**直接杀掉整个进程**,
     *    用户看到的就是"莫名闪退",且事后毫无线索。
     *
     * 策略:
     *  - 一律先把堆栈写进 filesDir/crash.log(可事后排查,不上传任何地方);
     *  - **非主线程**的异常:记录后【吞掉】,只让那条线程结束,App 继续可用
     *    (一次工具失败不该让用户丢掉整个会话);
     *  - **主线程**的异常:记录后交还系统默认处理(UI 状态已不可信,强行续命只会更糟)。
     */
    /**
     * 清理超过 7 天的附件副本。
     *
     * 图片和大文本附件都会被复制进 filesDir/attachments 只留路径,发完消息也不删——
     * 不清理的话反复传大图会一直吃满应用私有空间。不在发送后立即删,是因为历史消息里
     * 还留着路径,用户回看旧对话时可能还想让 AI 再读一次。
     */
    private fun pruneOldAttachments() {
        try {
            val dir = java.io.File(filesDir, "attachments")
            if (!dir.isDirectory) return
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
            }
        } catch (_: Exception) { /* 清理失败不影响启动 */ }
    }

    private fun installCrashGuard() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val summary = "${throwable::class.java.name}: ${throwable.message} @${thread.name}"
            try {
                lastCrashSummary = summary
                Log.e("XINCODE", "UNCAUGHT on ${thread.name}", throwable)
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                java.io.File(filesDir, "crash.log").appendText(
                    "\n=== ${java.util.Date()} thread=${thread.name} ===\n$sw\n"
                )
            } catch (_: Throwable) { /* 记录失败也不能再抛 */ }

            if (thread === mainLooper.thread) {
                default?.uncaughtException(thread, throwable)  // 主线程:交还系统,正常崩溃
            }
            // 非主线程:已记录,吞掉不杀进程
        }
    }

    /**
     * Pick a directory owned by the current app UID and verify it with a real write.
     * Shared `/storage/emulated/0/XINCODE` can survive reinstall with the previous UID's
     * ownership, which is exactly how ordinary non-root team work ended in EPERM.
     */
    private fun configureWritableWorkspace() {
        val candidates = listOfNotNull(
            getExternalFilesDir(null)?.let { java.io.File(it, "XINCODE") },
            java.io.File(filesDir, "workspace")
        )
        val chosen = candidates.firstOrNull { dir ->
            runCatching {
                if (!dir.exists() && !dir.mkdirs()) return@runCatching false
                val probe = java.io.File(dir, ".xincode-write-probe")
                probe.writeText("ok")
                probe.delete()
                true
            }.getOrDefault(false)
        } ?: java.io.File(filesDir, "workspace").also { it.mkdirs() }
        com.xincode.tools.WorkspaceContext.configureDefaultRoot(chosen.absolutePath)
    }

    /**
     * Internal group work sessions predate room-scoped memory. Repair only memories whose
     * source message points at one of those sessions; an untraceable manual note must not be
     * guessed into a room.
     */
    private fun repairGroupWorkScopes() {
        runBlocking {
            database.inTransaction {
                database.groupRoomDao().getMembersWithWorkSessions().forEach { member ->
                    val scope = GroupRoomIsolation.memoryScopeId(member.roomId)
                    database.sessionDao().setProjectId(member.workSessionId, scope)
                    database.memoryDao().rehomeBySourceSession(member.workSessionId, scope)
                }
            }
        }
    }

override fun onCreate() {
        super.onCreate()
        Log.i("XINCODE", "=== APP START ===")
        installCrashGuard()

        // 自我保护要在【建库之前】就位:tools 模块拿不到 Context,这个路径只能从这里注入。
        // 晚一步注入,这一轮里 AI 就能把 App 自己的 databases/ 改坏(真实事故,见 SelfProtect)。
        com.xincode.tools.SelfProtect.appDataDir = applicationInfo.dataDir.orEmpty()
        configureWritableWorkspace()

        // ⚠️ database 必须在【任何用到它的代码之前】赋值。
        // 它是 lateinit,提前一行访问就是 UninitializedPropertyAccessException,
        // 抛在 Application.onCreate 里 = 进程当场死 = 用户看到的「点开就闪退」,
        // 而且全新安装一样崩,没有任何幸存路径。下面新增初始化时务必守住这个顺序。
        database = AppDatabase.getInstance(this)
        repairGroupWorkScopes()
        // Initialize shared HTTP disk cache
        HttpCacheProvider.init(cacheDir)

        pruneOldAttachments()
        UsageRecorder.prune(database)
        kanbanRunner = KanbanRunner(database) { buildIsolatedAgentCore() }
        // 进程被杀时正在跑的任务会永远停在 running,重启后没人管它 —— 启动时统一收回 ready
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { database.kanbanTaskDao().reclaimStuckRunning() }
        }
        keystore = KeystoreProvider()
        openAiClient = OpenAiClient(database, keystore)
        // 按功能绑定的 client:各自去读【功能模型配置】,没配就自动回落到活跃配置。
        // 分别 new 而不是复用单例,是因为 functionKey 是构造参数(避免并发串配置)。
        reviewClient = OpenAiClient(database, keystore, functionKey = "review")
        subAgentClient = OpenAiClient(database, keystore, functionKey = "subagent")
        wolfpackClient = OpenAiClient(database, keystore, functionKey = "wolfpack")
        cronClient = OpenAiClient(database, keystore, functionKey = "cron")
        compactClient = OpenAiClient(database, keystore, functionKey = "compact")

        // Legacy ChatState (kept for fallback)
        chatState = ChatState(database, openAiClient)

        // Identity repository — must init before session-ensure so first session locks onto active identity
        identityRepository = IdentityRepository(database)
        activeIdentityId = runBlocking { identityRepository.getActiveId() }
        identityListFlow = identityRepository.observeAll()

        // Ensure a session exists
        var session = runBlocking { database.sessionDao().getAllVisible().firstOrNull() }
        if (session == null) {
            val sid = runBlocking { database.sessionDao().upsert(SessionEntity(title = "对话 1", identityId = activeIdentityId)) }
            session = runBlocking { database.sessionDao().getById(sid) }
        }
        currentSessionId = session?.id ?: 1L
        sessionListFlow = database.sessionDao().getAllFlow()
        projectsRepository = ProjectsRepository(database)
        initProjectFlows()

        // 5.0: Project & star flows — collated from Room for sidebar

        // Security gate — load permission mode from Room
        securityGate = SecurityGateImpl(database.auditLogDao())
        runBlocking {
            val modeStr = database.settingDao().get("permission_mode") ?: "ASK"
            val mode = try { PermissionMode.valueOf(modeStr) } catch (_: Exception) { PermissionMode.ASK }
            securityGate.setPermissionMode(mode)
            permissionModeState = mode
            // gap-12:加载持久化的 allow/deny 权限规则快照。
            try { securityGate.setPermissionRules(database.permissionRuleDao().getAll()) } catch (_: Exception) {}
        }

        // Root detector
        rootDetector = RootDetector()
        rootDetector.start()

        // 内置 Linux 环境(root+chroot Ubuntu):定位私有目录,若已部署则标记就绪。
        LinuxEnvironment.init(this)
        // 1.22: 终端已接管 Shizuku/ADB 分级，需 context 判定
        terminalState.attachContext(this)
        // 部署/安装的流式输出汇聚到可视终端。
        LinuxEnvironment.outputSink = { terminalState.appendChunk(it) }
        // 加载自定义环境变量(构建/终端注入)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val json = database.settingDao().get(com.xincode.app.ide.EnvVarManager.KEY) ?: ""
                val vars = com.xincode.app.ide.EnvVarManager.fromJson(json)
                LinuxEnvironment.customEnvVars = vars
            } catch (_: Exception) {}
        }

        // ---- libsu root shell (replaces PersistentRootShell) ----
        RootShellManager.init()
        GlobalScope.launch(Dispatchers.IO) {
            val ok = RootShellManager.verifyRoot()
            if (!ok) {
                rootInitError = "Root 不可用, 当前状态: ${RootShellManager.rootStatus}"
                Log.e("XincodeApp", "ROOT INIT FAILED: ${RootShellManager.rootStatus}")
            } else {
                Log.i("XincodeApp", "Root shell verified: uid=0 confirmed via libsu")
            }
        }

        // Agent Core with tool registry + security gate + cursor persistence
        toolRegistry = ToolRegistry()
        shellExecTool = ShellExecTool()
        toolRegistry.register(shellExecTool)
val suExecTool = SuExecTool().also { this.suExecTool = it }
        toolRegistry.register(suExecTool)
        toolRegistry.register(FileReadTool())
        toolRegistry.register(FileWriteTool())
        toolRegistry.register(ListDirTool())
        // grok 核心工具补齐:外科式编辑 + 代码搜索(gap-01/02/03/04)
        toolRegistry.register(EditTool())
        toolRegistry.register(MultiEditTool())
        toolRegistry.register(GrepTool())
        toolRegistry.register(GlobTool())
        // 文件管理补齐:删除和建目录以前只能靠 shell_exec 拼 rm/mkdir。
        // 做成独立工具后意图是显式的,安全门也能按工具名单独识别,不必在所有 shell 里猜。
        toolRegistry.register(DeleteFileTool())
        toolRegistry.register(MakeDirectoryTool())
        // 下载与抓网页分开:web_fetch 抽正文【进】上下文,download_file 存字节【不进】上下文。
        // 二进制走 web_fetch 既没意义又顶爆上下文。
        toolRegistry.register(DownloadFileTool())
        toolRegistry.register(SleepTool())
        // 技能用量生命周期:命中即累计;agent/user 技能每用 5 次触发一次后台自改进(bundled 只读不触发)。
        val invokeSkillTool = InvokeSkillTool(database).also { tool ->
            tool.onSkillUsed = { skill ->
                if (skill.source != "bundled") {
                    backgroundReviewRunner.onSkillImprovement(
                        skill.name,
                        skill.content,
                        skill.useCount,
                        workspaceRoot = WorkspaceContext.workspaceRoot,
                        projectId = WorkspaceContext.projectId
                    )
                }
            }
        }
        toolRegistry.register(invokeSkillTool)
        toolRegistry.register(WolfpackOrchestrator(wolfpackClient))
        toolRegistry.register(AgentPlanTool {
            val ownerSessionId = ToolSessionContext.sessionId ?: currentSessionId
            planStates.forSession(ownerSessionId)
        })
        // AI 的可视终端工具:在内置 Ubuntu 环境跑命令,输出实时镜像到终端页(未部署则不暴露)。
        toolRegistry.register(EnvExecTool(terminalState))
        toolRegistry.register(RecallMemoryTool(database, openAiClient))
        // recall_memory 给的是截断后的候选摘要;认出是哪条之后要用这个取全文,
        // 拿半截的决策记录去干活,少的那半段可能正是关键约束。
        toolRegistry.register(GetMemoryByTitleTool(database))
        // 代码结构查询:比 grep 准(基于语法树),而且不用把文件读进上下文。
        // 内核加载不了时它自己会报错让 agent 退回 grep,不影响启动。
        toolRegistry.register(CodeGraphTool(database))
        // Hermes-① 自进化学习闭环:记忆写入 + 技能管理(主对话也可用,后台复盘分身主要用)。
        toolRegistry.register(SaveMemoryTool(database))
        toolRegistry.register(SkillManageTool(database))
        // Hermes-④ execute_code 零上下文工具-RPC(脚本内调工具、仅 stdout 回模型)。
        toolRegistry.register(CodeExecTool(toolRegistry))
        // 当前时间(纯本地读设备时钟,零依赖):定时任务/"现在几点"等场景先查准时间,避免把 UTC 当本地时间。
        toolRegistry.register(CurrentTimeTool())
        // Hermes-⑦ cronjob:让模型把用户口语翻译成定时任务。
        toolRegistry.register(CronJobTool(database))
        // Hermes-⑥ 语音转写(服务门控:无端点则不暴露)。
        toolRegistry.register(TranscribeAudioTool(database, keystore))
        // 模型委托:主模型缺能力时转交副模型(均服务门控)。
        toolRegistry.register(DescribeImageTool(database, keystore))       // 视觉
        toolRegistry.register(AskReasoningTool(database, keystore))        // 深度推理
        toolRegistry.register(TranslateTool(database, keystore))           // 翻译
        // 子智能体调度:主脑把任务拆给专职子智能体并行处理(带指挥室动画状态)。
        toolRegistry.register(SubAgentTool(toolRegistry, subAgentClient, database, securityGate, subAgentScene))

        // Web tools — search + fetch
        val webSearchTool = WebSearchTool().also { this.webSearchTool = it }
        val webFetchTool = WebFetchTool()
        toolRegistry.register(webSearchTool)
        toolRegistry.register(webFetchTool)

        // Load search API key from Room
        runBlocking {
            val encKey = database.settingDao().get("search_api_key") ?: ""
            if (encKey.isNotBlank()) {
                try {
                    val decrypted = keystore.decrypt(android.util.Base64.decode(encKey, android.util.Base64.NO_WRAP))
                    webSearchTool.apiKey = decrypted
                } catch (_: Exception) {}
            }
        }

        // MCP manager — connects to MCP servers and registers their tools
        val mcpOkHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .cache(HttpCacheProvider.get())
            .build()
        mcpManager = McpManager(database, toolRegistry, mcpOkHttpClient)

        // workflowState 必须先于会话工厂构造(各 core 的状态收集器会调用它)。
        workflowState = WorkflowState()

        // Hermes-① 复盘分身运行器(共享;每个会话 core 都挂它)——必须先于会话工厂构造。
        val reviewToolRegistry = ToolRegistry().apply {
            register(SaveMemoryTool(database))
            register(SkillManageTool(database))
            register(RecallMemoryTool(database, openAiClient))
            register(InvokeSkillTool(database))
            register(FileReadTool())   // 只读:允许复盘时回看文件
        }
        backgroundReviewRunner = BackgroundReviewRunner(
            reviewCoreFactory = {
                AgentCore(
                    openAiClient = reviewClient,
                    toolRegistry = reviewToolRegistry,
                    securityGate = securityGate,
                    cursorDao = null,
                    sessionId = -2L,
                    systemPrompt = buildLayeredSystemPrompt(null)
                ).also { it.isReviewFork = true }
            }
        )

        // 构造首个会话的 agent 对(工厂完成全部布线:hooks / 复盘 / 前缀哈希 / 确认 / 状态收集 / 限额)。
        val initial = buildSessionAgents(currentSessionId)
        sessionPool[currentSessionId] = initial
        _active = initial
        // Identity: seed the initial session's layered system prompt + temperature
        // (per-turn refresh happens in AgentChatState.send(), this covers the cold-start gap).
        val initialIdentity = session?.identityId?.let { id -> runBlocking { database.identityDao().getById(id) } }
        initial.core.updateSystemPrompt(buildLayeredSystemPrompt(initialIdentity?.systemPrompt))
        initial.core.temperature = initialIdentity?.temperature ?: 1.0f

        // 步骤3: 冷启动时同步加载当前 session 历史(UI 列表 + AgentCore 内存),阻塞确保在 send() 之前完成
        runBlocking {
            val storedWorkspace = database.settingDao().get("workspace_root") ?: ""
            // v1.10 的共享目录可能归旧安装 UID 所有。空掉后自动使用当前安装自己的目录。
            workspaceRootGlobal = if (storedWorkspace.trimEnd('/') ==
                com.xincode.tools.WorkspaceContext.LEGACY_SHARED_ROOT) {
                database.settingDao().put("workspace_root", "")
                ""
            } else storedWorkspace
            auxVisionBaseUrl = database.settingDao().get("aux_vision_base_url") ?: ""
            auxVisionModel = database.settingDao().get("aux_vision_model") ?: ""
            auxVisionKeySet = !database.settingDao().get("aux_vision_api_key").isNullOrBlank()
            applyWorkspaceForSession(currentSessionId) // 冷启动设置工作区 + 项目记忆隔离
            val msgs = database.messageDao().getBySessionId(currentSessionId)
            if (msgs.isNotEmpty()) {
                initial.core.restoreHistory(msgs)
            }
            // ChatScreen 不再自行加载历史(改由 app 负责),故这里填充 UI 列表。
            initial.chat.loadHistoryForSession(currentSessionId)
            // gap-16:激活断点续传死代码——若上次工具已执行但结果未回灌即被杀,
            // 以状态游标中的 pendingToolResult 续跑循环,不重复执行该工具。
            try {
                if (initial.core.hasPendingCursor()) {
                    initial.core.resumeFromCursorIfNeeded(GlobalScope)
                }
            } catch (e: Exception) {
                Log.e("XincodeApp", "resume from cursor failed: ${e.message}")
            }
            Unit
        }

        // Goal runner (goal-driven agent loop with independent judge)
        goalRunner = GoalRunner(
            // gap-18:工厂构造独立 AgentCore(独立 sessionId=-1,复用同 provider 与工具),不污染主对话。
            agentCoreFactory = {
                AgentCore(
                    openAiClient = openAiClient,
                    toolRegistry = toolRegistry,
                    securityGate = securityGate,
                    cursorDao = null,
                    sessionId = -1L,
                    systemPrompt = buildLayeredSystemPrompt(null)
                )
            },
            database = database,
            judgeApiKey = runBlocking {
                val cfg = database.providerConfigDao().getActive()
                val enc = cfg?.apiKeyEnc ?: ""
                if (enc.isNotBlank()) keystore.decrypt(android.util.Base64.decode(enc, android.util.Base64.NO_WRAP)) else ""
            },
            judgeBaseUrl = runBlocking { database.providerConfigDao().getActive()?.baseUrl ?: "" },
            judgeModel = runBlocking { database.providerConfigDao().getActive()?.model ?: "" },
            judgeApiPathType = runBlocking { database.providerConfigDao().getActive()?.apiPathType ?: "openai" },
            judgeExtraHeadersJson = runBlocking { database.providerConfigDao().getActive()?.extraHeadersJson ?: "" }
        )

        // 首启种子:5 基础技能 + 2 默认 MCP(只种一次)。
        GlobalScope.launch(Dispatchers.IO) {
            try { DefaultsSeeder.seedIfNeeded(database) } catch (e: Exception) {
                Log.w("XincodeApp", "defaults seed failed: ${e.message}")
            }
        }

        // 内置技能包:assets/skills/**/SKILL.md 随 APK 分发,启动导入(按名查重,幂等)。
        GlobalScope.launch(Dispatchers.IO) {
            try { AssetsSkillImporter.install(this@XincodeApplication, database) } catch (e: Exception) {
                Log.w("XincodeApp", "assets skills import failed: ${e.message}")
            }
        }

        // gap-26:启动时自动发现工作区 skills/**/SKILL.md,导入 Room(进入系统提示技能清单)。
        GlobalScope.launch(Dispatchers.IO) {
            try { SkillImporter.autoDiscover(database) } catch (e: Exception) {
                Log.w("XincodeApp", "skill auto-discover failed: ${e.message}")
            }
        }

        // 技能 curator:按最近使用时间推进 active → stale → archived(默认 7 天检查一次,纯确定性,无 LLM 开销)。
        GlobalScope.launch(Dispatchers.IO) {
            try { SkillCurator.runIfDue(database) } catch (e: Exception) {
                Log.w("XincodeApp", "skill curator failed: ${e.message}")
            }
        }

        // Hermes-⑦ 定时任务:确保 WorkManager 周期 tick 在跑。
        try { CronScheduler.ensureScheduled(this) } catch (e: Exception) {
            Log.w("XincodeApp", "cron schedule failed: ${e.message}")
        }

        // 每会话状态收集器在 buildSessionAgents() 工厂内挂载(→ onCoreState),前台服务保活跨全部 core 聚合。

        // Set current model label for UI display
        runBlocking {
            val selected = resolveModelSelection(currentSessionId)
            currentModelLabel = selected.modelId
            currentModelName = selected.modelId
            currentProviderName = selected.provider?.name.orEmpty()
        }
        // Load thinking prefs from Room
        thinkingEnabled = runBlocking { database.settingDao().get("thinking_enabled")?.toBooleanStrictOrNull() ?: false }
        thinkingLevel = runBlocking { database.settingDao().get("thinking_level")?.toIntOrNull() ?: 2 }
        agentChatState.thinkingEnabled = thinkingEnabled
        agentChatState.thinkingLevel = thinkingLevel
        darkMode = runBlocking { database.settingDao().get("dark_mode")?.toBooleanStrictOrNull() ?: false }
        enterToSend = runBlocking { database.settingDao().get("enter_to_send")?.toBooleanStrictOrNull() ?: true }
        // 联网搜索总开关:默认【关闭】,不打开就不能联网获取信息。加载持久化值。
        com.xincode.tools.WebSearchGate.enabled = runBlocking { database.settingDao().get("web_search_enabled")?.toBooleanStrictOrNull() ?: false }
        // Ensure enabledModelIds has at least the active model
        runBlocking {
            val configs = database.providerConfigDao().getAll()
            configs.forEach { cfg ->
                if (cfg.enabledModelIds.isEmpty() && cfg.model.isNotBlank()) {
                    val updated = cfg.copy(enabledModelIds = listOf(cfg.model))
                    database.providerConfigDao().update(updated)
                }
            }
        }

        // Restore MCP server connections from Room
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val results = mcpManager.syncFromRoom()
                for (r in results) {
                    when (r) {
                        is McpConnectResult.Success -> Log.i("XINCODE", "MCP restored: ${r.serverName} (${r.toolCount} tools)")
                        is McpConnectResult.Error -> Log.w("XINCODE", "MCP restore failed: ${r.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w("XINCODE", "MCP syncFromRoom failed: ${e.message}")
            }
        }

        // Battery monitor — observe state and adjust power mode
        batteryMonitor = BatteryMonitor(this).apply { start() }
        // Wolfpack reference for dynamic concurrency adjustment
        val wolfpackTool = toolRegistry.get("wolfpack_run") as? WolfpackOrchestrator
        GlobalScope.launch(Dispatchers.Main) {
            batteryMonitor.powerMode.collectLatest { mode ->
                currentPowerMode = mode
                Log.i("XINCODE", "Power mode: ${mode.label}")
                // Adjust Wolfpack concurrency
                wolfpackTool?.concurrency = mode.wolfpackConcurrency
                // Adjust agent limits — 应用到池中【全部】会话 core(含后台续跑的)。
                sessionPool.values.forEach { it.core.updateLimits(mode.maxIterations, mode.totalTimeoutMs) }
                Log.i("XINCODE", "Wolfpack concurrency set to ${mode.wolfpackConcurrency} ($mode)")
            }
    }

    }

    // ---- session management ----

    /** Current active session ID — observable from Compose. */
    var currentSessionId: Long by mutableStateOf(1L)
        private set

    /** Flow of all sessions (for sidebar). */
    lateinit var sessionListFlow: Flow<List<SessionEntity>>
        private set

    /** Flow of all projects (for sidebar). */
    lateinit var projectListFlow: Flow<List<ProjectEntity>>
        private set

    /** Flow of all identity cards (for sidebar switcher + management list). */
    lateinit var identityListFlow: Flow<List<IdentityEntity>>
        private set

    /** Flow of starred sessions (for sidebar). */
    lateinit var starredSessionsFlow: Flow<List<SessionEntity>>
        private set

    /** Flow of ungrouped sessions (for sidebar). */
    lateinit var ungroupedSessionsFlow: Flow<List<SessionEntity>>
        private set

    /** Flow of Goal/Work 任务(侧栏「Goal 模式」区)。 */
    lateinit var goalSessionsFlow: Flow<List<SessionEntity>>
        private set

    // ---- Goal/Work 模式运行态 ----
    /** sessionId -> 运行中控制器。运行期间钉住不回收。 */
    private val goalControllers = HashMap<Long, GoalLoopController>()
    /** sessionId -> 状态文本(供聊天页横幅即时显示)。 */
    val goalRunStatus = androidx.compose.runtime.mutableStateMapOf<Long, String>()
    /** 正在跑的 Goal 会话 id 集合(防被后台回收/换出池)。 */
    private val activeGoalSessions = java.util.Collections.synchronizedSet(HashSet<Long>())
    fun isGoalRunning(sessionId: Long): Boolean = activeGoalSessions.contains(sessionId)

    /**
     * Hermes-⑦:给后台(cron worker 等)造一个隔离 AgentCore——全工具、独立 sessionId、无游标、
     * 不触碰主对话。isReviewFork=true 避免它自己再触发后台复盘。
     */
    fun buildIsolatedAgentCore(): AgentCore = AgentCore(
        openAiClient = cronClient,
        toolRegistry = toolRegistry,
        securityGate = securityGate,
        cursorDao = null,
        sessionId = -3L,
        systemPrompt = buildLayeredSystemPrompt(null)
    ).also { it.isReviewFork = true }

    // ---- per-session agent factory + lifecycle ----

    /**
     * 为 [sessionId] 构造一对全新的 AgentCore + AgentChatState 并完成【全部布线】
     * (hooks / 前缀哈希 / 后台复盘 / 确认处理 / 状态收集 / 功耗限额)。
     * 关键:core 与 chat 的 sessionId 在此固定,运行循环只往这个 id 读写 —— 因此即便切走后
     * 仍在后台跑,也【不会污染】别的会话,也不需要被 stop() 打断(从而不会造成 HTTP 400)。
     */
    private fun buildSessionAgents(sessionId: Long): SessionAgents {
        planStates.forSession(sessionId)
        // 每个会话一个带会话级模型覆盖的 client:切换对话供应商/模型不影响其他会话,
        // 也不会和并发会话互相串配置。
        val sessionClient = OpenAiClient(database, keystore, sessionIdOverride = sessionId)
        val core = AgentCore(
            openAiClient = sessionClient,
            toolRegistry = toolRegistry,
            securityGate = securityGate,
            cursorDao = database.stateCursorDao(),
            sessionId = sessionId,
            systemPrompt = BASE_SYSTEM_PROMPT
        )
        core.hookDispatcher = HookDispatcherImpl(database, shellExecTool, suExecTool)
        core.onBackgroundReview = { m, s, tail ->
            backgroundReviewRunner.onReview(
                m,
                s,
                tail,
                workspaceRoot = WorkspaceContext.workspaceRoot,
                projectId = WorkspaceContext.projectId
            )
        }
        // 用量记录:每次模型调用都落一笔(不是每回合),否则工具回环的调用全被漏掉。
        core.onUsageRecorded = { usage, sid ->
            // Usage belongs to the session that made the request. Resolving the label from that
            // row avoids a background session inheriting whatever the foreground UI displays.
            applicationScope.launch(Dispatchers.IO) {
                val selected = resolveModelSelection(sid)
                UsageRecorder.record(
                    database,
                    usage,
                    sid,
                    model = selected.modelId,
                    provider = selected.provider?.name.orEmpty(),
                    source = "chat"
                )
            }
        }
        // P2: Prefix hash drift detection — persist computed hash and warn on drift
        core.onPrefixHashComputed = { hash, sid ->
            GlobalScope.launch(Dispatchers.IO) {
                val sess = database.sessionDao().getById(sid) ?: return@launch
                val stored = sess.prefixHash
                if (stored == null) {
                    database.sessionDao().upsert(sess.copy(prefixHash = hash))
                    Log.i("CacheStability", "Prefix hash stored for session $sid: ${hash.take(16)}...")
                } else if (stored != hash) {
                    Log.w("CacheStability", "Prefix hash drift detected: " +
                            "session=$sid old=${stored.take(16)}... new=${hash.take(16)}...")
                    database.sessionDao().upsert(sess.copy(prefixHash = hash))
                }
            }
        }
        core.updateLimits(currentPowerMode.maxIterations, currentPowerMode.totalTimeoutMs)
        val chat = AgentChatState(database, core, sessionClient, compactClient)
        chat.currentSessionId = sessionId
        chat.thinkingEnabled = thinkingEnabled
        chat.thinkingLevel = thinkingLevel
        core.setConfirmHandler(chat.confirmHandler)
        val holder = SessionAgents(sessionId, core, chat)
        // 各会话独立状态收集器:驱动前台服务保活(跨全部 core 聚合)+ 轨迹保存 + 后台完成即回收。
        holder.collector = GlobalScope.launch(Dispatchers.Main) {
            core.state.collect { state -> onCoreState(holder, state) }
        }
        return holder
    }

    /** 单个会话 core 的状态变化处理(主线程)。 */
    private fun onCoreState(holder: SessionAgents, state: com.xincode.core.AgentState) {
        val sid = holder.sessionId
        coreBusy[sid] = state.isBusy
        // 仅【当前前台会话】驱动 workflow 观测面板与轨迹归档,避免后台会话事件串台。
        if (sid == currentSessionId) {
            workflowState.onStateChange(state)
            if (state is com.xincode.core.AgentState.Idle && workflowState.toJson() != "[]") {
                val snapshot = workflowState.toJson()
                GlobalScope.launch(Dispatchers.IO) {
                    database.trajectoryDao().upsert(
                        com.xincode.data.TrajectoryEntity(sessionId = sid, eventsJson = snapshot)
                    )
                }
                workflowState.clear()
            }
        }
        // 群聊成员工作会话进入等待确认时,把确认卡推到房间;离开该状态就撤下。
        val confirmRoom = groupSessionRoom[sid]
        if (confirmRoom != null) {
            if (state is com.xincode.core.AgentState.WaitingConfirm) {
                groupRoomConfirms[confirmRoom] = GroupConfirmRequest(
                    sessionId = sid,
                    memberName = groupSessionMember[sid] ?: "成员",
                    toolName = state.toolName,
                    preview = state.preview
                )
            } else {
                val cur = groupRoomConfirms[confirmRoom]
                if (cur?.sessionId == sid) groupRoomConfirms.remove(confirmRoom)
            }
        } else if (state is com.xincode.core.AgentState.WaitingConfirm) {
            // 冷启动后内存映射丢失:按 workSessionId 反查成员并补登记。
            GlobalScope.launch(Dispatchers.IO) {
                val member = database.groupRoomDao().getMemberByWorkSession(sid)
                if (member != null) {
                    groupSessionRoom[sid] = member.roomId
                    groupSessionMember[sid] = member.displayName
                    groupRoomConfirms[member.roomId] = GroupConfirmRequest(
                        sessionId = sid,
                        memberName = member.displayName,
                        toolName = state.toolName,
                        preview = state.preview
                    )
                }
            }
        }
        refreshForegroundService()
        // 后台会话(非当前)跑完即从池中回收:再回到它时会从 Room 重新加载最新历史。
        // 例外:运行中的 Goal 任务【钉住】不回收(轮次之间会短暂空闲,控制器还要驱动它继续)。
        if (!state.isBusy && sid != currentSessionId && !activeGoalSessions.contains(sid)) {
            sessionPool.remove(sid)?.collector?.cancel()
            coreBusy.remove(sid)
        }
    }

    /** 前台通知/桌面小组件:只要【任一】会话在忙就保活(否则 Android 可能杀掉后台续跑)。 */
    private fun refreshForegroundService() {
        val activeText = _active?.core?.state?.value?.let { statusTextFor(it) }
        val anyBusy = coreBusy.values.any { it }
        val anyGroupBusy = groupRoomJobs.values.any { it.isActive }
        val text = activeText ?: when {
            anyBusy -> "后台会话执行中…"
            anyGroupBusy -> "团队群聊执行中…"
            else -> null
        }
        if (text != null) {
            com.xincode.service.AgentForegroundService.start(this, text)
        } else {
            com.xincode.service.AgentForegroundService.stop(this)
        }
        com.xincode.app.XincodeWidgetReceiver.updateAll(this, text ?: "空闲")
    }

    private fun statusTextFor(state: com.xincode.core.AgentState): String? = when (state) {
        is com.xincode.core.AgentState.Idle -> null
        is com.xincode.core.AgentState.Thinking -> "思考中… (第${state.iteration}轮)"
        is com.xincode.core.AgentState.CallingTool -> "调用工具: ${state.toolName}"
        is com.xincode.core.AgentState.Executing -> "执行: ${state.toolName}"
        is com.xincode.core.AgentState.WaitingConfirm -> "等待确认: ${state.toolName}"
        is com.xincode.core.AgentState.Responding -> "完成"
        is com.xincode.core.AgentState.Error -> "✗ ${state.message}"
        is com.xincode.core.AgentState.Interrupted -> "已中断"
    }

    // Initialize flows in onCreate
    private fun initProjectFlows() {
        projectListFlow = database.projectDao().observeAll()
        starredSessionsFlow = database.sessionDao().observeStarred()
        ungroupedSessionsFlow = database.sessionDao().observeUngrouped()
        goalSessionsFlow = database.sessionDao().observeGoals()
    }

    // ---- session management ----

    fun createNewSession(): Long {
        return runBlocking {
            database.sessionDao().upsert(SessionEntity(title = "新对话", identityId = activeIdentityId))
        }
    }

    fun createSessionInProject(projectId: Long): Long {
        return runBlocking {
            database.sessionDao().upsert(
                SessionEntity(title = "新对话", projectId = projectId, identityId = activeIdentityId)
            )
        }
    }

    // ---- Goal/Work 模式 ----

    /** 新建一个 Goal 任务会话(独立对话,不进普通列表)。返回其 sessionId。 */
    fun createGoalSession(): Long = runBlocking {
        database.sessionDao().upsert(
            SessionEntity(title = "新目标", isGoal = true, goalStatus = "", identityId = activeIdentityId)
        )
    }

    /** 启动/接管某个 Goal 会话的目标循环。goal 作为首条消息,智能体自主推进,裁判验收到达成为止。 */
    fun startGoalForSession(sessionId: Long, goal: String) {
        val g = goal.trim()
        if (g.isEmpty()) return
        if (goalControllers[sessionId]?.isRunning == true) return
        activeGoalSessions.add(sessionId)
        goalRunStatus[sessionId] = "启动中…"
        // 标题用目标摘要,便于侧栏辨识。
        GlobalScope.launch(Dispatchers.IO) {
            try { database.sessionDao().rename(sessionId, g.take(24)) } catch (_: Exception) {}
            try { database.sessionDao().setGoalStatus(sessionId, "running") } catch (_: Exception) {}
        }
        // 确保该会话的 (core+chat) 在池中(控制器驱动的正是这一份,UI 打开时也绑它)。
        val agents = sessionPool[sessionId] ?: buildSessionAgents(sessionId).also { sessionPool[sessionId] = it }
        val controller = GoalLoopController(
            sessionId = sessionId,
            chat = agents.chat,
            database = database,
            judgeFactory = { buildGoalJudge() },
            appScope = GlobalScope,
            onStatus = { sid, text -> goalRunStatus[sid] = text },
            onDone = { sid, achieved, summary ->
                activeGoalSessions.remove(sid)
                goalControllers.remove(sid)
                notifyGoalDone(sid, achieved, summary)
                refreshForegroundService()
            }
        )
        goalControllers[sessionId] = controller
        controller.start(g)
        refreshForegroundService()
    }

    /** Whether a room currently owns a background reply chain. Compose reads this state map. */
    fun isGroupRoomBusy(roomId: Long): Boolean = groupRoomJobs[roomId]?.isActive == true

    /** Name of the member currently producing a reply, or an empty string between replies. */
    fun groupRoomSpeaker(roomId: Long): String = groupRoomSpeakers[roomId].orEmpty()

    /** 房间是否正在做滚动总结。 */
    fun groupRoomSummarizing(roomId: Long): Boolean = groupRoomSummarizing[roomId] == true

    /** 回复前的上下文估算提示(例如「架构师 · 上下文 ≈ 3200 tokens」)。 */
    fun groupRoomContextEstimateText(roomId: Long): String = groupRoomContextEstimate[roomId].orEmpty()

    /** 房间当前等待批准的确认卡;没有则为 null。 */
    fun groupRoomConfirm(roomId: Long): GroupConfirmRequest? = groupRoomConfirms[roomId]

    /** 从群聊房间的确认卡批准/拒绝某个成员工作会话里的工具。 */
    fun resolveGroupConfirm(sessionId: Long, result: ToolConfirmResult) {
        val chat = sessionPool[sessionId]?.chat
        when (result) {
            ToolConfirmResult.ALLOW_ONCE -> chat?.approveOnceConfirmation()
            ToolConfirmResult.ALWAYS_ALLOW -> chat?.approveAlwaysConfirmation()
            ToolConfirmResult.DENY -> chat?.denyConfirmation()
        }
        val rid = groupSessionRoom[sessionId]
        if (rid != null && groupRoomConfirms[rid]?.sessionId == sessionId) {
            groupRoomConfirms.remove(rid)
        }
    }

    /**
     * Start a room turn in the Application scope so leaving the room does not cancel it.
     * If the room is already running, the new message interrupts the old chain first
     * (its streaming rows are marked interrupted), then starts the new chain.
     *
     * @param replyToId 用户消息引用的原消息;成员回复时会自动带上这条引用。
     */
    fun sendGroupMessage(
        roomId: Long,
        rawText: String,
        replyToId: Long = 0,
        replyToSender: String = "",
        replyToContent: String = ""
    ): Boolean {
        val text = rawText.trim()
        if (text.isBlank()) return false

        // 新消息打断旧回复:取消旧链,把还在流式的消息标记为已中断,再开新链。
        val old = groupRoomJobs[roomId]
        if (old?.isActive == true) {
            old.cancel()
            applicationScope.launch(Dispatchers.IO) {
                database.groupRoomDao().markStreamingInterrupted(roomId)
            }
            groupRoomSpeakers.remove(roomId)
            groupRoomContextEstimate.remove(roomId)
        }

        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            val seedMessageId = withContext(Dispatchers.IO) {
                database.groupRoomDao().insertMessage(
                    com.xincode.data.GroupMessageEntity(
                        roomId = roomId, sender = "", content = text,
                        replyToId = replyToId, replyToSender = replyToSender, replyToContent = replyToContent
                    )
                )
            }
            GroupRoomEngine.onMessage(
                database = database,
                keystore = keystore,
                roomId = roomId,
                content = text,
                senderName = "",
                seedMessageId = seedMessageId,
                runWorkTurn = { sid, prompt, workspace ->
                    runGroupWorkTurn(sid, prompt, workspaceRoot = workspace)
                },
                ensureWorkSession = { member -> ensureGroupWorkSession(roomId, member) },
                onSpeaking = { name ->
                    applicationScope.launch { groupRoomSpeakers[roomId] = name }
                },
                onSummaryStatus = { active ->
                    applicationScope.launch { groupRoomSummarizing[roomId] = active }
                },
                onContextStatus = { name, estimate ->
                    applicationScope.launch {
                        groupRoomContextEstimate[roomId] = "$name · 上下文 ≈ ${estimate} tokens"
                    }
                }
            )
        }
        groupRoomJobs[roomId] = job
        job.invokeOnCompletion {
            applicationScope.launch {
                if (groupRoomJobs[roomId] === job) groupRoomJobs.remove(roomId)
                groupRoomSpeakers.remove(roomId)
                groupRoomSummarizing.remove(roomId)
                groupRoomContextEstimate.remove(roomId)
                groupRoomConfirms.remove(roomId)
                refreshForegroundService()
            }
        }
        job.start()
        refreshForegroundService()
        return true
    }

    /** Stop only this room's chain; other rooms and normal chats continue. */
    fun stopGroupMessage(roomId: Long) {
        groupRoomJobs[roomId]?.cancel()
        applicationScope.launch(Dispatchers.IO) {
            database.groupRoomDao().markStreamingInterrupted(roomId)
        }
        groupRoomSpeakers.remove(roomId)
        groupRoomSummarizing.remove(roomId)
        groupRoomContextEstimate.remove(roomId)
    }

    private suspend fun ensureGroupWorkSession(
        roomId: Long,
        member: com.xincode.data.GroupMemberEntity
    ): Long = withContext(Dispatchers.IO) {
        val memoryScope = GroupRoomIsolation.memoryScopeId(roomId)
        val providerId = member.providerConfigId.takeIf { it > 0L }
        val modelId = member.model.trim().ifBlank { null }
        val roomName = database.groupRoomDao().getRoom(roomId)?.name ?: "群聊"

        if (member.workSessionId > 0L) {
            val existing = database.sessionDao().getById(member.workSessionId)
            if (existing != null) {
                // The member picker is authoritative. Re-bind an existing work session on
                // every room turn so changing provider/model is not a one-time-only setting.
                database.inTransaction {
                    database.sessionDao().upsert(
                        existing.copy(
                            modelProviderConfigId = providerId,
                            currentModelId = modelId,
                            projectId = memoryScope,
                            identityId = member.identityId.takeIf { it > 0L }
                        )
                    )
                    database.memoryDao().rehomeBySourceSession(member.workSessionId, memoryScope)
                }
                groupSessionRoom[member.workSessionId] = roomId
                groupSessionMember[member.workSessionId] = member.displayName
                return@withContext member.workSessionId
            }
        }

        database.inTransaction {
            val sid = database.sessionDao().upsert(
                SessionEntity(
                    title = "$roomName · ${member.displayName}",
                    modelProviderConfigId = providerId,
                    currentModelId = modelId,
                    projectId = memoryScope,
                    identityId = member.identityId.takeIf { it > 0L }
                )
            )
            database.groupRoomDao().updateMember(member.copy(workSessionId = sid))
            groupSessionRoom[sid] = roomId
            groupSessionMember[sid] = member.displayName
            sid
        }
    }

    /**
     * 让群聊成员在【它自己的工作会话】里跑一轮,返回它最后说的那段话。
     *
     * 为什么要有独立会话:群聊里只该出现结论。成员真干活时的工具调用、试错、
     * 中间稿子如果全刷在群里,人根本读不下去(工程师贴一张工时表就把讨论淹了)。
     * 放进自己的会话后,群里是干净的汇报,想看过程点进去看 —— 而且因为走的是
     * 正常会话通道,消息会流式落库,你切过去看到的是实时进度而不是事后日志。
     *
     * 驱动方式抄的 Goal 模式那套(见 GoalLoopController.runOneTurn),已经在
     * 后台长跑场景验证过:等空闲 → 塞输入 → send → 等流式起来 → 等流式结束。
     *
     * @return 这一轮的最终发言;跑不出东西时返回空串。
     */
    suspend fun runGroupWorkTurn(
        sessionId: Long,
        prompt: String,
        timeoutMs: Long = 10 * 60 * 1000L,
        /**
         * 这个成员干活的目录。**必须真的切过去**,不能只写进提示词 ——
         * 光在 prompt 里说「工作区是 rooms/xxx」而不切 [WorkspaceContext],
         * 成员写相对路径时仍会落到全局工作区根,文件写不进去,而错误信息
         * 只是「exit -1」,模型只能自己瞎猜(实测它会退到 shell 里 chmod + cat > 硬绕)。
         */
        workspaceRoot: String? = null
    ): String {
        val agents = withContext(Dispatchers.Main) {
            sessionPool[sessionId] ?: buildSessionAgents(sessionId).also { sessionPool[sessionId] = it }
        }
        val chat = agents.chat
        val memberScope = withContext(Dispatchers.IO) {
            database.groupRoomDao().getMemberByWorkSession(sessionId)
                ?.roomId
                ?.let { GroupRoomIsolation.memoryScopeId(it) }
        }
        val storedScope = withContext(Dispatchers.IO) {
            database.sessionDao().getById(sessionId)?.projectId
        }
        // 绑到这条会话自己的作用域上(WorkspaceThreadElement 会在它的协程里生效),
        // 不影响主对话和别的会话。
        workspaceRoot?.let { chat.sessionWorkspaceRoot = it }
        chat.sessionProjectId = storedScope ?: memberScope ?: 0L

        // 跑的过程中钉住,别让 onCoreState 里的「空闲即回收」把它从池里摘走 ——
        // 摘走之后用户点进去看到的会是另一份实例,历史对不上。
        activeGoalSessions.add(sessionId)
        try {
            // isStreaming is a presentation state and can briefly become false between an
            // assistant segment and a tool call. The Job is the authoritative whole-turn
            // lifecycle, so never use the UI state to decide which message is the final reply.
            val previousTurn = withContext(Dispatchers.Main) { chat.activeTurnJob() }
            if (previousTurn?.isActive == true) {
                val becameReady = withTimeoutOrNull(15_000L) {
                    previousTurn.join()
                    true
                } == true
                if (!becameReady) return ""
            }

            val (messageStartIndex, turnJob) = withContext(Dispatchers.Main) {
                val startIndex = chat.messages.size
                chat.input.value = prompt
                startIndex to chat.sendTracked()
            }
            if (turnJob == null) return ""

            try {
                val completed = withTimeoutOrNull(timeoutMs) {
                    turnJob.join()
                    true
                } == true
                if (!completed || turnJob.isCancelled) return ""

                return withContext(Dispatchers.Main) {
                    finalAssistantContentSince(chat.messages, messageStartIndex)
                }
            } finally {
                // Stopping a room or timing out must stop the member turn as well; otherwise
                // it can keep writing to the workbench after the group chain has ended.
                if (!turnJob.isCompleted) {
                    withContext(NonCancellable + Dispatchers.Main) {
                        chat.cancelTurn(turnJob)
                    }
                }
            }
        } finally {
            activeGoalSessions.remove(sessionId)
            refreshForegroundService()
        }
    }

    /** 停止某个 Goal 任务。 */
    fun stopGoal(sessionId: Long) {
        goalControllers[sessionId]?.stop()
        goalControllers.remove(sessionId)
        activeGoalSessions.remove(sessionId)
        goalRunStatus[sessionId] = "已停止"
        refreshForegroundService()
    }

    /**
     * 构造独立裁判。优先用【功能模型配置】里给 judge 指定的那套,没指定才用活跃配置。
     * 一轮评估要投 3 票,单配便宜模型省下来的量很可观。
     */
    private fun buildGoalJudge(): com.xincode.provider.JudgeService = runBlocking {
        val dao = database.providerConfigDao()
        val assignedId = database.settingDao().get("fn_judge_config_id")?.toLongOrNull() ?: 0L
        val modelOverride = database.settingDao().get("fn_judge_model")?.trim().orEmpty()
        // 指定的配置被删掉时回落到活跃配置,别让 Goal 模式跟着一起挂
        val cfg = (if (assignedId > 0) dao.getById(assignedId) else null) ?: dao.getActive()
        val key = cfg?.apiKeyEnc?.takeIf { it.isNotBlank() }
            ?.let { keystore.decrypt(android.util.Base64.decode(it, android.util.Base64.NO_WRAP)) } ?: ""
        com.xincode.provider.JudgeService(
            baseUrl = cfg?.baseUrl ?: "",
            apiKey = key,
            model = modelOverride.ifBlank { cfg?.model ?: "" },
            apiPathType = cfg?.apiPathType ?: "openai",
            extraHeadersJson = cfg?.extraHeadersJson ?: ""
        )
    }

    /** 目标完成/失败发系统通知(App 在后台也能看到)。 */
    private fun notifyGoalDone(sessionId: Long, achieved: Boolean, summary: String) {
        try {
            val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "xincode_goal"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(channelId, "Goal 任务", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val title = runBlocking { database.sessionDao().getById(sessionId)?.title } ?: "目标任务"
            val head = if (achieved) "✓ 目标已达成" else "✗ 目标未达成"
            val n = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("$head:$title")
                .setContentText(summary)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(summary))
                .setAutoCancel(true)
                .build()
            nm.notify(("goal$sessionId").hashCode(), n)
        } catch (_: Exception) {}
    }

    fun switchToSession(id: Long) {
        if (id == currentSessionId) return
        // 关键修复:【不再】stop() 旧会话。旧会话的那一对 (core+chat) 留在 sessionPool 里【继续后台跑】,
        // 它只往自己固定的 sessionId 读写,不会污染新会话,也不会因中途 cancel 产生断裂消息历史(→ 无 HTTP 400)。
        // UI 只是把绑定切到目标会话的那一对(池中已有则复用,没有则新建),各自独立、互不影响。
        // 切走前:若旧会话【已空闲】(没在跑),从池中回收,避免空闲实例堆积;再回来时会从 Room 重载最新历史。
        // 若旧会话【仍在忙】,则保留在池中让它后台继续跑到底。
        val outgoing = sessionPool[currentSessionId]
        // Goal 任务运行期间【钉住】不回收,保证控制器一直驱动池中那一份实例(后台续跑)。
        if (outgoing != null && !outgoing.core.state.value.isBusy && !activeGoalSessions.contains(currentSessionId)) {
            sessionPool.remove(currentSessionId)?.collector?.cancel()
            coreBusy.remove(currentSessionId)
        }

        currentSessionId = id
        applySessionModelLabel(id)
        val existing = sessionPool[id]
        if (existing != null) {
            // 复用池中实例(可能仍在后台跑):直接换绑,绝不 clearHistory/reload,以免打断正在跑的循环。
            _active = existing
            GlobalScope.launch(Dispatchers.IO) { applyWorkspaceForSession(id) }
            refreshForegroundService()
            return
        }
        // 新建目标会话的一对:立即换绑(UI 即刻切过去,历史随后异步填充),再加载历史(UI 列表 + AgentCore 内存)。
        val fresh = buildSessionAgents(id)
        sessionPool[id] = fresh
        _active = fresh
        refreshForegroundService()
        GlobalScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) { applyWorkspaceForSession(id) } // 工作区 + 项目记忆隔离
            val history = withContext(Dispatchers.IO) { database.messageDao().getBySessionId(id) }
            fresh.core.restoreHistory(history)
            fresh.chat.loadHistoryForSession(id)
        }
    }

    /** 全局工作区根(设置页可改)。存 setting `workspace_root`,并立即对当前会话重算生效根。 */
    var workspaceRootGlobal by mutableStateOf("")
        private set
    fun updateWorkspaceRoot(path: String) {
        val p = path.trim()
        workspaceRootGlobal = p
        GlobalScope.launch(Dispatchers.IO) {
            database.settingDao().put("workspace_root", p)
            applyWorkspaceForSession(currentSessionId)
        }
    }

    // 多模态委托:视觉副模型配置(设置页可改)。key 经 keystore 加密存储。
    var auxVisionBaseUrl by mutableStateOf("")
        private set
    var auxVisionModel by mutableStateOf("")
        private set
    var auxVisionKeySet by mutableStateOf(false)
        private set
    fun updateAuxVision(baseUrl: String, apiKey: String, model: String) {
        auxVisionBaseUrl = baseUrl.trim()
        auxVisionModel = model.trim()
        GlobalScope.launch(Dispatchers.IO) {
            database.settingDao().put("aux_vision_base_url", baseUrl.trim())
            database.settingDao().put("aux_vision_model", model.trim())
            if (apiKey.isNotBlank()) {
                val enc = android.util.Base64.encodeToString(keystore.encrypt(apiKey), android.util.Base64.NO_WRAP)
                database.settingDao().put("aux_vision_api_key", enc)
                auxVisionKeySet = true
            }
        }
    }

    /**
     * 依据会话所属项目,设置运行期 [com.xincode.tools.WorkspaceContext]:
     *  - projectId → 记忆按项目隔离(recall/save/精编记忆)。
     *  - workspaceRoot → 项目级工作区(空则用全局设置 workspace_root,再空则默认)。
     */
    suspend fun applyWorkspaceForSession(sessionId: Long) {
        try {
            val session = database.sessionDao().getById(sessionId)
            // Group work sessions use a negative room scope and do not have a ProjectEntity.
            // Resolve their room directory here too, so opening the embedded workbench or
            // switching to a work session never temporarily binds it to the global workspace.
            val groupMember = database.groupRoomDao().getMemberByWorkSession(sessionId)
            val groupRoom = groupMember?.let { database.groupRoomDao().getRoom(it.roomId) }
            val pid = session?.projectId
                ?: groupMember?.roomId?.let { GroupRoomIsolation.memoryScopeId(it) }
                ?: 0L
            val projectRoot = if (pid > 0L) database.projectDao().getById(pid)?.workspaceRoot?.takeIf { it.isNotBlank() } else null
            val globalRoot = database.settingDao().get("workspace_root")?.takeIf { it.isNotBlank() }
            val groupRoot = groupRoom?.let { GroupRoomEngine.workspaceOf(it) }
            val configuredRoot = groupRoot ?: projectRoot ?: globalRoot
            val root = if (configuredRoot?.trimEnd('/') ==
                com.xincode.tools.WorkspaceContext.LEGACY_SHARED_ROOT) {
                com.xincode.tools.WorkspaceContext.defaultRoot
            } else configuredRoot ?: com.xincode.tools.WorkspaceContext.defaultRoot
            // 全局兜底(设置显示/无 per-session 上下文的后台核用)。
            com.xincode.tools.WorkspaceContext.projectId = pid
            com.xincode.tools.WorkspaceContext.workspaceRoot = root
            // B2:把本会话的工作区/项目绑定到它自己的 AgentChatState(其 scope 的 WorkspaceThreadElement
            // 会据此在自己作用域内隔离),这样即使切走后台续跑,也只写自己的项目目录/记忆。
            sessionPool[sessionId]?.chat?.let {
                it.sessionWorkspaceRoot = root
                it.sessionProjectId = pid
            }
        } catch (e: Exception) {
            Log.w("XincodeApp", "applyWorkspaceForSession failed: ${e.message}")
        }
    }

    fun renameSession(id: Long, title: String) {
        GlobalScope.launch(Dispatchers.IO) {
            database.sessionDao().updateTitle(id, title)
        }
    }

    fun deleteSession(id: Long) {
        GlobalScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                database.messageDao().deleteBySessionId(id)
                database.sessionDao().getById(id)?.let { database.sessionDao().delete(it) }
            }
            // 从池中回收被删会话的实例(取消其后台循环与收集器)。
            sessionPool.remove(id)?.let { it.core.stop(); it.collector?.cancel() }
            coreBusy.remove(id)
            planStates.remove(id)
            // 若删的是当前会话,切到另一个(或新建),并正常换绑加载。
            if (id == currentSessionId) {
                val targetId = withContext(Dispatchers.IO) {
                    val remaining = database.sessionDao().getAllVisible()
                    remaining.firstOrNull { it.id != id }?.id
                        ?: database.sessionDao().upsert(SessionEntity(title = "新对话", identityId = activeIdentityId))
                }
                // switchToSession 需要 currentSessionId 与目标不同才会执行:此处仍等于被删 id,满足。
                switchToSession(targetId)
            }
        }
    }

    // ---- project management ----

    fun createProject(name: String) {
        GlobalScope.launch(Dispatchers.IO) {
            projectsRepository.createProject(name)
        }
    }

    fun renameProject(id: Long, name: String) {
        GlobalScope.launch(Dispatchers.IO) {
            projectsRepository.renameProject(id, name)
        }
    }

    /** 联网搜索总开关(输入框「+」卡片切换):即时生效(清工具可用性缓存)+ 持久化。关=不能联网获取信息。 */
    fun setWebSearchEnabled(enabled: Boolean) {
        com.xincode.tools.WebSearchGate.enabled = enabled
        toolRegistry.invalidateAvailability()
        GlobalScope.launch(Dispatchers.IO) { database.settingDao().put("web_search_enabled", enabled.toString()) }
    }

    /** 协作模式(输入框模式卡片):开=强化主脑+子智能体协作。切换后强制各会话重建系统提示即时生效。 */
    var collabModeEnabled: Boolean by mutableStateOf(false)
        private set
    private val COLLAB_BRAIN_TOOLS = setOf("dispatch_agents", "agent_plan", "recall_memory", "save_memory")
    fun setCollabMode(enabled: Boolean) {
        collabModeEnabled = enabled
        com.xincode.tools.CollabGate.enabled = enabled
        if (!enabled) {
            toolRegistry.collabAllowlist = emptySet()   // 还原:主脑重新拿回全部工具
            sessionPool.values.forEach { it.chat.invalidateSystemPrompt() }
        } else {
            // 硬性收窄:开启协作后,主脑【只】看得见编排类工具(dispatch_agents 等),看不见 web_search/文件/shell
            // 等「动手」工具——弱模型再也没法绕过派活自己搜。但仅在【确实配置了子智能体】时才收窄,否则主脑
            // 无手可用会卡死,此时退回软提示。
            GlobalScope.launch(Dispatchers.IO) {
                val hasSub = try { database.subAgentDao().getAll().isNotEmpty() } catch (_: Exception) { false }
                toolRegistry.collabAllowlist = if (hasSub) COLLAB_BRAIN_TOOLS else emptySet()
                withContext(Dispatchers.Main) { sessionPool.values.forEach { it.chat.invalidateSystemPrompt() } }
            }
        }
    }

    /** 把某文件夹设为【当前对话】的工作目录(输入框 + 卡片里选文件夹)。只影响当前会话。 */
    fun setConversationWorkspace(path: String) {
        val p = path.trim().ifBlank { return }
        _active?.chat?.let { it.sessionWorkspaceRoot = p.trimEnd('/') }
        com.xincode.tools.WorkspaceContext.workspaceRoot = p
    }

    /** 设置项目独立工作目录(AI 产出/写入的默认根;仍可读目录之外)。立即对当前会话重算生效根。 */
    fun updateProjectWorkspace(projectId: Long, path: String) {
        GlobalScope.launch(Dispatchers.IO) {
            projectsRepository.setProjectWorkspaceRoot(projectId, path)
            // 若当前会话正属于该项目,立刻切换其运行期工作区。
            applyWorkspaceForSession(currentSessionId)
        }
    }

    fun deleteProject(id: Long) {
        GlobalScope.launch(Dispatchers.IO) {
            projectsRepository.deleteProject(id)
        }
    }

    fun toggleProjectExpanded(id: Long) {
        GlobalScope.launch(Dispatchers.IO) {
            projectsRepository.toggleExpanded(id)
        }
    }

    fun moveSessionToProject(sessionId: Long, projectId: Long?) {
        GlobalScope.launch(Dispatchers.IO) {
            projectsRepository.moveSessionToProject(sessionId, projectId)
        }
    }

    // ---- identity management ----

    /** Switch the active identity. Only affects sessions created after this call. */
    fun setActiveIdentity(id: Long) {
        applicationScope.launch {
            val accepted = withContext(Dispatchers.IO) {
                runCatching { identityRepository.setActive(id) }.isSuccess
            }
            if (accepted) activeIdentityId = id
        }
    }

    fun createIdentity(r: IdentityEditResult) {
        GlobalScope.launch(Dispatchers.IO) {
            val id = identityRepository.create(r.name, r.systemPrompt, r.temperature)
            // repository.create 只认三个基础字段,扩展字段建完再补一次更新,
            // 免得为了几个可选字段改动 repository 的既有签名。
            if (r.description.isNotBlank() || r.openingStatement.isNotBlank() ||
                r.marks.isNotBlank() || r.allowedTools.isNotBlank()) {
                database.identityDao().getById(id)?.let { created ->
                    identityRepository.update(created.copy(
                        description = r.description, openingStatement = r.openingStatement,
                        marks = r.marks, allowedTools = r.allowedTools
                    ))
                }
            }
        }
    }

    fun updateIdentity(identity: IdentityEntity) {
        GlobalScope.launch(Dispatchers.IO) {
            identityRepository.update(identity)
        }
    }

    fun renameIdentity(id: Long, name: String) {
        GlobalScope.launch(Dispatchers.IO) {
            identityRepository.rename(id, name)
        }
    }

    fun setIdentityStarred(id: Long, starred: Boolean) {
        GlobalScope.launch(Dispatchers.IO) {
            identityRepository.setStarred(id, starred)
        }
    }

    fun deleteIdentity(id: Long) {
        GlobalScope.launch(Dispatchers.IO) {
            identityRepository.delete(id)
            if (activeIdentityId == id) {
                activeIdentityId = identityRepository.getActiveId()
            }
        }
    }

    fun setSessionStarred(sessionId: Long, starred: Boolean) {
        GlobalScope.launch(Dispatchers.IO) {
            projectsRepository.setSessionStarred(sessionId, starred)
        }
    }

    private suspend fun resolveModelSelection(sessionId: Long): EffectiveModelSelection {
        val session = database.sessionDao().getById(sessionId)
            ?: SessionEntity(id = sessionId)
        val configs = database.providerConfigDao().getAll()
        val active = configs.firstOrNull { it.isActive }
        return ModelSelection.resolve(session, configs, active)
    }

    /** Refresh the process labels after a provider config was edited, added, removed or activated. */
    fun refreshProviderRuntime() {
        applicationScope.launch(Dispatchers.IO) {
            val selected = resolveModelSelection(currentSessionId)
            currentModelName = selected.modelId
            currentProviderName = selected.provider?.name.orEmpty()
            withContext(Dispatchers.Main) {
                currentModelLabel = selected.modelId
            }
        }
    }

    fun switchModel(modelId: String) {
        applicationScope.launch(Dispatchers.IO) {
            val configs = database.providerConfigDao().getAll()
            val active = configs.firstOrNull { it.isActive } ?: return@launch
            if (modelId in active.enabledModelIds) {
                val updated = active.copy(model = modelId)
                database.providerConfigDao().update(updated)
                val selected = resolveModelSelection(currentSessionId)
                currentModelName = selected.modelId
                currentProviderName = selected.provider?.name.orEmpty()
                withContext(Dispatchers.Main) { currentModelLabel = selected.modelId }
            }
        }
    }

    /**
     * 给【当前会话】设置专属模型覆盖:可换供应商配置 + 模型。
     * providerConfigId 为 null 时只覆盖模型(跟随当前活跃供应商);两者都 null 时清除覆盖。
     */
    fun switchSessionModel(sessionId: Long, providerConfigId: Long?, modelId: String?) {
        val normalized = ModelSelection.normalize(providerConfigId, modelId)
        applicationScope.launch(Dispatchers.IO) {
            val s = database.sessionDao().getById(sessionId) ?: return@launch
            database.sessionDao().upsert(
                s.copy(
                    modelProviderConfigId = normalized.providerConfigId,
                    currentModelId = normalized.modelId,
                    updatedAt = System.currentTimeMillis()
                )
            )
            val selected = resolveModelSelection(sessionId)
            currentModelName = selected.modelId
            currentProviderName = selected.provider?.name.orEmpty()
            if (sessionId == currentSessionId) {
                withContext(Dispatchers.Main) { currentModelLabel = selected.modelId }
            }
        }
    }

    /** 清除会话级模型覆盖,回到全局活跃配置。 */
    fun clearSessionModelOverride(sessionId: Long) {
        applicationScope.launch(Dispatchers.IO) {
            val s = database.sessionDao().getById(sessionId) ?: return@launch
            database.sessionDao().upsert(
                s.copy(modelProviderConfigId = null, currentModelId = null, updatedAt = System.currentTimeMillis())
            )
            val selected = resolveModelSelection(sessionId)
            currentModelName = selected.modelId
            currentProviderName = selected.provider?.name.orEmpty()
            if (sessionId == currentSessionId) {
                withContext(Dispatchers.Main) { currentModelLabel = selected.modelId }
            }
        }
    }

    /** 把顶栏模型标签刷新成当前会话的覆盖(未覆盖则回到活跃配置的模型)。 */
    fun applySessionModelLabel(sessionId: Long) {
        applicationScope.launch(Dispatchers.IO) {
            val selected = resolveModelSelection(sessionId)
            currentModelName = selected.modelId
            currentProviderName = selected.provider?.name.orEmpty()
            if (sessionId == currentSessionId) {
                withContext(Dispatchers.Main) { currentModelLabel = selected.modelId }
            }
        }
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        thinkingEnabled = enabled
        sessionPool.values.forEach { it.chat.thinkingEnabled = enabled }
        GlobalScope.launch(Dispatchers.IO) {
            database.settingDao().put("thinking_enabled", enabled.toString())
        }
    }

    fun updateThinkingLevel(level: Int) {
        thinkingLevel = level.coerceIn(0, 4)
        sessionPool.values.forEach { it.chat.thinkingLevel = thinkingLevel }
        GlobalScope.launch(Dispatchers.IO) {
            database.settingDao().put("thinking_level", thinkingLevel.toString())
        }
    }

    fun updatePermissionMode(mode: PermissionMode) {
        securityGate.setPermissionMode(mode)
        permissionModeState = mode
        GlobalScope.launch(Dispatchers.IO) {
            database.settingDao().put("permission_mode", mode.name)
        }
    }

    /** Delete a single message row + refresh the visible list. */
    fun deleteMessage(id: Long) {
        GlobalScope.launch(Dispatchers.Main) { agentChatState.deleteMessage(id) }
    }

    /**
     * Regenerate: locate the failed assistant message, delete it (+ trailing tool rows),
     * put the preceding user message text back in the input, and let the user press send.
     * Keeping the two-step flow avoids surprise duplicate sends.
     */
    /**
     * 让 AI 重新回答某条消息所在的那一轮。
     *
     * 传 assistant 的 id 就重答那条;传 user 的 id 就重发那条。
     *
     * 两个要点:
     *  1. 必须删掉【从那条 user 之后的全部消息】,而不只是那一条 assistant ——
     *     后面的对话都是基于旧回答展开的,留着会让新回答和历史自相矛盾。
     *  2. 删完直接重新发送,而不是把原文填回输入框等用户再按一次。
     *     用户点的是「重新回答」,不是「把问题抄给我」。
     */
    fun regenerateFromMessage(messageId: Long) {
        GlobalScope.launch(Dispatchers.Main) {
            if (agentChatState.isStreaming.value) return@launch   // 正在回答时不重入
            val msgs = agentChatState.messages
            val idx = msgs.indexOfFirst { it.id == messageId }
            if (idx < 0) return@launch
            // 定位这一轮的提问:点 user 就是它自己,点 assistant 就往上找最近的 user
            val userIdx = if (msgs[idx].role == "user") idx
            else (idx - 1 downTo 0).firstOrNull { msgs[it].role == "user" } ?: return@launch
            val prompt = msgs[userIdx].content
            if (prompt.isBlank()) return@launch

            // 连同这条提问本身一起删掉——重新发送时会再落一条新的,否则会出现两条一样的提问
            msgs.drop(userIdx).map { it.id }.forEach { agentChatState.deleteMessage(it) }
            agentCore.clearHistory()
            agentChatState.loadHistoryForSession(currentSessionId)
            agentChatState.input.value = prompt
            agentChatState.send()
        }
    }

    /** /compact — summarize and replace history with a digest. */
    fun compactContext() {
        GlobalScope.launch(Dispatchers.Main) {
            val ok = agentChatState.compactContext()
            if (!ok) android.util.Log.w("XINCODE", "compactContext: no-op or failed")
        }
    }

    /** 选中技能 → 在输入框生成 `/技能名 ` 引用(不再塞整段模板);智能体见到会调用该技能。 */
    fun insertSkillIntoInput(name: String) {
        val existing = agentChatState.input.value
        // 已是 /名称 开头就替换那个 token,否则前插引用。
        agentChatState.input.value = if (existing.startsWith("/")) {
            "/$name " + existing.substringAfter(' ', "").trimStart()
        } else {
            "/$name " + existing.trimStart()
        }
    }

    /** 选中 MCP 服务器 → 在输入框生成 `@服务器 ` 引用(提示智能体优先用该 MCP 的工具)。 */
    fun insertMcpIntoInput(name: String) {
        val existing = agentChatState.input.value
        val sep = if (existing.isBlank()) "" else if (existing.endsWith(" ")) "" else " "
        agentChatState.input.value = existing + sep + "@$name "
    }

    /** Sidebar full-text search across all sessions' messages. Returns snippet hits
     *  ordered by recency. Called from Compose — must be safe from the Main dispatcher. */
    suspend fun searchMessages(query: String): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            val hits = database.messageDao().searchByKeyword(query, limit = 40)
            val sessionTitles = database.sessionDao().getAllVisible().associateBy({ it.id }, { it.title })
            hits.map { m ->
                val idx = m.content.indexOf(query, ignoreCase = true).coerceAtLeast(0)
                val start = (idx - 20).coerceAtLeast(0)
                val end = (idx + query.length + 40).coerceAtMost(m.content.length)
                var snippet = m.content.substring(start, end).replace(Regex("\\s+"), " ").trim()
                if (start > 0) snippet = "…$snippet"
                if (end < m.content.length) snippet = "$snippet…"
                SearchHit(
                    messageId = m.id,
                    sessionId = m.sessionId,
                    sessionTitle = sessionTitles[m.sessionId] ?: "对话 ${m.sessionId}",
                    snippet = snippet
                )
            }
        }
    }

    fun updateDarkMode(enabled: Boolean) {
        darkMode = enabled
        GlobalScope.launch(Dispatchers.IO) {
            database.settingDao().put("dark_mode", enabled.toString())
        }
    }

    fun updateEnterToSend(enabled: Boolean) {
        enterToSend = enabled
        GlobalScope.launch(Dispatchers.IO) {
            database.settingDao().put("enter_to_send", enabled.toString())
        }
    }

    fun updateSearchApiKey(key: String) {
        webSearchTool.apiKey = key
        GlobalScope.launch(Dispatchers.IO) {
            val encrypted = android.util.Base64.encodeToString(
                keystore.encrypt(key), android.util.Base64.NO_WRAP
            )
            database.settingDao().put("search_api_key", encrypted)
        }
    }
}
