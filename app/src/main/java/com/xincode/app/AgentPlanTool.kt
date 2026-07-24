package com.xincode.app

import com.xincode.core.Tool
import com.xincode.core.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * `agent_plan` — the AI's live task-list surface. The model calls this to publish and
 * update a plan the user can watch tick through in real time.
 *
 * Operations (`op` argument):
 *  - `set` + `title` + `steps: [String]` — commit a fresh plan of N steps
 *  - `advance` — mark the first PENDING step as IN_PROGRESS, returns its id
 *  - `done` + `id` — mark step [id] as DONE
 *  - `fail` + `id` — mark step [id] as FAILED (agent hit a blocker)
 *  - `reset` — clear the plan card
 *
 * Returned string is fed back to the model so it knows the plan was accepted.
 *
 * Multi-step planning is one of the highest-leverage things an agent can do — this
 * gives the model a first-class UI surface for it, not just words buried in a reply.
 */
class AgentPlanTool(private val planState: PlanState) : Tool {
    override val name = "agent_plan"
    override val description =
        "维护你的实时任务清单，用户能在界面上看到每一步进展。" +
            "面对多步骤任务时，先用 op=set 提交完整清单（3-8 步为宜），执行每步前调用 op=advance，" +
            "完成后调用 op=done 传入该步的 id。全部完成或任务已废弃时调用 op=reset。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("op", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("set", "advance", "done", "fail", "reset")))
                put("description", "操作类型")
            })
            put("title", JSONObject().apply {
                put("type", "string")
                put("description", "op=set 时的清单标题，例如 '重构登录模块'")
            })
            put("steps", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "op=set 时的步骤文本数组，每条一步；3-8 步为宜")
            })
            put("id", JSONObject().apply {
                put("type", "integer")
                put("description", "op=done/fail 时目标步骤的 1-based id")
            })
        })
        put("required", JSONArray(listOf("op")))
    }

    // 类型保真:直接从 JSONObject 读 steps 数组(避免默认压平成字符串再解析的脆弱路径)。
    override suspend fun executeJson(args: org.json.JSONObject): ToolResult {
        if (args.optString("op").trim() == "set") {
            val title = args.optString("title", "")
            val arr = args.optJSONArray("steps")
            val steps = if (arr != null) (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
            else parseStringArray(args.optString("steps", ""))
            return if (steps.isEmpty()) ToolResult.Error("agent_plan: steps 为空")
            else { planState.setPlan(title, steps); ToolResult.Success("已发布计划 (${steps.size} 步)") }
        }
        return super.executeJson(args)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val op = params["op"]?.trim().orEmpty()
        return when (op) {
            "set" -> {
                val title = params["title"].orEmpty()
                val stepsRaw = params["steps"].orEmpty()
                val steps = parseStringArray(stepsRaw)
                if (steps.isEmpty()) {
                    ToolResult.Error("agent_plan: steps 为空或解析失败: $stepsRaw")
                } else {
                    planState.setPlan(title, steps)
                    ToolResult.Success("已发布计划 (${steps.size} 步): ${steps.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")}")
                }
            }
            "advance" -> {
                val id = planState.advance()
                if (id < 0) ToolResult.Success("没有可推进的步骤（全部已完成或未设置计划）")
                else ToolResult.Success("步骤 $id 已开始 (in_progress)")
            }
            "done" -> {
                val id = params["id"]?.toIntOrNull()
                    ?: return ToolResult.Error("agent_plan: op=done 需要 id 参数")
                planState.updateStep(id, PlanStepStatus.DONE)
                val done = planState.doneCount()
                val total = planState.totalCount()
                ToolResult.Success("步骤 $id 完成 ($done/$total)")
            }
            "fail" -> {
                val id = params["id"]?.toIntOrNull()
                    ?: return ToolResult.Error("agent_plan: op=fail 需要 id 参数")
                planState.updateStep(id, PlanStepStatus.FAILED)
                ToolResult.Success("步骤 $id 已标记为失败")
            }
            "reset" -> {
                planState.reset()
                ToolResult.Success("计划已清空")
            }
            else -> ToolResult.Error("agent_plan: 未知操作 '$op'（可用: set/advance/done/fail/reset）")
        }
    }

    private fun parseStringArray(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        // Model may pass either JSON array `["a","b"]` or already-decoded plain string
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            // Fallback: comma or newline separated
            raw.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
