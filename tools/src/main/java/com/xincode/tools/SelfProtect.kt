package com.xincode.tools

/**
 * 自我保护:不让 AI 动 App 自己的运行时数据。
 *
 * ## 这是从一次真实事故来的
 *
 * 有用户让 AI「自行安装 skill」,AI 在过程中动到了
 * `/data/user/0/com.xincode.app/databases/`。下次启动 Room 打不开库:
 *
 * ```
 * SQLiteCantOpenDatabaseException: Cannot open database
 * [unable to open database file (code 14 SQLITE_CANTOPEN): Permission denied]
 * File /data/user/0/com.xincode.app/databases/xincode.db is not readable
 * ```
 *
 * 注意这不是「数据坏了」—— 数据一个字节都没变,坏的只是**文件权限**。
 * 但对启动流程来说打不开就是打不开,只能改名备份 + 重建空库,
 * 用户的会话、身份卡、供应商配置、记忆**全部消失**。
 *
 * root 动作尤其危险:显式 `su_exec` 里的 `chmod` / `chown` / `mkdir`
 * 仍可能让文件变成 root 所有；普通 `shell_exec` 保持应用 uid,也同样必须
 * 拦住这些私有运行目录。
 *
 * ## 为什么是「拒绝」而不是「确认」
 *
 * 这里没有任何值得权衡的正当用途:AI 要存东西有工作区、有记忆、有技能表,
 * 没有一样需要它去写自己的 sqlite 文件或 SharedPreferences。
 * 允许的唯一后果就是把 App 自己搞坏,所以直接堵死,不走确认弹窗
 * —— 弹窗只会让用户在看不懂的路径上点「允许」。
 *
 * ## 为什么不整个 data 目录一刀切
 *
 * `files/` 下面住着内置 Ubuntu 环境(`filesDir/ubuntu`),部署时本来就要 root
 * 去 chmod/chown 整棵树;`cache/`、`attachments/` 也是正常读写区。
 * 一刀切会把这些功能一起废掉。所以只锁**运行时状态**那几个目录。
 */
object SelfProtect {

    /**
     * App 自己的私有数据目录(如 `/data/user/0/com.xincode.app`)。
     * 由 Application 启动时注入 —— tools 模块拿不到 Context,不能自己算。
     */
    @Volatile
    var appDataDir: String = ""
        set(value) { field = value.replace('\\', '/').trimEnd('/') }

    /**
     * 锁死的子目录。只挑「坏了 App 就起不来」的那几个:
     *  - `databases` —— 事故现场,全部用户数据都在这
     *  - `shared_prefs` —— 设置与首启标志
     *  - `no_backup` / `code_cache` —— 系统自用,动了没有任何好处
     */
    private val LOCKED = listOf("databases", "shared_prefs", "no_backup", "code_cache")

    /**
     * 该路径是否落在锁死区里。
     *
     * 路径先规范化再比,`files/../databases/x` 这种绕法一起挡掉。
     */
    fun isProtected(path: String): Boolean {
        val base = appDataDir
        if (base.isEmpty() || path.isBlank()) return false
        val canonical = canonicalForPolicy(tidySeparators(path))
        return LOCKED.any { sub ->
            canonical == "$base/$sub" || canonical.startsWith("$base/$sub/")
        }
    }

    /**
     * 输入级规范化:反斜杠→斜杠、多余斜杠合并。先做这一步,Windows 风格
     * (`databases\\x.db`)与双斜杠写法在任何平台都被同一规则判定。
     */
    private fun tidySeparators(path: String): String =
        path.replace('\\', '/').replace(Regex("/+"), "/")

    /**
     * 命令级规范化:去引号 + 分隔符整理 + 词法消解 `/./` 与 `/../`。
     *
     * 命令是整行文本,不能调文件 canonical(相对段无基准、引号管道会干扰),但
     * `$dir/files/../databases/x.db` 这种字面绕法必须在匹配前压平,否则
     * contains 精确匹配会被 `..` 段直接绕过。空格/引号保留在段内,不影响子串匹配。
     */
    private fun normalizeCommand(command: String): String {
        val tidy = tidySeparators(command.replace("'", "").replace("\"", ""))
        val absolute = tidy.startsWith('/')
        val parts = ArrayDeque<String>()
        tidy.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return (if (absolute) "/" else "") + parts.joinToString("/")
    }

    /** Keep Android path policy deterministic in JVM tests running on Windows too. */
    private fun canonicalForPolicy(path: String): String {
        if (java.io.File.separatorChar == '/') {
            return runCatching { java.io.File(path).canonicalPath }
                .getOrElse { java.io.File(path).absolutePath }
                .replace('\\', '/')
        }
        val absolute = path.replace('\\', '/').startsWith('/')
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return (if (absolute) "/" else "") + parts.joinToString("/")
    }

    /** 给模型看的拒绝理由;不该拦时返回 null。 */
    fun refuse(path: String): String? {
        if (!isProtected(path)) return null
        return "拒绝:`$path` 是 XINCODE 自己的运行时数据目录。" +
            "改动这里会让 App 下次启动打不开数据库,用户的会话、身份卡、供应商配置、记忆会全部丢失。" +
            "要存文件请写到工作区;要存知识请用记忆工具;要装技能请用技能管理,不要直接碰这个目录。"
    }

    /**
     * 扫一条 shell 命令里有没有摸到锁死区。
     *
     * 【为什么只能做字符串匹配】命令是任意文本,变量展开、引号、管道都无法在执行前真正求值,
     * 想精确判断就得写一个 shell 解析器。但这里要挡的不是攻击者,是**模型顺手敲下的一行
     * chmod/rm** —— 那种命令里路径就是明明白白写着的。所以按字面匹配即可,
     * 漏判的代价是回到今天的状态,不会更糟;而误判只会拦下本来也不该执行的命令。
     */
    fun refuseCommand(command: String): String? {
        val base = appDataDir
        if (base.isEmpty()) return null
        val norm = normalizeCommand(command)
        // 同一个私有目录有两种写法,两种都要认:/data/user/0/<pkg> 与 /data/data/<pkg>
        val pkg = base.substringAfterLast('/')
        val prefixes = listOf(base, "/data/data/$pkg", "/data/user/0/$pkg")
        for (p in prefixes) {
            for (sub in LOCKED) {
                if (norm.contains("$p/$sub")) {
                    return "拒绝:这条命令要动 `$p/$sub` —— 那是 XINCODE 自己的运行时数据。" +
                        "动了它 App 下次就打不开数据库,用户数据会全部丢失。换个目录做这件事。"
                }
            }
        }
        return null
    }
}
