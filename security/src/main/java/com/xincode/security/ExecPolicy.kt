package com.xincode.security

/**
 * 执行策略判定核心 —— 移植自 OpenAI Codex。
 *
 * Ported from https://github.com/openai/codex (Apache-2.0):
 * - codex-rs/execpolicy/src/decision.rs (三态裁决,越严越大)
 * - codex-rs/execpolicy/src/rule.rs (前缀模式/网络规则/host 归一化/正反例自检思想)
 * - codex-rs/execpolicy/src/policy.rs (按首 token 分派/启发回退/叠加合并/域名编译)
 *
 * 有意没搬的部分(见文件尾说明):
 * - Starlark 策略文件解析器(parser.rs):解释器太重,这里用 Builder API 等价组装;
 * - host 可执行文件路径归属(match_host_executable_rules):POSIX PATH 语义,
 *   Android root/chroot 路径模型不同,搬过来是错的。
 *
 * 与本模块现有 [Decision] 的对应:ALLOW→Allow, PROMPT→NeedConfirm, FORBIDDEN→Denied。
 */

/** 三态裁决。ordinal 即严重度:合并多条命中时【最严者胜】(与 Codex Evaluation::from_matches 一致)。 */
enum class ExecVerdict {
    ALLOW,
    PROMPT,
    FORBIDDEN;

    companion object {
        fun parse(raw: String): ExecVerdict = when (raw) {
            "allow" -> ALLOW
            "prompt" -> PROMPT
            "forbidden" -> FORBIDDEN
            else -> throw IllegalArgumentException("verdict 必须是 allow/prompt/forbidden(得 $raw)")
        }
    }

    /** 映射到本模块闸门类型(preview 由调用方按工具生成,这里只管裁决)。 */
    fun toDecision(reason: String, preview: String = ""): Decision = when (this) {
        ALLOW -> Decision.Allow(reason)
        PROMPT -> Decision.NeedConfirm(reason, preview)
        FORBIDDEN -> Decision.Denied(reason)
    }
}

/** 单个命令词的匹配器:定值,或若干备选之一(对应视频/源码里的 Alts)。 */
sealed class PatternToken {
    data class Single(val expected: String) : PatternToken()
    data class Alternatives(val options: List<String>) : PatternToken()

    fun matches(token: String): Boolean = when (this) {
        is Single -> expected == token
        is Alternatives -> options.any { it == token }
    }

    override fun toString(): String = when (this) {
        is Single -> expected
        is Alternatives -> "[${options.joinToString("|")}]"
    }
}

/**
 * 前缀模式:首 token 固定(策略按它建索引),后面跟若干词匹配器。
 * 命中返回实际命中的前缀,供审计/提示展示。
 */
data class PrefixPattern(val first: String, val rest: List<PatternToken>) {
    fun matchesPrefix(cmd: List<String>): List<String>? {
        val patternLength = rest.size + 1
        if (cmd.size < patternLength || cmd[0] != first) return null
        for (i in rest.indices) {
            if (!rest[i].matches(cmd[1 + i])) return null
        }
        return cmd.subList(0, patternLength).toList()
    }
}

/** 一条命中的说明:前缀命中(含依据)或启发回退。 */
sealed class ExecRuleMatch {
    abstract fun verdict(): ExecVerdict

    data class Prefix(
        val matchedPrefix: List<String>,
        val verdict: ExecVerdict,
        val justification: String? = null
    ) : ExecRuleMatch() {
        override fun verdict() = verdict
    }

    data class Heuristics(val command: List<String>, val verdict: ExecVerdict) : ExecRuleMatch() {
        override fun verdict() = verdict
    }
}

data class PrefixRule(
    val pattern: PrefixPattern,
    val verdict: ExecVerdict,
    val justification: String? = null
)

/** 网络规则协议(别名与 Codex 一致:https_connect/http-connect 都算 https)。 */
enum class NetworkProtocol(val policyString: String) {
    HTTP("http"),
    HTTPS("https"),
    SOCKS5_TCP("socks5_tcp"),
    SOCKS5_UDP("socks5_udp");

    companion object {
        fun parse(raw: String): NetworkProtocol = when (raw) {
            "http" -> HTTP
            "https", "https_connect", "http-connect" -> HTTPS
            "socks5_tcp" -> SOCKS5_TCP
            "socks5_udp" -> SOCKS5_UDP
            else -> throw IllegalArgumentException(
                "network 协议必须是 http/https/socks5_tcp/socks5_udp(得 $raw)"
            )
        }
    }
}

data class NetworkRule(
    val host: String,
    val protocol: NetworkProtocol,
    val verdict: ExecVerdict,
    val justification: String? = null
)

/**
 * host 归一化(与 Codex normalize_network_rule_host 同规则):
 * 裸主机名/IP,不带 scheme/path;IPv6 去括号;去端口;小写;禁通配符与空白。
 */
