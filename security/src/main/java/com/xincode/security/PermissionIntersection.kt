package com.xincode.security

/**
 * 权限求交 —— 移植自 OpenAI Codex。
 *
 * Ported from https://github.com/openai/codex (Apache-2.0):
 * - codex-rs/protocol/src/permission_profile_intersection.rs (求交语义与 fail-closed 原则)
 *
 * 核心语义(逐条对应上游):
 * 1. 求交只收紧不放松:结果同时满足两边,任一边不允许即不允许。
 * 2. 不支持的形状直接失败 closed(上游:External/PlatformDefaults/未物化 glob → Err;
 *    这里:通配以外的正则/回溯路径 → Unfit,调用方按拒绝处理)。
 * 3. 网络取 AND:两边都开才开(上游 NetworkSandboxPolicy::Enabled 条件一致)。
 * 4. Disabled+Disabled→Disabled;两边相等→原样;一边 Unrestricted→取另一边。
 * 5. 路径先归一化再比较,符号链接/“..”不能凭空变出权限(上游 canonicalize 对应)。
 *
 * XINCODE 落地形态:文件(路径前缀)+网络两维;工具×参数第三维由调用方
 * (SecurityGate classify)先收敛成“路径/域名”,再进求交。
 */

// ---- 模型 ----

/** 文件访问位。 */
enum class FsAccess {
    READ,
    READ_WRITE;

    fun allowsWrite() = this == READ_WRITE

    /** 求交:任一 READ 即 READ。 */
    fun intersect(other: FsAccess) = if (this == READ_WRITE && other == READ_WRITE) READ_WRITE else READ
}

enum class FsPolicyKind { UNRESTRICTED, RESTRICTED }

/** 一条文件授权:路径前缀 + 访问位。deny 由“不在授权内”表达(默认拒绝)。 */
data class FsGrant(val pathPrefix: String, val access: FsAccess)

/** 文件沙箱策略。 */
data class FsPolicy(val kind: FsPolicyKind, val grants: List<FsGrant>) {
    companion object {
        fun unrestricted() = FsPolicy(FsPolicyKind.UNRESTRICTED, emptyList())
        fun restricted(vararg grants: FsGrant) = FsPolicy(FsPolicyKind.RESTRICTED, grants.toList())
    }
}

enum class NetPolicy { ENABLED, RESTRICTED }

/** 一份权限剖面:文件 + 网络。 */
data class PermissionProfile(val fs: FsPolicy, val net: NetPolicy) {
    companion object {
        /** 全开(仅 as 求交输入的一边有意义,绝不能直接授予)。 */
        fun unrestricted() = PermissionProfile(FsPolicy.unrestricted(), NetPolicy.ENABLED)

        /** 只读工作区(最常用的收敛结果形状)。 */
        fun readOnlyWorkspace(workspaceRoot: String) = PermissionProfile(
            FsPolicy.restricted(FsGrant(normalizeFsPath(workspaceRoot), FsAccess.READ)),
            NetPolicy.RESTRICTED
        )

        /** 可写工作区(对应 Codex :workspace 内涵:可改检出,不出网)。 */
        fun workspace(workspaceRoot: String) = PermissionProfile(
            FsPolicy.restricted(FsGrant(normalizeFsPath(workspaceRoot), FsAccess.READ_WRITE)),
            NetPolicy.RESTRICTED
        )
    }
}

/** 求交结果:Ok(收紧后的剖面) / Unfit(形状不支持,调用方必须按拒绝处理 = fail closed)。 */
sealed class IntersectionResult {
    data class Ok(val profile: PermissionProfile) : IntersectionResult()
    data class Unfit(val reason: String) : IntersectionResult()
}

/**
 * 路径归一化(对应上游 canonicalize + UnsupportedPath 校验的轻量版):
 * 反斜杠转正、去重复分隔、解“.”、拒绝“..”越界与空路径。符号链接不解析
 * (Android 上 /sdcard 这类马甲太多,解析反而制造“两边看到不同路径”的歧义;
 * 调用方应在座舱/工作区入口处先 realpath,本函数对含 link 的前缀一律按字面比较)。
 */
