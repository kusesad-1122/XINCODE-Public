package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 删除文件或目录。
 *
 * 之前只能靠 `shell_exec` 跑 `rm`。做成独立工具的好处是删除意图变得显式:
 * 参数就是一个路径,不会出现模型拼 shell 字符串时把 `-rf` 和别的参数粘在一起
 * 这类事故,危险操作也能被 SecurityGate 按工具名单独识别,而不是混在所有 shell 里。
 *
 * 注意 [PathResolver] 按项目既定设计【不是沙箱】:绝对路径可以指向工作区外。
 * 真正的把关在 SecurityGate 和 Android 权限,这里只挡住工作区根本身。
 */
class DeleteFileTool : Tool {

    override val name = "delete_file"
    override val description = "删除文件或目录。目录非空时必须显式传 recursive=true。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "要删除的文件或目录路径(相对路径以工作区为基准)")
            })
            put("recursive", JSONObject().apply {
                put("type", "boolean")
                put("description", "删除非空目录时必须为 true。默认 false。")
            })
        })
        put("required", JSONArray().apply { put("path") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"] ?: return@withContext ToolResult.Error("缺少 path 参数")
        val safePath = PathResolver.resolve(path)
            ?: return@withContext ToolResult.Error("无法解析路径: $path")
        // 不让 AI 改 App 自己的运行时数据 —— 动了 databases/ 下次启动就打不开库,
        // 用户的会话、身份卡、供应商配置、记忆全没。见 SelfProtect。
        SelfProtect.refuse(safePath)?.let { return@withContext ToolResult.Error(it) }

        val file = File(safePath)
        if (!file.exists()) return@withContext ToolResult.Error("不存在: $path")

        // 工作区根本身不能删:删掉之后所有工具的相对路径解析全部失效,
        // 而且这几乎不可能是本意。
        val root = runCatching { File(PathResolver.WORKSPACE_ROOT).canonicalPath }.getOrNull()
        if (root != null && runCatching { file.canonicalPath }.getOrNull() == root) {
            return@withContext ToolResult.Error("不能删除工作区根目录")
        }

        val recursive = params["recursive"]?.toBooleanStrictOrNull() ?: false

        try {
            if (file.isDirectory) {
                val children = file.list()?.size ?: 0
                if (children > 0 && !recursive) {
                    return@withContext ToolResult.Error(
                        "目录非空($children 项),要删请显式传 recursive=true: $path"
                    )
                }
                val ok = if (recursive) file.deleteRecursively() else file.delete()
                if (!ok) return@withContext ToolResult.Error("删除失败: $path")
                ToolResult.Success("已删除目录: $path")
            } else {
                if (!file.delete()) return@withContext ToolResult.Error("删除失败: $path")
                ToolResult.Success("已删除文件: $path")
            }
        } catch (e: Exception) {
            ToolResult.Error("删除出错: ${e.message}")
        }
    }
}

/**
 * 创建目录(含中间层级)。
 *
 * 单独做一个而不是让模型用 file_write 顺带建,是因为「先把目录结构搭好」经常
 * 本身就是一步 —— 尤其是准备工程骨架的时候。
 */
class MakeDirectoryTool : Tool {

    override val name = "make_directory"
    override val description = "创建目录,自动创建中间层级(相当于 mkdir -p)。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "要创建的目录路径(相对路径以工作区为基准)")
            })
        })
        put("required", JSONArray().apply { put("path") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"] ?: return@withContext ToolResult.Error("缺少 path 参数")
        val safePath = PathResolver.resolve(path)
            ?: return@withContext ToolResult.Error("无法解析路径: $path")
        // 不让 AI 改 App 自己的运行时数据 —— 动了 databases/ 下次启动就打不开库,
        // 用户的会话、身份卡、供应商配置、记忆全没。见 SelfProtect。
        SelfProtect.refuse(safePath)?.let { return@withContext ToolResult.Error(it) }

        val dir = File(safePath)
        // 已存在且就是目录时按成功返回 —— 这个操作本来就该幂等,
        // 报错会让模型以为出了问题然后开始绕路。
        if (dir.isDirectory) return@withContext ToolResult.Success("目录已存在: $path")
        if (dir.exists()) return@withContext ToolResult.Error("同名文件已存在,无法建目录: $path")

        return@withContext if (dir.mkdirs()) ToolResult.Success("已创建目录: $path")
        else ToolResult.Error("创建目录失败: $path")
    }
}
