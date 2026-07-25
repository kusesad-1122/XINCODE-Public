package com.xincode.app

import org.json.JSONObject

/**
 * 工具调用的「人话」标签(思路取自 hermes `agent/display.py` 的 _TOOL_VERBS / build_tool_label)。
 *
 * 目的:时间线里的每一步应当读起来像一句话——「读取 SettingsScreen.kt」「搜索网页:天气」,
 * 而不是抛出裸工具名与一坨 JSON 参数。
 *
 * 三层结构:
 *  1. [verbOf]     工具 → 中文动词
 *  2. [previewOf]  参数 → 一行摘要(按工具挑最有信息量的字段,而非整串 JSON)
 *  3. [labelOf]    动词 + 连接词 + 摘要,拼成最终展示串
 */
object ToolLabels {

    /** 工具 → 动词。未收录的(MCP/自定义工具)回落到工具名本身。 */
    private val VERBS: Map<String, String> = mapOf(
        "web_search" to "搜索网页",
        "web_fetch" to "抓取网页",
        "file_read" to "读取",
        "file_write" to "写入",
        "file_edit" to "编辑",
        "multi_edit" to "批量编辑",
        "list_dir" to "列出目录",
        "glob" to "查找文件",
        "grep" to "搜索内容",
        "shell_exec" to "执行",
        "su_exec" to "root 执行",
        "env_exec" to "环境内执行",
        "execute_code" to "运行脚本",
        "recall_memory" to "检索记忆",
        "save_memory" to "记住",
        "invoke_skill" to "调用技能",
        "skill_manage" to "管理技能",
        "agent_plan" to "制定计划",
        "dispatch_agents" to "派发子智能体",
        "wolfpack_run" to "并行编排",
        "cronjob" to "设定定时任务",
        "current_time" to "查询当前时间",
        "describe_image" to "识别图片",
        "ask_reasoning" to "深度推理",
        "translate_text" to "翻译",
        "transcribe_audio" to "语音转写"
    )

    /** 这些动词单独成句更自然,不再追加参数摘要。 */
    private val NO_PREVIEW = setOf("current_time", "agent_plan")

    /** 搜索类:用「:」连接读起来更顺(搜索网页:天气)。 */
    private val COLON_CONNECTOR = setOf("web_search", "grep", "glob", "recall_memory")

    /** 每个工具优先展示的参数字段(按顺序取第一个非空的)。 */
    private val PREVIEW_KEYS: Map<String, List<String>> = mapOf(
        "web_search" to listOf("query", "q"),
        "web_fetch" to listOf("url"),
        "file_read" to listOf("path", "file_path"),
        "file_write" to listOf("path", "file_path"),
        "file_edit" to listOf("path", "file_path"),
        "multi_edit" to listOf("path", "file_path"),
        "list_dir" to listOf("path", "dir"),
        "glob" to listOf("pattern"),
        "grep" to listOf("pattern", "query"),
        "shell_exec" to listOf("command", "cmd"),
        "su_exec" to listOf("command", "cmd"),
        "env_exec" to listOf("command", "cmd"),
        "recall_memory" to listOf("query"),
        "save_memory" to listOf("title", "content"),
        "invoke_skill" to listOf("name"),
        "skill_manage" to listOf("name"),
        "cronjob" to listOf("schedule", "prompt"),
        "describe_image" to listOf("path", "url"),
        "ask_reasoning" to listOf("question", "prompt"),
        "translate_text" to listOf("text"),
        "dispatch_agents" to listOf("task", "tasks")
    )

    private const val MAX_PREVIEW = 60

    fun verbOf(toolName: String): String = VERBS[toolName] ?: toolName

    /**
     * 参数摘要:优先取该工具最有信息量的字段;文件路径只留文件名,shell 命令只留主命令,
     * 避免一长串路径/管道把时间线撑爆。
     */
    fun previewOf(toolName: String, argumentsJson: String): String {
        if (toolName in NO_PREVIEW) return ""
        val obj = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return ""

        val keys = PREVIEW_KEYS[toolName]
            ?: obj.keys().asSequence().toList()   // 未收录工具:用第一个字符串参数兜底
        var raw = ""
        for (k in keys) {
            val v = obj.opt(k) ?: continue
            val s = if (v is JSONObject || v is org.json.JSONArray) v.toString() else v.toString()
            if (s.isNotBlank() && s != "null") { raw = s; break }
        }
        if (raw.isBlank()) return ""

        val shaped = when (toolName) {
            "shell_exec", "su_exec", "env_exec" -> summarizeCommand(raw)
            "file_read", "file_write", "file_edit", "multi_edit", "list_dir", "describe_image" -> shortPath(raw)
            else -> raw
        }
        return oneLine(shaped).take(MAX_PREVIEW)
    }

    /** 最终标签:动词 + 摘要。未收录工具直接「工具名 摘要」。 */
    fun labelOf(toolName: String, argumentsJson: String): String {
        val verb = verbOf(toolName)
        val preview = previewOf(toolName, argumentsJson)
        if (preview.isBlank()) return verb
        return if (toolName in COLON_CONNECTOR) "$verb:$preview" else "$verb $preview"
    }

    /** 路径只保留最后一段文件名(带上一级目录更好认时保留一级)。 */
    private fun shortPath(path: String): String {
        val p = path.trimEnd('/')
        val parts = p.split('/').filter { it.isNotBlank() }
        return when {
            parts.isEmpty() -> p
            parts.size == 1 -> parts[0]
            else -> parts.takeLast(2).joinToString("/")
        }
    }

    /**
     * shell 命令摘要:取管道/连接符前的第一段,并去掉 sudo 等前缀词,
     * 让「cat a.txt | grep x | wc -l」显示为「cat a.txt」。
     */
    private fun summarizeCommand(command: String): String {
        val firstSegment = command
            .split("&&", "||", "|", ";")
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        val words = firstSegment.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return oneLine(command)
        var i = 0
        while (i < words.size && words[i] in setOf("sudo", "su", "-c", "env", "nohup")) i++
        return words.drop(i).joinToString(" ").ifBlank { firstSegment }
    }

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()
}