fun normalizeFsPath(raw: String): String {
    val p = raw.trim().replace('\\', '/')
    require(p.isNotEmpty()) { "路径不能为空" }
    val parts = mutableListOf<String>()
    for (seg in p.split('/')) {
        when {
            seg.isEmpty() || seg == "." -> {}
            seg == ".." -> {
                require(parts.isNotEmpty() && !(parts.size == 1 && parts[0].isEmpty())) {
                    "路径越界(.. 逃出根,得 $raw)"
                }
                parts.removeAt(parts.lastIndex)
            }
            else -> parts.add(seg)
        }
    }
    val rooted = p.startsWith("/")
    val joined = parts.joinToString("/")
    return (if (rooted) "/" else "") + joined
}

/** prefix 是否覆盖 path(边界对齐:/a/b 覆盖 /a/b/c,不覆盖 /a/bc)。 */
fun prefixCovers(prefix: String, path: String): Boolean {
    if (prefix == "/") return path.startsWith("/")
    if (path == prefix) return true
    return path.startsWith(prefix.trimEnd('/') + "/")
}

/**
 * 求交(对应上游 intersect_effective_permission_profiles)。
 *
 * @param authority 系统侧权威(如工作区围栏,地位 = 上游 authority)。
 * @param requested 任务侧请求(如审批放行范围,地位 = 上游 requested)。
 */
fun intersectProfiles(authority: PermissionProfile, requested: PermissionProfile): IntersectionResult {
    // 网络 AND:两边都开才开。
    val net = if (authority.net == NetPolicy.ENABLED && requested.net == NetPolicy.ENABLED) {
        NetPolicy.ENABLED
    } else {
        NetPolicy.RESTRICTED
    }
    // Disabled 在本模型里 = RESTRICTED + 零授权,自然落到下面的通用路径,无需特判。
    if (authority.fs == requested.fs) {
        return IntersectionResult.Ok(PermissionProfile(authority.fs, net))
    }
    if (authority.fs.kind == FsPolicyKind.UNRESTRICTED) {
        return IntersectionResult.Ok(PermissionProfile(requested.fs, net))
    }
    if (requested.fs.kind == FsPolicyKind.UNRESTRICTED) {
        return IntersectionResult.Ok(PermissionProfile(authority.fs, net))
    }
    // 两边都 RESTRICTED:授权取“同时被两边覆盖”的最长公共约束。
    // 实现:对每一对 (a, r),若一方覆盖另一方,则交集取被覆盖者的路径、访问位取 min。
    val out = mutableListOf<FsGrant>()
    for (a in authority.fs.grants) {
        for (r in requested.fs.grants) {
            val an = normalizeFsPath(a.pathPrefix)
            val rn = normalizeFsPath(r.pathPrefix)
            val covered: String? = when {
                prefixCovers(an, rn) -> rn
                prefixCovers(rn, an) -> an
                else -> null
            }
            if (covered != null) {
                val access = a.access.intersect(r.access)
                // 同路径只留最严。
                val existing = out.indexOfFirst { it.pathPrefix == covered }
                if (existing < 0) out.add(FsGrant(covered, access))
                else if (access == FsAccess.READ) out[existing] = out[existing].copy(access = FsAccess.READ)
            }
        }
    }
    out.sortBy { it.pathPrefix }
    return IntersectionResult.Ok(PermissionProfile(FsPolicy(FsPolicyKind.RESTRICTED, out), net))
}

/** 用剖面判定单次访问(默认拒绝:不在任何授权前缀内即拒)。 */
fun PermissionProfile.allows(path: String, write: Boolean): Boolean {
    if (fs.kind == FsPolicyKind.UNRESTRICTED) return true
    val p = normalizeFsPath(path)
    return fs.grants.any { prefixCovers(normalizeFsPath(it.pathPrefix), p) && (!write || it.access.allowsWrite()) }
}