fun normalizeNetworkHost(raw: String): String {
    var host = raw.trim()
    require(host.isNotEmpty()) { "network host 不能为空" }
    require(!host.contains("://") && !host.contains('/') && !host.contains('?') && !host.contains('#')) {
        "network host 必须是裸主机名/IP,不带 scheme 与路径(得 $raw)"
    }
    if (host.startsWith("[")) {
        val close = host.indexOf(']')
        require(close > 0) { "network host IPv6 括号不配对(得 $raw)" }
        val rest = host.substring(close + 1)
        if (rest.isNotEmpty()) {
            require(rest.startsWith(":") && rest.length > 1 && rest.drop(1).all { it.isDigit() }) {
                "network host 后缀不支持(得 $raw)"
            }
        }
        host = host.substring(1, close)
    } else if (host.count { it == ':' } == 1) {
        val idx = host.lastIndexOf(':')
        val port = host.substring(idx + 1)
        if (host.substring(0, idx).isNotEmpty() && port.isNotEmpty() && port.all { it.isDigit() }) {
            host = host.substring(0, idx)
        }
    }
    val normalized = host.trimEnd('.').trim().lowercase()
    require(normalized.isNotEmpty()) { "network host 不能为空" }
    require(!normalized.contains('*')) { "network host 必须是具体主机,禁通配符(得 $raw)" }
    require(normalized.none { it.isWhitespace() }) { "network host 不能含空白(得 $raw)" }
    return normalized
}

/** 一次求值结果:最终裁决 + 所有命中(审计用)。 */
data class ExecEvaluation(val verdict: ExecVerdict, val matchedRules: List<ExecRuleMatch>) {
    /** 是否有真实规则命中(纯启发回退不算)。 */
    fun isMatch(): Boolean = matchedRules.any { it !is ExecRuleMatch.Heuristics }
}

/**
 * 执行策略:按首 token 索引的前缀规则 + 网络规则。
 * 用法:addPrefixRule/addNetworkRule 组装 → check 求值 → verdict.toDecision() 进闸门。
 */
class ExecPolicy {
    private val rulesByProgram = mutableMapOf<String, MutableList<PrefixRule>>()
    private val networkRules = mutableListOf<NetworkRule>()

    fun addPrefixRule(
        prefix: List<String>,
        verdict: ExecVerdict,
        justification: String? = null
    ): ExecPolicy {
        require(prefix.isNotEmpty()) { "prefix 不能为空" }
        if (justification != null) require(justification.isNotBlank()) { "justification 为空不如不写" }
        val first = prefix.first()
        val pattern = PrefixPattern(first, prefix.drop(1).map { PatternToken.Single(it) })
        rulesByProgram.getOrPut(first) { mutableListOf() }
            .add(PrefixRule(pattern, verdict, justification))
        return this
    }

    /** 带备选词的前缀规则,如 ["git", Alts("push","pull")]。 */
    fun addPrefixRuleAlts(
        first: String,
        rest: List<PatternToken>,
        verdict: ExecVerdict,
        justification: String? = null
    ): ExecPolicy {
        rulesByProgram.getOrPut(first) { mutableListOf() }
            .add(PrefixRule(PrefixPattern(first, rest), verdict, justification))
        return this
    }

    fun addNetworkRule(
        host: String,
        protocol: NetworkProtocol,
        verdict: ExecVerdict,
        justification: String? = null
    ): ExecPolicy {
        val normalized = normalizeNetworkHost(host)
        if (justification != null) require(justification.isNotBlank()) { "justification 为空不如不写" }
        networkRules.add(NetworkRule(normalized, protocol, verdict, justification))
        return this
    }

    /** 求值:精确规则命中(可多条,取最严) → 无命中且有回退则启发裁决 → 无命中无回退则空评估。 */
    fun check(
        cmd: List<String>,
        heuristicsFallback: ((List<String>) -> ExecVerdict)? = null
    ): ExecEvaluation {
        val matched = matchExact(cmd)
        if (matched.isNotEmpty()) return ExecEvaluation(maxVerdict(matched), matched)
        if (heuristicsFallback != null) {
            val verdict = heuristicsFallback(cmd)
            return ExecEvaluation(verdict, listOf(ExecRuleMatch.Heuristics(cmd.toList(), verdict)))
        }
        // 无命中无回退:调用方决定默认(本函数不擅自 Allow,也不 Forbidden,只报空)。
        return ExecEvaluation(ExecVerdict.PROMPT, emptyList())
    }

