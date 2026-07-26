package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.tools.CodeGraphNative
import com.xincode.tools.WorkspaceContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * `code_graph` —— 查代码结构,不用把文件读进上下文。
 *
 * ## 它替代什么
 *
 * 以前要回答「`parseMarkdownBlocks` 在哪定义的、谁调用了它」,agent 得:
 * grep 一遍 → 拿到十几个命中 → 逐个 `file_read` → 在上下文里堆几千行代码。
 * 现在一次查询就出 `file:line` + 签名,只有真要看实现时才读文件。
 *
 * 手机上这个差别尤其大:上下文窗口本来就紧,每读一个文件都在挤占后面的空间。
 *
 * ## 和 grep 的分工
 *
 * grep 找的是**文本**,注释里、字符串里的同名词都会命中,而且给不出「A 调用了 B」
 * 这种结构关系。这个工具查的是 **AST 抽出来的符号和关系**,准确但只覆盖已索引的
 * 语言和文件。所以两个都留着:结构问题问这个,找字面量还是 grep。
 */
class CodeGraphTool(
    private val database: AppDatabase
) : Tool {

    override val name = "code_graph"

    override val description =
        "查代码结构:符号定义在哪、谁调用了它、它依赖谁、某个文件里有什么。" +
            "比 grep 准(基于语法树,不会命中注释和字符串里的同名词),而且不用把文件读进上下文。" +
            "回答「这个函数在哪」「改它会影响什么」「这个文件都有些什么」时优先用它;" +
            "找字面字符串或配置值仍然用 grep。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description",
                    "define=找定义 | callers=谁引用了它 | callees=它引用了谁 | file=列出某文件的符号 | status=索引状态")
                put("enum", JSONArray(listOf("define", "callers", "callees", "file", "status")))
            })
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", "符号名。action 为 define/callers/callees 时必填")
            })
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "文件路径。action=file 时必填")
            })
        })
        put("required", JSONArray().apply { put("action") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        if (!CodeGraphNative.available) {
            return ToolResult.Error("代码索引内核不可用(可能是设备架构不支持),请改用 grep")
        }
        val dao = database.codeIndexDao()
        val root = WorkspaceContext.workspaceRoot

        return when (params["action"]) {
            "status" -> {
                val files = dao.fileCount(root)
                val syms = dao.symbolCount(root)
                if (files == 0) {
                    ToolResult.Success("这个工作区还没建索引($root)。在设置的「代码索引」里建一次。")
                } else {
                    ToolResult.Success("已索引 $files 个文件,$syms 个符号。工作区:$root")
                }
            }

            "define" -> {
                val n = params["name"]?.trim().orEmpty()
                if (n.isBlank()) return ToolResult.Error("action=define 需要 name")
                val hits = dao.findByName(root, n)
                if (hits.isEmpty()) {
                    // 说清楚是「没索引」还是「索引了但没有」—— 两种情况下一步动作不同
                    val total = dao.symbolCount(root)
                    return if (total == 0)
                        ToolResult.Error("这个工作区没有索引,先去设置里建索引,或者改用 grep")
                    else
                        ToolResult.Success("索引里没有「$n」。可能是拼写不同、在未索引的语言里,或者本来就不存在。")
                }
                ToolResult.Success(buildString {
                    append("找到 ${hits.size} 处定义:\n")
                    hits.forEach { s ->
                        append("- ${s.kind} ${s.name}")
                        if (s.qualifiedName.isNotBlank()) append("  (${s.qualifiedName})")
                        append("\n  ${s.filePath}:${s.startLine}")
                        if (s.endLine > s.startLine) append("-${s.endLine}")
                        if (s.signature.isNotBlank()) append("\n  ${s.signature}")
                        append("\n")
                    }
                })
            }

            "callers" -> {
                val n = params["name"]?.trim().orEmpty()
                if (n.isBlank()) return ToolResult.Error("action=callers 需要 name")
                val hits = dao.callersOf(root, n)
                if (hits.isEmpty()) return ToolResult.Success("索引里没有引用「$n」的地方。")
                ToolResult.Success(buildString {
                    append("${hits.size} 处引用了「$n」:\n")
                    hits.forEach { e -> append("- ${e.kind}  ${e.filePath}:${e.line}\n") }
                })
            }

            "callees" -> {
                val n = params["name"]?.trim().orEmpty()
                if (n.isBlank()) return ToolResult.Error("action=callees 需要 name")
                val hits = dao.calleesOf(root, n)
                if (hits.isEmpty()) return ToolResult.Success("索引里没有「$n」引用别人的记录。")
                ToolResult.Success(buildString {
                    append("「$n」引用了:\n")
                    hits.forEach { e -> append("- ${e.kind} → ${e.toName}  (:${e.line})\n") }
                })
            }

            "file" -> {
                val p = params["path"]?.trim().orEmpty()
                if (p.isBlank()) return ToolResult.Error("action=file 需要 path")
                val resolved = com.xincode.tools.PathResolver.resolve(p) ?: p
                val syms = dao.symbolsOf(resolved)
                if (syms.isEmpty()) return ToolResult.Success("这个文件没有索引记录:$p")
                ToolResult.Success(buildString {
                    append("$p 里的符号(${syms.size} 个):\n")
                    syms.forEach { s ->
                        append("- ${s.startLine}: ${s.kind} ${s.name}")
                        if (s.signature.isNotBlank()) append("  ${s.signature}")
                        append("\n")
                    }
                })
            }

            else -> ToolResult.Error("action 必须是 define / callers / callees / file / status 之一")
        }
    }
}
