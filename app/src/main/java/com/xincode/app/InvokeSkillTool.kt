package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import com.xincode.data.AppDatabase
import com.xincode.data.SkillEntity
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

    /**
     * 技能被命中后回调(已在工具内累计 useCount/lastUsedAt)。
     * XincodeApplication 用它触发「用后自改进」;复盘分身的注册表不挂,避免递归。
     */
    var onSkillUsed: (suspend (SkillEntity) -> Unit)? = null

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
        val skillName = (params["name"] ?: params["skill"] ?: params["skill_name"])?.trim()
            ?.removePrefix("/")   // 用户约定用 `/技能名` 触发,模型经常把斜杠一起带进来
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.Error("缺少 name 参数")

        // 精确名 → 忽略大小写/空格的近似名。
        //
        // 【为什么要放宽】实测模型会把界面上的功能开关当成技能来调,比如 invoke_skill("联网搜索")
        // ——那是输入框「+」里的联网总开关,不是技能。原来只回一句「未找到技能」,不告诉它到底
        // 有哪些,它就只能接着猜,猜到被防空转刹车掐掉。把现有技能名列出来,一次就能改对。
        val skill = database.skillDao().getByName(skillName)
            ?: findLoosely(skillName)
            ?: return@withContext ToolResult.Error(
                "未找到技能: \"$skillName\"。" + availableSkillsHint()
            )

        val output = buildString {
            appendLine("# Skill: ${skill.name}")
            if (skill.description.isNotBlank()) {
                appendLine("> ${skill.description}")
            }
            appendLine()
            append(skill.content)
        }

        // 用量生命周期:命中即累计 + 复活,供 curator 与自改进使用。
        database.skillDao().incrementUsage(skill.id, System.currentTimeMillis())
        onSkillUsed?.invoke(skill)

        ToolResult.Success(output)
    }

    /** 忽略大小写/空格/连字符后再找一次；只有唯一命中才认，不唯一就当没找到，宁可报错也不猜错技能。 */
    private suspend fun findLoosely(name: String): com.xincode.data.SkillEntity? {
        val want = normalize(name)
        if (want.isEmpty()) return null
        val all = runCatching { database.skillDao().getAll() }.getOrNull() ?: return null
        return all.filter { normalize(it.name) == want }.singleOrNull()
    }

    private fun normalize(s: String): String =
        s.filter { !it.isWhitespace() && it != '-' && it != '_' }.lowercase()

    /** 把现有技能名附在错误后面，让模型一次就能改对而不是接着猜。 */
    private suspend fun availableSkillsHint(): String {
        val names = runCatching { database.skillDao().getAll().map { it.name } }.getOrNull().orEmpty()
        if (names.isEmpty()) return "当前没有安装任何技能，别再调用 invoke_skill 了。"
        return "现有技能只有这些：${names.joinToString("、")}。" +
            "注意「联网搜索」「深度分析」这类是界面上的功能开关，不是技能，调用不到。"
    }
}
