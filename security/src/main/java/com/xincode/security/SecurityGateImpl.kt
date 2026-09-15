package com.xincode.security

import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

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

    /** A-DEAD-2:设置权威权限围栏;传 null 即取消围栏,decide() 恢复现状行为。 */
    override fun setAuthorityProfile(profile: PermissionProfile?) {
        authorityProfile = profile
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

    /**
     * 审计写入单线程串行化,保证哈希链的 prevHash 严格按插入顺序链接
     * (原实现每条 new Thread,并发插入会打乱链序)。
     */
    private val auditExecutor = Executors.newSingleThreadExecutor()

    // ---- A-DEAD-2 权威档(默认不设置 = 行为与现状完全一致)----
    @Volatile
    private var authorityProfile: PermissionProfile? = null

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
        val raw = command
        // §5.4 解析差异防御:先把命令 tokenize 成 argv(剥引号/转义),并把 ${IFS} 还原成空格,
        // 使 'rm' '-rf' '/'、r''m -rf /、rm${IFS}-rf${IFS}/ 都还原成 rm -rf / 一类语义。
        // 主判据基于 canonical argv;原始串的 contains 仅作【补充判据】,不再作为主判据。
        val deIfs = raw.replace(Regex("(?i)\\$\\{IFS\\}"), " ")
        val tokens = tokenizeCommand(deIfs)
        val canonical = tokens.joinToString(" ")
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        fun hit(s: String) = canonical.contains(s) || normalized.contains(s)

        // ===== FATAL_BANNED =====

        // 1. System partition writes + App 私有数据（bind 后 /data 直通）
        val systemPaths = listOf("/system", "/system_ext", "/vendor", "/product", "/odm", "/boot", "/recovery") + XINCODE_APP_DATA_ROOTS
        val destructiveOps = listOf("rm", "mv", "cp", "dd", "mount -o rw", "chmod", "chown", "touch", "mkdir", "rmdir", "ln -sf")
        for (path in systemPaths) {
            for (op in destructiveOps) {
                if (hit("$op ") && hit(path)) {
                    return RiskLevel.FATAL_BANNED
                }
            }
        }
        // Also check > /tee redirect to system paths
        for (path in systemPaths) {
            if ((hit(">") || hit("tee ")) && hit(path)) {
                return RiskLevel.FATAL_BANNED
            }
        }

        // 2. Partition table / block device / format operations
        val partitionOps = listOf("parted", "sgdisk", "fdisk", "mkfs", "mke2fs", "make_ext4fs", "wipe",
            "fastboot flash", "flash_image")
        for (op in partitionOps) {
            if (hit(op)) return RiskLevel.FATAL_BANNED
        }
        // dd/cat/> to /dev/block/*
        if (hit("/dev/block/") && (canonical.startsWith("dd ") || canonical.startsWith("cat ") || hit("> /dev/block/"))) {
            return RiskLevel.FATAL_BANNED
        }
        // Writing to boot.img / recovery.img / system.img
        val imgPatterns = listOf("boot.img", "recovery.img", "system.img")
        for (img in imgPatterns) {
            if (hit(img) && (hit("dd ") || hit(">"))) {
                return RiskLevel.FATAL_BANNED
            }
        }

        // 3. rm -rf / or equivalent
        if (hit("rm -rf /") && !hit("/data/data/") && !hit("/sdcard/")) {
            // Check if it's rm -rf / (root) not rm -rf /something
            val afterRm = canonical.substringAfter("rm -rf ").trimStart()
            if (afterRm == "/" || afterRm == "/*" || afterRm.startsWith("/ ") || afterRm.startsWith("/* ")) {
                return RiskLevel.FATAL_BANNED
            }
        }
        // rm -rf /data (entire data, not subdir)
        if (hit("rm -rf /data") && !hit("/data/data/") && !hit("/data/local/") && !hit("/data/adb/")) {
            val afterData = canonical.substringAfter("rm -rf /data").trim()
            if (afterData.isEmpty() || afterData.startsWith(" ") || afterData == "/*") {
                return RiskLevel.FATAL_BANNED
            }
        }

        // ===== DANGEROUS =====

        // rm -rf on system dirs (non-FATAL)
        if (hit("rm -rf /data/data/") || hit("rm -rf /data/system")) {
            return RiskLevel.DANGEROUS
        }
        // build.prop / default.prop modification
        if ((hit("build.prop") || hit("default.prop")) &&
            (canonical.startsWith("rm ") || canonical.startsWith("mv ") || canonical.startsWith("sed ") || hit(">") || hit("tee "))) {
            return RiskLevel.DANGEROUS
        }
        // iptables -F / firewall flush
        if (hit("iptables") && (hit("-F") || hit("--flush"))) {
            return RiskLevel.DANGEROUS
        }
        // pm uninstall system app
        if (hit("pm uninstall") && hit("--user 0")) {
            return RiskLevel.DANGEROUS
        }
        // /etc/hosts modification (root context)
        if (hit("/etc/hosts") && (hit(">") || hit("tee ") || canonical.startsWith("sed "))) {
            return RiskLevel.DANGEROUS
        }
        // setenforce 0
        if (canonical.startsWith("setenforce 0") || canonical.startsWith("setenforce 0 ")) {
            return RiskLevel.DANGEROUS
        }

        // ===== NORMAL（带解析差异防御）=====
        // 含命令替换/变量/IFS 拼接/命令分隔/重定向/brace 展开等语法的命令,
        // 全自动档下也绝不允许静默放行(至少升为 DANGEROUS → 触发确认)。
        if (containsShellExpansionSyntax(raw)) return RiskLevel.DANGEROUS

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

        val base = when (mode) {
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

            // 允许全部(全自动):语义 = 【免确认】,不是【免拦截】(契约 §5.3 红线)。
            // 只有"普通 + 可逆"操作才直接放行;其余(危险 / 不可逆 / 含解析差异语法)至少确认一次。
            // FATAL_BANNED 在上方已恒拒,此处不重复处理。
            PermissionMode.ALLOW_ALL -> {
                if (risk == RiskLevel.NORMAL && cmd.reversibility == Reversibility.REVERSIBLE) {
                    Decision.Allow("权限模式「允许全部」，自动放行(全自动)")
                } else {
                    Decision.NeedConfirm(
                        "全自动档下该操作非「普通+可逆」(risk=$risk, rev=${cmd.reversibility}),按契约 §5.3 至少确认一次",
                        preview(cmd)
                    )
                }
            }
        }
        // A-DEAD-2:权威档求交(fail-closed)。未设置权威档时直接返回 base,行为不变。
        return applyAuthority(base, cmd)
    }

    /** A-DEAD-2:把「权威档」与「请求档」求交;Unfit 或超出围栏一律按拒绝处理。 */
    private fun applyAuthority(base: Decision, cmd: GateCommand): Decision {
        val auth = authorityProfile ?: return base
        val requested = profileForCommand(cmd)
        return when (val res = intersectProfiles(auth, requested)) {
            is IntersectionResult.Unfit ->
                Decision.Denied("authority_intersection_unfit: ${res.reason}")
            is IntersectionResult.Ok -> {
                // 命令无法收敛到具体路径(shell/未知工具):围栏下 fail-closed 拒绝。
                if (requested.fs.kind == FsPolicyKind.UNRESTRICTED) {
                    Decision.Denied("authority_intersection_denied: 命令无法收敛到路径,超出权威围栏")
                } else {
                    val path = extractPathForIntersection(cmd) ?: ""
                    val write = cmd.toolName in WRITE_TOOLS || cmd.toolName == "su_exec"
                    if (res.profile.allows(path, write)) base
                    else Decision.Denied("authority_intersection_denied: 路径 $path 超出权威围栏")
                }
            }
        }
    }

    /** A-DEAD-2:把一次工具请求收敛成权限剖面;能解析到路径的收敛为文件授权,否则视为不受限(交由权威档收紧)。 */
    private fun profileForCommand(cmd: GateCommand): PermissionProfile {
        val path = extractPathForIntersection(cmd)
        val write = cmd.toolName in WRITE_TOOLS || cmd.toolName == "su_exec"
        return if (path != null) {
            val access = if (write) FsAccess.READ_WRITE else FsAccess.READ
            PermissionProfile(FsPolicy.restricted(FsGrant(normalizeFsPath(path), access)), NetPolicy.RESTRICTED)
        } else {
            PermissionProfile.unrestricted()
        }
    }

    /** A-DEAD-2:仅对文件类工具提取路径,用于在权威围栏内判定允许/拒绝。 */
    private fun extractPathForIntersection(cmd: GateCommand): String? {
        return try {
            val obj = JSONObject(cmd.toolArgs)
            when (cmd.toolName) {
                "file_read", "file_write", "file_edit", "multi_edit",
                "delete_file", "make_directory", "download_file" ->
                    obj.optString("path", "").takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (_: Exception) { null }
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
            // 单线程串行写入,保证 prevHash 严格按插入顺序链接成链。
            auditExecutor.submit {
                try {
                    kotlinx.coroutines.runBlocking {
                        val prevHash = auditLogDao.getRecent(1).firstOrNull()?.hash ?: ""
                        val hash = computeAuditHash(
                            prevHash, entry.timestamp, entry.toolName, entry.toolArgs, entry.decision, entry.result
                        )
                        auditLogDao.insert(entry.copy(prevHash = prevHash, hash = hash))
                    }
                } catch (_: Exception) {}
            }
        } else {
            auditTrail.add(AuditEntry(System.currentTimeMillis(), cmd.toolName, cmd.toolArgs,
                cmd.capability, cmd.reversibility, ds, result))
        }
    }

    override fun getAuditTrail(): List<AuditEntry> {
        val dao = auditLogDao ?: return auditTrail.toList()
        return try {
            kotlinx.coroutines.runBlocking { dao.getRecent(200) }.mapNotNull { e ->
                try {
                    AuditEntry(
                        e.timestamp, e.toolName, e.toolArgs,
                        Capability.valueOf(e.capability), Reversibility.valueOf(e.reversibility),
                        e.decision, e.result
                    )
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            auditTrail.toList()
        }
    }

    /**
     * 校验审计哈希链完整性。
     * - ok=true 且 brokenAt=null:链完整(或没有 DAO,无可篡改数据)。
     * - ok=false:在 brokenAt 处发现断链/被篡改。
     */
    fun verifyAuditChain(): AuditChainVerification {
        val dao = auditLogDao ?: return AuditChainVerification(ok = true)
        return try {
            val rows = kotlinx.coroutines.runBlocking { dao.getAll() }.sortedBy { it.id }
            var prev = ""
            for ((i, e) in rows.withIndex()) {
                if (e.prevHash != prev) return AuditChainVerification(ok = false, brokenAt = i)
                val expected = computeAuditHash(e.prevHash, e.timestamp, e.toolName, e.toolArgs, e.decision, e.result)
                if (expected != e.hash) return AuditChainVerification(ok = false, brokenAt = i)
                prev = e.hash
            }
            AuditChainVerification(ok = true)
        } catch (_: Exception) {
            AuditChainVerification(ok = true)
        }
    }

    /** sha256(prevHash|timestamp|toolName|toolArgs|decision|result)。 */
    private fun computeAuditHash(
        prevHash: String,
        timestamp: Long,
        toolName: String,
        toolArgs: String,
        decision: String,
        result: String?
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = buildString {
            append(prevHash); append('|')
            append(timestamp); append('|')
            append(toolName); append('|')
            append(toolArgs); append('|')
            append(decision); append('|')
            append(result ?: "")
        }
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

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