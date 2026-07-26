package com.xincode.tools

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * gap-02 批量原子编辑(对标 grok hashline_edit / codex apply_patch)。
 *
 * 对同一文件按顺序应用多个 (old_string→new_string) 编辑。全部在内存副本上先应用并逐个校验,
 * 只有【全部成功】才落盘;任一失败则整体回滚(文件保持原状),保证原子性。
 * 依赖 gap-05:override [executeJson] 读结构化 edits:JSONArray,不被压平成字符串。
 */
class MultiEditTool : Tool {

    override val name = "multi_edit"
    override val description =
        "对一个文件按顺序应用多处替换,原子提交:全部成功才写盘,任一失败整体回滚。" +
        "参数:path(文件路径)、edits(数组,每项 {old_string, new_string, replace_all?})。" +
        "适合一次改动同一文件的多个位置,比多次 file_edit 更安全。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply { put("type", "string"); put("description", "要编辑的文件路径(工作区内)") })
            put("edits", JSONObject().apply {
                put("type", "array")
                put("description", "编辑列表,按顺序应用")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("old_string", JSONObject().apply { put("type", "string") })
                        put("new_string", JSONObject().apply { put("type", "string") })
                        put("replace_all", JSONObject().apply { put("type", "boolean") })
                    })
                    put("required", JSONArray().apply { put("old_string"); put("new_string") })
                })
            })
        })
        put("required", JSONArray().apply { put("path"); put("edits") })
    }

    // 走结构化入口:必须能拿到 edits 的 JSONArray 原型。
    override suspend fun executeJson(args: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.optString("path").takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Error("缺少 path 参数")
        val edits = args.optJSONArray("edits")
            ?: return@withContext ToolResult.Error("缺少 edits 数组参数")
        if (edits.length() == 0) return@withContext ToolResult.Error("edits 为空")

        val safePath = PathResolver.resolve(path)
            ?: return@withContext ToolResult.Error("路径不在工作区内: $path")
        // 不让 AI 改 App 自己的运行时数据 —— 动了 databases/ 下次启动就打不开库,
        // 用户的会话、身份卡、供应商配置、记忆全没。见 SelfProtect。
        SelfProtect.refuse(safePath)?.let { return@withContext ToolResult.Error(it) }
        val file = File(safePath)
        if (!file.exists() || !file.isFile) return@withContext ToolResult.Error("文件不存在或不是普通文件: $path")

        var working = file.readText()
        for (i in 0 until edits.length()) {
            val e = edits.optJSONObject(i) ?: return@withContext ToolResult.Error("edits[$i] 不是对象")
            val oldStr = e.optString("old_string", "")
            val newStr = e.optString("new_string", "")
            val replaceAll = e.optBoolean("replace_all", false)
            if (oldStr.isEmpty()) return@withContext ToolResult.Error("edits[$i] 的 old_string 不能为空(multi_edit 用于修改已有文件)")
            if (oldStr == newStr) return@withContext ToolResult.Error("edits[$i] 的 old/new 相同")

            val count = countOccurrences(working, oldStr)
            when {
                count == 0 -> return@withContext ToolResult.Error("edits[$i]:未找到 old_string(在前 $i 处编辑应用后)。整体回滚,未写盘。")
                count > 1 && !replaceAll -> return@withContext ToolResult.Error("edits[$i]:old_string 出现 $count 次不唯一;扩大上下文或设 replace_all=true。整体回滚,未写盘。")
            }
            working = if (replaceAll) working.replace(oldStr, newStr) else working.replaceFirst(oldStr, newStr)
        }

        return@withContext try {
            file.writeText(working)
            ToolResult.Success("已原子应用 ${edits.length()} 处编辑 → $path(现 ${working.length} 字符)")
        } catch (ex: Exception) {
            ToolResult.Error("写入失败: ${ex.message}")
        }
    }

    // 兜底:直接 execute(压平)时也给出清晰错误引导走 executeJson。
    override suspend fun execute(params: Map<String, String>): ToolResult {
        val path = params["path"] ?: return ToolResult.Error("缺少 path 参数")
        val editsRaw = params["edits"] ?: return ToolResult.Error("缺少 edits 参数")
        return try {
            executeJson(JSONObject().put("path", path).put("edits", JSONArray(editsRaw)))
        } catch (e: Exception) {
            ToolResult.Error("edits 需为 JSON 数组: ${e.message}")
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var idx = haystack.indexOf(needle); var n = 0
        while (idx >= 0) { n++; idx = haystack.indexOf(needle, idx + needle.length) }
        return n
    }
}
