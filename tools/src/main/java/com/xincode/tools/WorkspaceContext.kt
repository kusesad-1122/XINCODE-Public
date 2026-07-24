package com.xincode.tools

/**
 * 运行期工作区上下文。
 *
 * B2 修复:之前是纯进程级全局单例,多个会话并发时(后台会话仍在跑),文件/记忆工具会读到
 * 【最后切换到的那个会话】的工作区/项目,造成跨项目写错目录 + 记忆泄漏。
 *
 * 现在读取优先用【线程覆盖值】(由 [WorkspaceThreadElement] 在各会话自己的协程作用域内设置),
 * 没有覆盖时回退到【全局值】(设置页/无 per-session 上下文的后台核如 cron/goal 用)。
 * 这样每个会话核在自己的作用域里执行工具时,始终解析到【它自己】的工作区/项目,与前台切换无关。
 */
object WorkspaceContext {
    const val DEFAULT_ROOT = "/storage/emulated/0/XINCODE"

    // 全局兜底(可被设置/项目覆盖)。
    @Volatile
    private var globalRoot: String = DEFAULT_ROOT
    @Volatile
    private var globalProjectId: Long = 0L

    // 线程级覆盖(由协程 ThreadContextElement 设置);null = 用全局兜底。
    private val tlRoot = ThreadLocal<String?>()
    private val tlProjectId = ThreadLocal<Long?>()

    /** 当前生效的工作区根:优先线程覆盖,否则全局兜底。setter 写全局兜底(保持旧行为)。 */
    var workspaceRoot: String
        get() = tlRoot.get() ?: globalRoot
        set(value) { globalRoot = if (value.isBlank()) DEFAULT_ROOT else value.trimEnd('/') }

    /** 当前生效的项目 id(记忆按项目隔离):优先线程覆盖,否则全局兜底。 */
    var projectId: Long
        get() = tlProjectId.get() ?: globalProjectId
        set(value) { globalProjectId = value }

    // —— 供 WorkspaceThreadElement 使用的线程覆盖读写 ——
    internal fun pushThread(root: String?, pid: Long?) { tlRoot.set(root); tlProjectId.set(pid) }
    internal fun peekThread(): Pair<String?, Long?> = tlRoot.get() to tlProjectId.get()
}
