package com.xincode.tools

import java.io.File

/**
 * 路径解析:工作区是【相对路径基准 + 默认产出/写入目录】,**不是**硬性沙箱。
 *
 * 规则(按用户要求放宽):
 * - 相对路径(不以 / 开头)→ 以工作区根为基准解析(AI 的产出默认落这里)。
 * - 绝对路径 → 原样规范化,【允许】指向工作区之外(可读写工作区外的文件)。
 *   真正的写/危险操作由 SecurityGate + Android 权限把关,不在这里拦。
 */
object PathResolver {
    /** 当前工作区根:随 [WorkspaceContext.workspaceRoot](用户可在设置/项目里改)动态变化。 */
    val WORKSPACE_ROOT: String get() = WorkspaceContext.workspaceRoot

    /**
     * Resolve [raw] to an absolute, normalized path.
     * 相对路径以工作区为基准;绝对路径原样(允许工作区外)。规范化失败时回退原字符串。
     */
    fun resolve(raw: String): String? {
        val root = WORKSPACE_ROOT
        val absolute = if (raw.startsWith("/")) raw else "$root/$raw"
        return try { File(absolute).canonicalPath } catch (_: Exception) { absolute }
    }
}