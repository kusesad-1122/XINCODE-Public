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
 * Hermes-① `skill_manage` — 技能库写入(供「后台复盘分身」把经验固化成可复用技能,也可主对话用)。
 *
 * action:
 *  - view:读一个技能的完整内容(edit/patch 前**必须**先 view,见下)
 *  - create:新建技能(source=agent)
 *  - patch:外科式 find-replace(old_string→new_string,唯一匹配),优先用它做**增量改进**
 *  - edit:整体覆写 description/content
 *  - remove:删除
 *
 * 保护:
 *  - bundled(内置/SKILL.md 导入)技能只读——不可 patch/edit/remove。
 *  - 先读后写守卫:edit/patch 前必须在最近 [READ_TTL_MS] 内 view 过该技能,避免盲改。
 */
class SkillManageTool(private val database: AppDatabase) : Tool {

    companion object {
        private const val READ_TTL_MS = 5 * 60 * 1000L
    }

    /** 先读后写守卫:name → 最近 view 的时间戳。 */
    private val readAt = HashMap<String, Long>()

    override val name = "skill_manage"
    override val description =
        "管理可复用技能库。优先用 action=patch 对**当前用到的、已存在的**技能做外科式增量改进" +
        "(补一个坑、订正一步);只有当没有任何技能覆盖该类任务时才用 create 新建。" +
        "edit/patch 前必须先 view。内置(bundled)技能只读。不要用 PR 号/报错串命名,不要固化环境相关的失败。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("view", "create", "patch", "edit", "remove")))
            })
            put("name", JSONObject().apply { put("type", "string"); put("description", "技能名") })
            put("description", JSONObject().apply { put("type", "string"); put("description", "create/edit 用:简短描述") })
            put("content", JSONObject().apply { put("type", "string"); put("description", "create/edit 用:markdown 正文") })
            put("old_string", JSONObject().apply { put("type", "string"); put("description", "patch 用:被替换的唯一片段") })
            put("new_string", JSONObject().apply { put("type", "string"); put("description", "patch 用:替换后的片段") })
        })
        put("required", JSONArray(listOf("action", "name")))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.trim().orEmpty()
        val name = params["name"]?.trim().orEmpty()
        if (name.isEmpty()) return@withContext ToolResult.Error("缺少 name")
        val dao = database.skillDao()
        try {
            when (action) {
                "view" -> {
                    val s = dao.getByName(name) ?: return@withContext ToolResult.Error("未找到技能: $name")
                    readAt[name] = System.currentTimeMillis()
                    ToolResult.Success("# ${s.name} (source=${s.source})\n> ${s.description}\n\n${s.content}")
                }
                "create" -> {
                    if (dao.getByName(name) != null) return@withContext ToolResult.Error("技能已存在,请用 patch/edit 改进: $name")
                    val id = dao.upsert(SkillEntity(
                        name = name,
                        description = params["description"].orEmpty(),
                        content = params["content"].orEmpty(),
                        source = "agent"
                    ))
                    ToolResult.Success("已创建技能 $name (id=$id)")
                }
                "patch", "edit" -> {
                    val s = dao.getByName(name) ?: return@withContext ToolResult.Error("未找到技能: $name")
                    if (s.source == "bundled") return@withContext ToolResult.Error("内置技能只读,不能修改: $name")
                    val last = readAt[name] ?: 0
                    if (System.currentTimeMillis() - last > READ_TTL_MS) {
                        return@withContext ToolResult.Error("先读后写:请先用 action=view 读一遍 $name 的当前内容再修改")
                    }
                    if (action == "edit") {
                        val newContent = params["content"] ?: s.content
                        val newDesc = params["description"] ?: s.description
                        dao.upsert(s.copy(description = newDesc, content = newContent, updatedAt = System.currentTimeMillis()))
                        ToolResult.Success("已整体更新技能 $name")
                    } else {
                        val oldStr = params["old_string"].orEmpty()
                        val newStr = params["new_string"].orEmpty()
                        if (oldStr.isEmpty()) return@withContext ToolResult.Error("patch 需要 old_string")
                        val count = countOccurrences(s.content, oldStr)
                        if (count == 0) return@withContext ToolResult.Error("patch 未匹配到 old_string(内容可能已变,请重新 view)")
                        if (count > 1) return@withContext ToolResult.Error("patch 的 old_string 匹配 $count 处,不唯一;请扩大上下文")
                        val patched = s.content.replace(oldStr, newStr)
                        dao.upsert(s.copy(content = patched, updatedAt = System.currentTimeMillis()))
                        ToolResult.Success("已外科式更新技能 $name(1 处)")
                    }
                }
                "remove" -> {
                    val s = dao.getByName(name) ?: return@withContext ToolResult.Error("未找到技能: $name")
                    if (s.source == "bundled") return@withContext ToolResult.Error("内置技能只读,不能删除: $name")
                    dao.deleteById(s.id)
                    ToolResult.Success("已删除技能 $name")
                }
                else -> ToolResult.Error("未知 action: $action")
            }
        } catch (e: Exception) {
            ToolResult.Error("skill_manage 失败: ${e.message}")
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0; var idx = haystack.indexOf(needle)
        while (idx >= 0) { count++; idx = haystack.indexOf(needle, idx + needle.length) }
        return count
    }
}