    /** 多条命令一起求值,取最严(对应 Codex check_multiple)。 */
    fun checkMultiple(
        commands: List<List<String>>,
        heuristicsFallback: ((List<String>) -> ExecVerdict)? = null
    ): ExecEvaluation {
        val all = commands.flatMap { matchExact(it) }
        if (all.isNotEmpty()) return ExecEvaluation(maxVerdict(all), all)
        if (heuristicsFallback != null) {
            // 回退逐条算,整体仍取最严。
            val backs = commands.map { ExecRuleMatch.Heuristics(it.toList(), heuristicsFallback(it)) }
            return ExecEvaluation(maxVerdict(backs), backs)
        }
        return ExecEvaluation(ExecVerdict.PROMPT, emptyList())
    }

    private fun matchExact(cmd: List<String>): List<ExecRuleMatch> {
        val first = cmd.firstOrNull() ?: return emptyList()
        return rulesByProgram[first].orEmpty().mapNotNull { rule ->
            rule.pattern.matchesPrefix(cmd)?.let {
                ExecRuleMatch.Prefix(it, rule.verdict, rule.justification)
            }
        }
    }

    private fun maxVerdict(matches: List<ExecRuleMatch>): ExecVerdict =
        matches.maxOf { it.verdict() }

    /**
     * 策略自检(移植自 Codex validate_match/not_match_examples,去掉 host 解析部分):
     * - match 里的每条示例必须至少命中一条真实规则,否则抛(规则写漏了);
     * - notMatch 里的示例一条都不许命中,否则抛(规则写宽了)。
     * 策略加载时调一次,错配在启动期暴露,不在线上爆。
     */
    fun validateExamples(match: List<List<String>>, notMatch: List<List<String>>) {
        val unmatched = match.filter { matchExact(it).isEmpty() }
        require(unmatched.isEmpty()) {
            "策略自检失败:这些正例没有任何规则命中(规则写漏了):$unmatched"
        }
        val wronglyMatched = notMatch.firstOrNull { matchExact(it).isNotEmpty() }
        require(wronglyMatched == null) {
            "策略自检失败:这条反例被规则命中了(规则写宽了):$wronglyMatched"
        }
    }

    /** 叠加合并:对方规则追加进来(对应 Codex merge_overlay,后加的不覆盖先加的,只并存)。 */
    fun mergeOverlay(overlay: ExecPolicy): ExecPolicy {
        val merged = ExecPolicy()
        for ((program, rules) in rulesByProgram) merged.rulesByProgram[program] = rules.toMutableList()
        for ((program, rules) in overlay.rulesByProgram) {
            merged.rulesByProgram.getOrPut(program) { mutableListOf() }.addAll(rules)
        }
        merged.networkRules.addAll(networkRules)
        merged.networkRules.addAll(overlay.networkRules)
        return merged
    }

    /** 所有 Allow 前缀(渲染后),供“允许集”展示/审计。 */
    fun getAllowedPrefixes(): List<List<String>> =
        rulesByProgram.values.flatten()
            .filter { it.verdict == ExecVerdict.ALLOW }
            .map { rule ->
                listOf(rule.pattern.first) + rule.pattern.rest.map { it.toString() }
            }
            .distinct()
            .sortedBy { it.joinToString(" ") }

    /**
     * 编译域名黑白名单(对应 Codex compiled_network_domains,供 NetGuard 类出站守卫消费):
     * Allow 进白(同时从黑里摘掉),Forbidden 进黑(同时从白里摘掉),PROMPT 两边都不进。
     */
    fun compiledNetworkDomains(): Pair<List<String>, List<String>> {
        val allowed = mutableListOf<String>()
        val denied = mutableListOf<String>()
        for (rule in networkRules) {
            when (rule.verdict) {
                ExecVerdict.ALLOW -> {
                    denied.remove(rule.host)
                    if (!allowed.contains(rule.host)) allowed.add(rule.host)
                }
                ExecVerdict.FORBIDDEN -> {
                    allowed.remove(rule.host)
                    if (!denied.contains(rule.host)) denied.add(rule.host)
                }
                ExecVerdict.PROMPT -> {}
            }
        }
        return allowed to denied
    }

    fun networkRules(): List<NetworkRule> = networkRules.toList()
}

/**
 * 把 shell 命令行切成词(argv 形状,供 check 用)。
 * 支持单/双引号与反斜杠转义;引号不配对则按原样收尾(宁可多判,不漏判)。
 */
fun tokenizeCommand(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote = '\u0000'
    var i = 0
    var hasToken = false
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '\\' && i + 1 < raw.length -> {
                cur.append(raw[i + 1]); i += 2; hasToken = true; continue
            }
            quote != '\u0000' -> {
                if (c == quote) quote = '\u0000' else { cur.append(c); hasToken = true }
            }
            c == '\'' || c == '"' -> quote = c
            c.isWhitespace() -> {
                if (hasToken || cur.isNotEmpty()) { out.add(cur.toString()); cur.clear(); hasToken = false }
            }
            else -> { cur.append(c); hasToken = true }
        }
        i++
    }
    if (hasToken || cur.isNotEmpty()) out.add(cur.toString())
    return out
}
