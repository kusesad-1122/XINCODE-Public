package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tool that lets the model invoke a named skill.
 * When the model calls invoke_skill(name="xxx"), the tool reads the skill content
 * from Room and returns it, so the model can follow the skill's instructions.
 */
class InvokeSkillTool(private val database: AppDatabase) : Tool {

    override val name = "invoke_skill"

    override val description = "Invoke a named skill. Returns the skill's markdown instructions " +
            "so you can follow them. Use this when the user mentions a skill by name."

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", "The skill name to invoke (must match exactly)")
            })
        })
        put("required", JSONArray().apply { put("name") })
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val skillName = params["name"]
            ?: return@withContext ToolResult.Error("缺少 name 参数")

        val skill = database.skillDao().getByName(skillName)
            ?: return@withContext ToolResult.Error("未找到技能: \"$skillName\"。检查名称是否拼写正确。")

        val output = buildString {
            appendLine("# Skill: ${skill.name}")
            if (skill.description.isNotBlank()) {
                appendLine("> ${skill.description}")
            }
            appendLine()
            append(skill.content)
        }

        ToolResult.Success(output)
    }
}