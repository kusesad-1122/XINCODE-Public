package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * `env_exec` —— 让 AI 在【内置 Ubuntu 环境(chroot)】里跑命令的终端工具,输出【实时镜像到可视终端】,
 * 这样用户能直接看到 AI 在操作什么(可视化 AI 终端)。环境未部署时返回提示。
 */
class EnvExecTool(private val terminal: TerminalState) : Tool {
    override val name = "env_exec"
    override val description =
        "在内置 Ubuntu 环境(apt/node/python/rust/go 等都在这里)执行一条 shell 命令并返回输出。" +
        "需要该 Linux 环境已部署(设置→环境配置→部署环境)。命令与输出会实时显示在可视终端里。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "要在 Ubuntu 环境里执行的 shell 命令")
            })
        })
        put("required", JSONArray().apply { put("command") })
    }

    // 环境未就绪时不暴露给模型(避免它调了却总失败)。
    override fun isAvailable(): Boolean = LinuxEnvironment.isReady()

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val cmd = params["command"]?.trim().orEmpty()
        if (cmd.isEmpty()) return ToolResult.Error("缺少 command 参数")
        if (!LinuxEnvironment.isReady()) {
            return ToolResult.Error("Linux 环境尚未部署,请先到 设置→环境配置 部署环境")
        }
        terminal.appendChunk("$ [AI] $cmd")
        val out = StringBuilder()
        val res = LinuxEnvironment.runInEnvStreaming(cmd) { line ->
            terminal.appendChunk(line)
            out.append(line).append('\n')
            if (out.length > 12000) out.delete(0, out.length - 8000)
        }
        terminal.appendChunk("[exit ${res.exitCode}]")
        val text = out.toString().trim().ifBlank { "(无输出)" }
        return if (res.exitCode == 0) ToolResult.Success(text)
        else ToolResult.Error("退出码 ${res.exitCode}\n$text")
    }
}
