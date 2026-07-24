package com.xincode.app

import android.util.Log
import com.xincode.core.HookDispatcher
import com.xincode.data.AppDatabase
import com.xincode.tools.ShellExecTool
import com.xincode.tools.SuExecTool
import org.json.JSONObject

/**
 * gap-24 hooks 具体实现:据事件从 Room 取出已启用的 hook,按 matcher 过滤(pre/post_tool 按工具名前缀),
 * 做占位替换($TOOL/$ARGS/$STATUS/$PROMPT/$OUTPUT)后经(可选 root)shell 执行。best-effort,不阻断主流程。
 */
class HookDispatcherImpl(
    private val database: AppDatabase,
    private val shellExecTool: ShellExecTool,
    private val suExecTool: SuExecTool
) : HookDispatcher {

    companion object { private const val TAG = "Hooks" }

    override suspend fun dispatch(event: String, context: Map<String, String>) {
        val hooks = try { database.hookDao().getEnabledByEvent(event) } catch (_: Exception) { return }
        if (hooks.isEmpty()) return
        val tool = context["tool"] ?: ""
        for (h in hooks) {
            // pre/post_tool 的 matcher 按工具名前缀过滤(空=所有工具)。
            if ((event == "pre_tool" || event == "post_tool") && h.matcher.isNotBlank()
                && !tool.startsWith(h.matcher)) continue
            val cmd = substitute(h.command, context)
            if (cmd.isBlank()) continue
            try {
                val args = mapOf("command" to cmd)
                val res = if (h.runAsRoot) suExecTool.execute(args) else shellExecTool.execute(args)
                Log.i(TAG, "hook[$event] ran: ${cmd.take(80)} → $res")
            } catch (e: Exception) {
                Log.w(TAG, "hook[$event] failed: ${e.message}")
            }
        }
    }

    private fun substitute(command: String, ctx: Map<String, String>): String {
        var s = command
        s = s.replace("\$TOOL", ctx["tool"] ?: "")
        s = s.replace("\$ARGS", shellQuote(ctx["args"] ?: ""))
        s = s.replace("\$STATUS", ctx["status"] ?: "")
        s = s.replace("\$OUTPUT", shellQuote(ctx["output_head"] ?: ""))
        s = s.replace("\$PROMPT", shellQuote(ctx["prompt"] ?: ""))
        return s
    }

    // 单引号包裹并转义,避免 hook 占位注入。
    private fun shellQuote(v: String): String = "'" + v.replace("'", "'\\''") + "'"
}
