package com.xincode.app

import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.McpServerEntity
import com.xincode.data.SkillEntity

/**
 * 首启种子:开箱自带 **5 个基础技能** + **2 个默认 MCP**(参考 Reasonix 内建技能与 Hermes/MCP 参考实现)。
 * 用 settings 里的 `defaults_seeded_v1` 标志保证只种一次(用户之后删了不会被重新塞回)。
 */
object DefaultsSeeder {
    private const val TAG = "DefaultsSeeder"
    private const val FLAG = "defaults_seeded_v2"

    suspend fun seedIfNeeded(db: AppDatabase) {
        // 【放在总开关之前】通用工作技能是后加的,而老用户的 FLAG 早就是 1 了 ——
        // 放进开关里面的话,所有老用户永远装不上这批,只有全新安装的人才有。
        // 它自己按名字查重,重复调用无副作用,每次启动跑一遍是安全的。
        runCatching { WorkSkills.install(db) }
            .onFailure { Log.w(TAG, "work skills seed failed: ${it.message}") }

        // 内置子智能体同样按名字查重、每次启动补齐:老用户误删后,升级版本会自动找回。
        // 与 WorkSkills 相同的幂等模式——重复调用无副作用。
        runCatching { seedSubAgents(db) }
            .onFailure { Log.w(TAG, "sub-agent seed failed: ${it.message}") }

        try {
            if (db.settingDao().get(FLAG) == "1") return
            // v1→v2 清理:删掉会与原生 agent_plan/可视化任务卡冲突的内置 "plan" 技能
            //(它让模型只写计划文件、不落地执行也不出任务卡)。
            try {
                val plan = db.skillDao().getByName("plan")
                if (plan != null && plan.source == "bundled") db.skillDao().deleteById(plan.id)
            } catch (_: Exception) {}
            seedSkills(db)
            seedMcp(db)
            seedSubAgents(db)
            db.settingDao().put(FLAG, "1")
            Log.i(TAG, "default skills + MCP + sub-agents seeded (v2)")
        } catch (e: Exception) {
            Log.w(TAG, "seed failed: ${e.message}")
        }
    }

    private suspend fun seedSkills(db: AppDatabase) {
        val dao = db.skillDao()
        for ((name, desc, body) in SKILLS) {
            if (dao.getByName(name) != null) continue
            dao.upsert(SkillEntity(name = name, description = desc, content = body, source = "bundled"))
        }
    }

    private suspend fun seedSubAgents(db: AppDatabase) {
        val dao = db.subAgentDao()
        for (sa in SUB_AGENTS) {
            if (dao.getByName(sa.name) != null) continue
            dao.upsert(com.xincode.data.SubAgentEntity(
                name = sa.name, description = sa.desc, systemPrompt = sa.prompt,
                skillNames = sa.skills, toolNames = sa.tools, builtin = true
            ))
        }
    }

    private data class SA(val name: String, val desc: String, val prompt: String, val skills: String, val tools: String)
    // 内置子智能体类型:各管各的专属技能与工具。
    private val SUB_AGENTS = listOf(
        SA("探索者", "只读勘察代码库/文件系统,给出带出处的结论",
            "你是探索者子智能体,只读勘察专家。绝不修改任何文件。",
            "explore", "file_read,list_dir,grep,glob"),
        SA("审查员", "审代码 diff、查 bug,给结论 + file:line",
            "你是审查员子智能体,代码审查与排障专家。只读,不改代码。",
            "code-review,systematic-debugging", "file_read,grep,glob,shell_exec"),
        SA("编码员", "实现改动并跑测试直到通过",
            "你是编码员子智能体,负责实现具体改动并保证测试通过。改动要小而准。",
            "test-loop", "file_read,file_write,file_edit,multi_edit,list_dir,grep,glob,shell_exec"),
        SA("研究员", "联网调研,汇总带来源的结论",
            "你是研究员子智能体,负责联网查资料并汇总,结论要带来源。",
            "", "web_search,web_fetch")
    )

