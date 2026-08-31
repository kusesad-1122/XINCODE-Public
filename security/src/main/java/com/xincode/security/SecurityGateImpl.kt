package com.xincode.security

import org.json.JSONObject

/**
 * Default SecurityGate implementation.
 *
 * ## Three-tier risk classification:
 * - FATAL_BANNED: system partition writes, block device ops, rm -rf /
 * - DANGEROUS: system dir deletions, build.prop, iptables, etc.
 * - NORMAL: everything else
 *
 * ## Permission modes:
 * - ALLOW_ALL: auto-allow (except FATAL_BANNED)
 * - ASK: always show confirmation card
 * - DENY_ALL: auto-deny all
 */
class SecurityGateImpl(
    private val auditLogDao: com.xincode.data.AuditLogDao? = null
) : SecurityGate {

    // ---- permission mode (default ASK) ----
    @Volatile
    private var _permissionMode = PermissionMode.ASK

    override fun setPermissionMode(mode: PermissionMode) { _permissionMode = mode }
    override fun getPermissionMode(): PermissionMode = _permissionMode

    // ---- gap-12 持久化 allow/deny 规则快照(decide 同步查询)----
    @Volatile
    private var permissionRules: List<com.xincode.data.PermissionRuleEntity> = emptyList()
    override fun setPermissionRules(rules: List<com.xincode.data.PermissionRuleEntity>) {
        permissionRules = rules
    }

    /**
     * gap-12 规则求值:deny > allow > 无。
     * @return "deny" / "allow" / null(无规则命中)
     */
    private fun evaluateRules(cmd: GateCommand): String? {
        if (permissionRules.isEmpty()) return null
        val target = extractCommand(cmd)
        var allowHit = false
        for (r in permissionRules) {
            if (!toolFilterMatches(r.toolFilter, cmd.toolName)) continue
            if (!patternMatches(r.pattern, target)) continue
            when (r.action.lowercase()) {
                "deny" -> return "deny"   // deny 恒胜,立即返回
                "allow" -> allowHit = true
            }
        }
        return if (allowHit) "allow" else null
    }

    private fun toolFilterMatches(filter: String, toolName: String): Boolean = when {
        filter == "*" || filter.isBlank() -> true
        filter.endsWith("*") -> toolName.startsWith(filter.dropLast(1))
        else -> filter == toolName
    }

    /** glob(* ?)匹配;空 pattern 匹配任意。 */
    private fun patternMatches(pattern: String, target: String): Boolean {
        if (pattern.isBlank()) return true
        val regex = buildString {
            append("^")
            for (c in pattern) when (c) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> { append('\\'); append(c) }
                else -> append(c)
            }
            append("$")
        }
        return try {
            Regex(regex).containsMatchIn(target) || Regex(regex).matches(target) ||
                target.contains(pattern.trim('*')) // 兼容“git *”这类前缀直觉写法
        } catch (_: Exception) { target.contains(pattern) }
    }

    // ---- audit trail ----
    private val auditTrail = mutableListOf<AuditEntry>()

    // ---- SecurityGate interface ----

    companion object {
        /** gap-13 只读内建工具:任何模式(DENY_ALL 除外)自动放行。 */
        val READ_ONLY_TOOLS = setOf("file_read", "list_dir", "grep", "glob")
        /** gap-15 写/执行类工具:只读/计划模式一律拒绝。 */
        val WRITE_TOOLS = setOf("file_write", "file_edit", "multi_edit", "su_exec")
        /** gap-13 只读安全命令白名单(词边界匹配,无写重定向时自动放行)。 */
        val SAFE_COMMANDS = setOf(
            "ls", "cat", "pwd", "whoami", "id", "echo", "grep", "rg", "egrep", "fgrep",
            "head", "tail", "find", "stat", "file", "wc", "date", "uname", "df", "du",
            "ps", "env", "printenv", "which", "type", "basename", "dirname", "realpath",
            "readlink", "sort", "uniq", "cut", "tr", "diff", "cmp", "md5sum",
            "sha1sum", "sha256sum", "getprop", "true", "test"
        )
        /** git 的只读子命令。 */
        val SAFE_GIT_SUB = setOf("status", "log", "diff", "show", "branch", "rev-parse", "remote", "ls-files", "blame")
    }

    override fun classify(toolName: String, toolArgs: String): GateCommand {
        return when (toolName) {
            // gap-14:su_exec 也按命令风险决定可逆性(而非一律 IRREVERSIBLE),
            // 使 ALLOW_ALL 下普通 root 命令可自动放行、危险 root 命令强制确认。
            "su_exec" -> classifyShellCommand(toolName, toolArgs).copy(capability = Capability.SYSTEM)
            "shell_exec" -> classifyShellCommand(toolName, toolArgs)
            "file_read" -> GateCommand(toolName, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "只读文件操作，可逆")
            "file_write" -> GateCommand(toolName, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "文件写入可回滚")
            "file_edit", "multi_edit" -> GateCommand(toolName, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "文件局部编辑可回滚")
            "list_dir", "grep", "glob" -> GateCommand(toolName, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "只读文件/目录操作，可逆")
            "delete_file" -> GateCommand(toolName, toolArgs, Capability.FS, Reversibility.IRREVERSIBLE, "文件删除不可逆")
            "make_directory" -> GateCommand(toolName, toolArgs, Capability.FS, Reversibility.REVERSIBLE, "目录创建可逆")
            "download_file" -> GateCommand(toolName, toolArgs, Capability.NET, Reversibility.REVERSIBLE, "网络下载")
            "env_exec" -> classifyShellCommand(toolName, toolArgs).copy(capability = Capability.TERMINAL)
            "code_exec" -> GateCommand(toolName, toolArgs, Capability.SYSTEM, Reversibility.REVERSIBLE, "代码执行")
            else -> GateCommand(toolName, toolArgs, Capability.UNKNOWN, Reversibility.IRREVERSIBLE, "未知工具类型，默认不可逆")
        }
    }

    override fun classifyRisk(command: String): RiskLevel {
        val trimmed = command.trim()
        val normalized = trimmed.replace(Regex("\\s+"), " ")

        // ===== FATAL_BANNED =====

        // 1. System partition writes + App 私有数据（bind 后 /data 直通）
        val systemPaths = listOf("/system", "/system_ext", "/vendor", "/product", "/odm", "/boot", "/recovery", "/data/user/0/com.xincode.app", "/data/data/com.xincode.app")
        val destructiveOps = listOf("rm", "mv", "cp", "dd", "mount -o rw", "chmod", "chown", "touch", "mkdir", "rmdir", "ln -sf")
        for (path in systemPaths) {
            for (op in destructiveOps) {
                if (normalized.contains("$op ") && normalized.contains(path)) {
                    return RiskLevel.FATAL_BANNED
                }
            }
        }
        // Also check > /tee redirect to system paths
        for (path in systemPaths) {
            if ((normalized.contains(">") || normalized.contains("tee ")) && normalized.contains(path)) {
                return RiskLevel.FATAL_BANNED
            }
        }

        // 2. Partition table / block device / format operations
        val partitionOps = listOf("parted", "sgdisk", "fdisk", "mkfs", "mke2fs", "make_ext4fs", "wipe",
            "fastboot flash", "flash_image")
        for (op in partitionOps) {
            if (normalized.contains(op)) return RiskLevel.FATAL_BANNED
        }
        // dd/cat/> to /dev/block/*
        if (normalized.contains("/dev/block/") && (normalized.startsWith("dd ") || normalized.startsWith("cat ") || normalized.contains("> /dev/block/"))) {
            return RiskLevel.FATAL_BANNED
        }
        // Writing to boot.img / recovery.img / system.img
        val imgPatterns = listOf("boot.img", "recovery.img", "system.img")
        for (img in imgPatterns) {
            if (normalized.contains(img) && (normalized.contains("dd ") || normalized.contains(">"))) {
                return RiskLevel.FATAL_BANNED
            }
        }

        // 3. rm -rf / or equivalent
        if (normalized.contains("rm -rf /") && !normalized.contains("/data/data/") && !normalized.contains("/sdcard/")) {
            // Check if it's rm -rf / (root) not rm -rf /something
            val afterRm = normalized.substringAfter("rm -rf ").trimStart()
            if (afterRm == "/" || afterRm == "/*" || afterRm.startsWith("/ ") || afterRm.startsWith("/* ")) {
                return RiskLevel.FATAL_BANNED
            }
        }
        // rm -rf /data (entire data, not subdir)
        if (normalized.contains("rm -rf /data") && !normalized.contains("/data/data/") && !normalized.contains("/data/local/") && !normalized.contains("/data/adb/")) {
            val afterData = normalized.substringAfter("rm -rf /data").trim()
            if (afterData.isEmpty() || afterData.startsWith(" ") || afterData == "/*") {
                return RiskLevel.FATAL_BANNED
            }
        }

        // ===== DANGEROUS =====

        // rm -rf on system dirs (non-FATAL)
        if (normalized.contains("rm -rf /data/data/") || normalized.contains("rm -rf /data/system")) {
            return RiskLevel.DANGEROUS
        }
        // build.prop / default.prop modification
        if ((normalized.contains("build.prop") || normalized.contains("default.prop")) &&
            (normalized.startsWith("rm ") || normalized.startsWith("mv ") || normalized.contains(">") || normalized.contains("tee ") || normalized.startsWith("sed "))) {
            return RiskLevel.DANGEROUS
        }
        // iptables -F / firewall flush
        if (normalized.contains("iptables") && (normalized.contains("-F") || normalized.contains("--flush"))) {
            return RiskLevel.DANGEROUS
        }
        // pm uninstall system app
        if (normalized.contains("pm uninstall") && normalized.contains("--user 0")) {
            return RiskLevel.DANGEROUS
        }
        // /etc/hosts modification (root context)
        if (normalized.contains("/etc/hosts") && (normalized.contains(">") || normalized.contains("tee ") || normalized.startsWith("sed "))) {
            return RiskLevel.DANGEROUS
        }
        // setenforce 0
        if (normalized.startsWith("setenforce 0") || normalized.startsWith("setenforce 0 ")) {
            return RiskLevel.DANGEROUS
        }

        // ===== NORMAL =====
        return RiskLevel.NORMAL
    }

    override fun decide(cmd: GateCommand, mode: PermissionMode): Decision {
        val command = extractCommand(cmd)
        val risk = classifyRisk(command)

        // 致命操作:任何模式恒拒。
        if (risk == RiskLevel.FATAL_BANNED) {
            return Decision.Denied("fatal_banned: ${describeFatalViolation(command)}")
        }

        // gap-12:持久化规则优先于模式默认。deny 命中即拒(即使 ALLOW_ALL);allow 命中跳过确认。
        when (evaluateRules(cmd)) {
            "deny" -> return Decision.Denied("rule_deny: 命中拒绝规则")
            "allow" -> return Decision.Allow("rule_allow: 命中放行规则")
        }

        val isReadOnlyTool = cmd.toolName in READ_ONLY_TOOLS
        // gap-13:shell_exec 的只读安全命令(且无写重定向)视为安全。
        val isSafeShell = cmd.toolName == "shell_exec" && isSafeReadOnlyCommand(command)
        val safe = isReadOnlyTool || isSafeShell

        return when (mode) {
            PermissionMode.DENY_ALL ->
                Decision.Denied("user_denied_global: 权限模式为「禁止全部」，已自动拒绝所有工具调用")

            // gap-15:只读/计划模式——放行只读工具与安全命令,拒绝一切写/执行。
            PermissionMode.READ_ONLY, PermissionMode.PLAN -> {
                val label = if (mode == PermissionMode.PLAN) "计划" else "只读"
                if (safe) Decision.Allow("$label 模式:只读操作,放行")
                else Decision.Denied("read_only_mode: $label 模式禁止写/执行操作(${cmd.toolName})")
            }

            // gap-13:安全只读自动放行。
            PermissionMode.ASK -> {
                if (safe) Decision.Allow("只读安全操作,自动放行")
                else Decision.NeedConfirm("权限模式「询问」，需要确认", preview(cmd))
            }

            // 允许全部(全自动):真·放行一切——只有 FATAL_BANNED(上面已拦)与显式 deny 规则能挡。
            // 用户明确要求"允许全部就真的全部执行、别再问",故不再对危险/不可逆强制确认。
            PermissionMode.ALLOW_ALL ->
                Decision.Allow("权限模式「允许全部」，自动放行(全自动)")
        }
    }

    /**
     * gap-13:判断一条 shell 命令是否为“只读安全命令”。
     * 按 shell 操作符(&& || ; |)分段,每段首词须在 SAFE_COMMANDS(git 需只读子命令),
     * 且不得含写重定向(> >> tee)。任一段不满足即视为不安全。
     */
    private fun isSafeReadOnlyCommand(raw: String): Boolean {
        val command = raw.trim()
        if (command.isEmpty()) return false
        // 写重定向直接判不安全(允许 2>/dev/null、>&2 之类的 stderr 重定向不在此列,简单起见含 > 即视为不安全)
        if (Regex("(^|\\s)(>>?|\\btee\\b)").containsMatchIn(command)) return false
        val segments = command.split(Regex("(&&|\\|\\||;|\\|)"))
        for (segRaw in segments) {
            val seg = segRaw.trim()
            if (seg.isEmpty()) continue
            val tokens = seg.split(Regex("\\s+"))
            val head = tokens.firstOrNull()?.substringAfterLast('/') ?: return false
            if (head == "git") {
                val sub = tokens.getOrNull(1)
                if (sub == null || sub !in SAFE_GIT_SUB) return false
            } else if (head !in SAFE_COMMANDS) {
                return false
            }
        }
        return true
    }

    override fun preview(cmd: GateCommand): String {
        val sb = StringBuilder()
        sb.appendLine("=== 操作预览 ===")
        sb.appendLine("工具: ${cmd.toolName}")
        sb.appendLine("参数: ${cmd.toolArgs}")
        when (cmd.toolName) {
            "shell_exec", "su_exec", "env_exec" -> {
                val cmdText = try { JSONObject(cmd.toolArgs).optString("command", cmd.toolArgs) } catch (_: Exception) { cmd.toolArgs }
                sb.appendLine("命令: $cmdText")
                if (cmd.toolName == "su_exec") {
                    val stdin = try { JSONObject(cmd.toolArgs).optString("stdin", "") } catch (_: Exception) { "" }
                    if (stdin.isNotBlank()) sb.appendLine("stdin: $stdin")
                    sb.appendLine("⚠ 此命令以 root 身份执行，请确认命令正确且不会造成不可逆损失")
                }
                if (cmd.toolName == "env_exec") sb.appendLine("⚠ Ubuntu chroot 内执行，已透传 /sdcard /storage /data")
            }
            "file_write", "delete_file", "make_directory", "download_file" -> {
                val path = try { JSONObject(cmd.toolArgs).optString("path", "?") } catch (_: Exception) { "?" }
                sb.appendLine("目标: $path")
                if (cmd.toolName == "delete_file") sb.appendLine("⚠ 将删除文件/目录")
                else if (cmd.toolName == "file_write") sb.appendLine("⚠ 将覆写现有文件内容")
            }
        }
        return sb.toString()
    }

    override fun audit(cmd: GateCommand, decision: Decision, result: String?) {
        val ds = when (decision) {
            is Decision.Allow -> "Allow: ${decision.reason}"
            is Decision.NeedConfirm -> "NeedConfirm: ${decision.reason}"
            is Decision.Denied -> "Denied: ${decision.reason}"
        }
        val entry = com.xincode.data.AuditLogEntity(
            timestamp = System.currentTimeMillis(),
            toolName = cmd.toolName,
            toolArgs = cmd.toolArgs,
            capability = cmd.capability.name,
            reversibility = cmd.reversibility.name,
            decision = ds,
            result = result
        )
        if (auditLogDao != null) {
            Thread {
                try {
                    kotlinx.coroutines.runBlocking { auditLogDao.insert(entry) }
                } catch (_: Exception) {}
            }.start()
        } else {
            auditTrail.add(AuditEntry(System.currentTimeMillis(), cmd.toolName, cmd.toolArgs,
                cmd.capability, cmd.reversibility, ds, result))
        }
    }

    override fun getAuditTrail(): List<AuditEntry> = auditTrail.toList()

    // ---- private helpers ----

    private fun extractCommand(cmd: GateCommand): String = when (cmd.toolName) {
        "shell_exec", "su_exec" -> {
            try { JSONObject(cmd.toolArgs).optString("command", cmd.toolArgs) } catch (_: Exception) { cmd.toolArgs }
        }
        "file_read", "file_write" -> {
            try { JSONObject(cmd.toolArgs).optString("path", cmd.toolArgs) } catch (_: Exception) { cmd.toolArgs }
        }
        else -> cmd.toolArgs
    }

    private fun describeFatalViolation(command: String): String {
        val norm = command.replace(Regex("\\s+"), " ")
        return when {
            norm.contains("/system") || norm.contains("/system_ext") ||
            norm.contains("/vendor") || norm.contains("/product") ||
            norm.contains("/odm") || norm.contains("/boot") || norm.contains("/recovery") ->
                "禁止写入系统分区 ($command)"
            norm.contains("parted") || norm.contains("fdisk") || norm.contains("mkfs") ||
            norm.contains("/dev/block/") || norm.contains("fastboot") || norm.contains("flash_image") ->
                "禁止操作分区表/块设备"
            norm.contains("rm -rf /") -> "禁止删除根目录"
            norm.contains("rm -rf /data") && !norm.contains("/data/data/") && !norm.contains("/data/local/") ->
                "禁止删除 /data 整体"
            else -> "致命违规操作"
        }
    }

    private fun classifyShellCommand(toolName: String, toolArgs: String): GateCommand {
        val command = try { JSONObject(toolArgs).optString("command", "") } catch (_: Exception) { "" }
        val risk = classifyRisk(command)
        val rev = when (risk) {
            RiskLevel.FATAL_BANNED -> Reversibility.IRREVERSIBLE
            RiskLevel.DANGEROUS -> Reversibility.IRREVERSIBLE
            RiskLevel.NORMAL -> Reversibility.REVERSIBLE
        }
        val cap = inferCapability(command)
        val why = "风险等级: $risk, 命令: '$command'"
        return GateCommand(toolName, toolArgs, cap, rev, why)
    }

    private fun inferCapability(command: String): Capability = when {
        command.contains("kill") || command.contains("renice") -> Capability.PROCESS
        command.contains("iptables") || command.contains("ifconfig") -> Capability.NET
        command.contains("mount") || command.contains("insmod") -> Capability.KERNEL
        command.contains("pm ") || command.contains("am ") -> Capability.APP
        command.contains("settings") || command.contains("setprop") -> Capability.SYSTEM
        command.contains("gradle") || command.contains("sdkmanager") || command.contains("apt-get") || command.contains("apt ") -> Capability.BUILD
        command.contains("chroot") || command.contains("env -i") -> Capability.TERMINAL
        else -> Capability.FS
    }
}