    private suspend fun seedMcp(db: AppDatabase) {
        val dao = db.mcpServerDao()
        val existing = dao.getAll().map { it.name }.toSet()
        // 默认不自动连接(设备可能没有 node/uvx 运行时);预置好,用户在 MCP 页一键连。
        if ("fetch" !in existing) dao.upsert(McpServerEntity(
            name = "fetch", url = "", connected = false, transport = "stdio",
            command = "uvx", argsJson = org.json.JSONArray(listOf("mcp-server-fetch")).toString(),
            toolNames = "fetch"
        ))
        if ("memory" !in existing) dao.upsert(McpServerEntity(
            name = "memory", url = "", connected = false, transport = "stdio",
            command = "npx", argsJson = org.json.JSONArray(listOf("-y", "@modelcontextprotocol/server-memory")).toString(),
            toolNames = "memory"
        ))
    }

    // (name, description, SKILL.md body)
    private val SKILLS: List<Triple<String, String, String>> = listOf(
        Triple(
            "explore",
            "只读地大范围勘察代码库,给出带出处的单一结论",
            """你是只读探查专家。先撒大网(grep 找引用/用法、glob/ls 摸结构、按符号找定义),
再把最相关的 3-10 个文件读全——不要全读。找"谁调用/用到了 X"用 grep(按内容),不是 glob(只匹配文件名)。
一旦能回答就停。最终答复:先用一段话或几个要点给出结论,并标注 file:line。
当你断言某东西【不存在】时,必须说明你跑了哪些检索——负向结论只和背后的检索一样可信。全程严格只读,绝不修改。"""
        ),
        Triple(
            "code-review",
            "对当前改动(默认当前分支 diff)做审查,给结论 + 逐条 file:line",
            """先确定范围:git status、git diff --stat、git log --oneline,再 git diff <base>...HEAD。
diff 缺上下文时读被改的文件(签名、不变量、调用方)。断言"没有调用方依赖它"前先 grep/查符号。
按优先级找:(1)正确性 bug——差一、空指针、竞态、用错运算符、漏掉的边界;
(2)安全——注入(SQL/shell/路径)、泄密、缺鉴权、不安全反序列化;
(3)diff 藏起来的行为变化——改名漏了调用方、删掉了关键分支、错误处理现在被吞;
(4)新行为的测试覆盖;(5)风格(只在要紧处提)。
先给一句结论:"可直接合" / "小瑕疵,改完即可" / "有阻断问题,勿合"。
然后按严重度分组列要点(阻断 / 应修 / 小瑕疵)。干净就直说,别硬凑问题。全程只读,绝不提交。"""
        ),
        Triple(
            "systematic-debugging",
            "先找根因再动手修 bug;头痛医头视为失败",
            """核心原则:动手修之前【一定】先找到根因。
阶段1 理解:稳定复现;读真实报错和确切的出错行/栈帧;明确你【期望】什么 vs 实际发生了什么。
阶段2 隔离:缩到能触发的最小输入/代码路径;用真实断点或定点日志,不要到处乱改。
阶段3 诊断:提出假设,靠检查状态来证明它(别猜);区分是生产 bug、测试 bug、还是环境问题。
阶段4 修复并验证:上最小的正确修复,重跑复现,确认无回归。
永远不要瞎试指望蒙对——每次尝试都要基于一个被证明的假设。
同一症状连续两个假设被证伪后停下,带上你已排除的项来求助。"""
        ),
        // 注:不再内置 "plan" 技能——XINCODE 有原生 agent_plan + 可视化任务卡,更好用且不冲突。
        Triple(
            "test-loop",
            "检测并跑测试,诊断失败、修复、重跑直到通过",
            """1. 从清单文件识别测试命令:go.mod→go test ./...;package.json 的 scripts.test→npm/pnpm/yarn test;
pyproject.toml/requirements.txt→pytest;Cargo.toml→cargo test。含糊就问,别猜。
2. 跑它(长任务后台跑)。3. 逐条读失败:哪个测试、真实报错、抛错的 file:line。
4. 归类:生产 bug→改代码;测试 bug→改测试并明说;环境问题(缺依赖/工具链/夹具)→说明并停,别偷偷装东西。
5. 改完重跑、迭代。停止条件:全绿→报告改了什么;同一行连续 2 次仍失败→停下解释;3+ 个不相关失败→先修最小的、逐个来。
【绝不】跳过/删除/禁用失败的测试,也不改 runner 配置强行变绿。每轮开头先给一句状态。"""
        )
    )
}
