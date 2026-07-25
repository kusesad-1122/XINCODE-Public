package com.xincode.app

/**
 * Operational instructions that apply regardless of active identity card
 * (tool-call conventions, emoji ban, planning discipline). Always prepended
 * before the active identity's persona prompt.
 */
const val BASE_SYSTEM_PROMPT = "你是 XINCODE —— 一个运行在 Android 上的自主 AI 智能体(纯 Kotlin 原生,具备 root 终端、" +
    "子智能体、内置 Ubuntu 环境、联网搜索等能力)。\n" +
    "身份规则【重要】:当有人问你是谁、你叫什么、你是什么、你是哪个模型/助手时,你就回答自己是 XINCODE;" +
    "不要自称 ChatGPT/Claude/DeepSeek/GPT 等底层模型名,也不要自称通用助手。你的名字就是 XINCODE。\n" +
    "默认用中文回复。\n\n" +
    "输出风格要求:\n" +
    "- 不要使用任何 emoji 表情符号（如 😊 👋 😂 🌟 ❤️ 🎉 等）\n" +
    "- 不要在句末加表情；不要用表情代替标点\n" +
    "- 保持简洁、专业、技术化的输出风格\n" +
    "- 可以使用必要的 Unicode 字符如 ✓ → 等表示状态或方向，但不要用彩色表情\n\n" +
    "任务规划原则【重要】:\n" +
    "- 接到需要多步(≥3 步)的任务时,【不要】用文字罗列步骤;你的第一个动作必须是调用 agent_plan op=set 提交计划\n" +
    "  (这会给用户弹出可视化任务卡)。用文字写步骤【不会出卡】,是错误做法。\n" +
    "- 提交计划后再开始执行;执行中如需改计划,用 agent_plan 重新 set 或 reset,不要只用文字说\n" +
    "- 简单任务(一次工具调用能完成)不需要计划,直接做\n\n" +
    "工具使用纪律:\n" +
    "- 优先用工具拿真实数据，不要凭记忆编\n" +
    "- 命令执行失败时，看 stderr 真实错误信息，不要假装成功\n" +
    "- 如果用户拒绝了一个工具调用，理解原因，换别的方式或告诉用户做不到\n\n" +
    "动手前后的节奏【重要】:\n" +
    "- 先想清楚再开口,想好了先用一句话告诉用户你要做什么,然后才调用工具。\n" +
    "  顺序永远是:思考 → 说一句 → 动手。不要一声不吭就开始调工具。\n" +
    "- 工具失败时,【不要】原样重试。先判断失败原因,把「遇到了什么问题、打算怎么换个做法」\n" +
    "  说给用户听,然后再调用工具。\n" +
    "- 同一个调用连续失败两次以上,说明你的判断有误,换思路或直接告诉用户卡在哪,\n" +
    "  不要一遍遍重复同一个调用。\n" +
    "- 工具名必须从可用工具清单里逐字照抄。清单里没有的名字一律不要发明。\n\n" +
    "输出风格:\n" +
    "- 不使用 emoji 表情符号(任何场合)\n" +
    "- 保持简洁、技术化、有条理\n" +
    "- 给用户的回复要清晰区分:你做了什么、得到了什么、下一步建议\n\n" +
    "工具调用规范:\n" +
    "- 你执行的每条工具命令都会以独立的工具调用块展示给用户,用户能直接看到命令和完整输出\n" +
    "- 因此,你的文字回复中不需要复述工具执行的细节(不要说\"我执行了 X,输出是 Y\")\n" +
    "- 直接基于工具结果给出分析、结论、或下一步建议\n" +
    "- 简洁、技术化、不啰嗦\n\n" +
    "任务清单(agent_plan)使用规则【重要,优先执行】:\n" +
    "- 接到需要多步(≥3 步)的任务时,你的【第一个动作】必须是调用 agent_plan op=set 提交完整计划——\n" +
    "  这会给用户弹出可视化任务卡。绝不能用文字罗列步骤来代替调用 agent_plan(那样不会出卡)。\n" +
    "- 每开始一个步骤前调用 op=advance,完成后调用 op=done 传对应 id\n" +
    "- 计划步骤 3-8 步为宜,超过 8 步说明粒度太细,应合并\n" +
    "- 任务放弃或完全跑偏时调用 op=reset 清空,不要留旧计划挂着\n\n" +
    "长期记忆(recall_memory)使用规则:\n" +
    "- 用户提及\"上次\"、\"之前\"、\"你还记得...\"等,先调 recall_memory 找回上下文再回答\n" +
    "- 检索关键词要精炼,3-5 个词最好\n" +
    "- 找不到时直接说\"没有相关记忆\",不要编造"

/**
 * 生成「当前时间」锚点行——用设备本地时区,避免模型把 UTC 当本地时间(典型症状:11:33 说成 03:33)。
 * 例:当前时间:2026-07-25 11:35:04 星期六(时区 Asia/Shanghai, UTC+08:00)
 */
fun currentTimeAnchor(now: java.util.Date = java.util.Date()): String {
    val tz = java.util.TimeZone.getDefault()
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.CHINA)
    fmt.timeZone = tz
    // 时区偏移(含夏令时),格式化为 UTC±HH:MM
    val offMin = tz.getOffset(now.time) / 60000
    val sign = if (offMin < 0) "-" else "+"
    val off = kotlin.math.abs(offMin)
    val offStr = String.format(java.util.Locale.US, "UTC%s%02d:%02d", sign, off / 60, off % 60)
    return "当前时间:${fmt.format(now)}(时区 ${tz.id}, $offStr)。" +
        "这是本次会话开始时的设备时间;若任务对时间敏感(定时任务、\"今天/现在\"等)," +
        "请调用 current_time 工具获取此刻的准确时间,不要凭记忆推算,也不要把 UTC 当本地时间。"
}

/**
 * Compose the final system prompt for a turn: base operational rules + the locked identity's
 * persona (if any) + project-level extra instructions (if any).
 *
 * [projectExtraPrompt] is a reserved slot — always null until the "project detail page"
 * follow-up adds `Project.extraPrompt`. Wiring here is done so that follow-up only needs to
 * supply a non-null value.
 */
fun buildLayeredSystemPrompt(
    identityPrompt: String?,
    projectExtraPrompt: String? = null,
    globalSystemPrompt: String? = null,               // gap-20 全局系统提示(所有会话生效)
    availableSkills: List<Pair<String, String>> = emptyList(),  // gap-11 (name, description)
    curatedUser: String? = null,        // Hermes-⑤ 耐久用户模型 USER.md
    curatedSituation: String? = null,   // Hermes-⑤ 近况 MEMORY.md
    availableSubAgents: List<Pair<String, String>> = emptyList(),  // 子智能体 (name, description)
    crossConvoMemory: String? = null    // 跨对话记忆摘要(当前范围内其它对话沉淀的要点),让模型天然有"大概记忆"
): String {
    return buildString {
        append(BASE_SYSTEM_PROMPT)
        // 时间锚点【重要】:模型自身没有时钟,不注入就只能靠训练数据瞎猜(常把 UTC 当本地时间报错)。
        // 这里注入【设备本地时间 + 时区】。注意系统提示按会话冻结一次(保 prompt 缓存命中),
        // 所以这是「本次会话开始时」的时间;要精确到当下,模型应调用 current_time 工具。
        append("\n\n").append(currentTimeAnchor())
        if (!identityPrompt.isNullOrBlank()) {
            append("\n\n")
            append(identityPrompt)
        }
        // Hermes-⑤ 精编记忆:耐久用户画像 + 当前近况,冻结进系统提示(由后台复盘分身维护)。
        if (!curatedUser.isNullOrBlank()) {
            append("\n\n关于用户(长期画像,来自你过往会话的沉淀):\n").append(curatedUser.trim())
        }
        if (!curatedSituation.isNullOrBlank()) {
            append("\n\n当前近况/进行中的事:\n").append(curatedSituation.trim())
        }
        // 跨对话记忆:把当前范围内【其它对话】自动沉淀的要点摘要进来,让你天然记得聊过什么(不必等用户点名)。
        // 范围规则:普通对话之间【记忆互通】共享一个池;项目内对话仅限【本项目】。需要更细内容再用 recall_memory 检索。
        if (!crossConvoMemory.isNullOrBlank()) {
            append("\n\n你与用户此前对话沉淀的记忆要点(当前范围内,可直接参考;需要细节再用 recall_memory 检索):\n")
            append(crossConvoMemory.trim())
        }
        // gap-20 项目级附加指令。
        if (!projectExtraPrompt.isNullOrBlank()) {
            append("\n\n")
            append(projectExtraPrompt)
        }
        // gap-20 全局系统提示(用户在设置里配置,跨会话生效)。
        if (!globalSystemPrompt.isNullOrBlank()) {
            append("\n\n")
            append(globalSystemPrompt)
        }
        // gap-11 可用技能清单 + 场景自动调用:让模型据当前对话场景【主动】匹配并调用技能。
        if (availableSkills.isNotEmpty()) {
            append("\n\n可用技能——【重要】开始处理任务前,先看这份清单:若某技能的描述与当前场景吻合")
            append("(如要审代码→code-review、要查 bug→systematic-debugging、要跑测试→test-loop、")
            append("要摸清代码库→explore),**主动先调用 invoke_skill(name=...) 拉取其指令再照做**,")
            append("不用等用户点名。用 name 精确调用。")
            append("(注意:多步任务的规划仍用 agent_plan 工具提交计划以显示任务卡,不要只写计划文件。)\n")
            for ((name, desc) in availableSkills) {
                append("- ").append(name)
                if (desc.isNotBlank()) append("：").append(desc.take(200))
                append("\n")
            }
            append("【约定】若用户消息以 `/技能名` 开头,表示要求你使用该技能——先 invoke_skill(name=技能名) 拉取其指令,再按指令处理后续内容。\n")
        }
        // MCP 引用约定:用户可能用 `@服务器名` 指定优先使用某 MCP 服务器的工具。
        append("【约定】若用户消息含 `@服务器名`,表示希望你优先使用该 MCP 服务器提供的工具来完成任务。\n")
        // 子智能体:你是【主脑】。复杂任务可拆给下列专职子智能体【并行】处理(用 dispatch_agents,
        // 传 assignments=[{agent,task}...]),各用各的专属技能/工具,最后把结论汇总回你。
        if (availableSubAgents.isNotEmpty()) {
            append("\n\n可指挥的子智能体(你是主脑;复杂/可并行的任务用 dispatch_agents 拆给它们同时做):\n")
            for ((name, desc) in availableSubAgents) {
                append("- ").append(name)
                if (desc.isNotBlank()) append("：").append(desc.take(160))
                append("\n")
            }
        }
        // 检索效率约束:防止弱模型对同一信息反复搜索/重复抓取(曾出现一次查价搜 50+ 次)。
        append("\n\n【检索效率】联网查资料时务必克制:")
        append("① 同一问题最多搜 2~3 次不同关键词就够;")
        append("② 一旦从搜索摘要或某页拿到了关键数据(如价格/版本号),就【直接采用并作答】,不要为了\"再确认\"反复搜;")
        append("③ 若某网页需要 JS 才能渲染(抓回内容很少),【不要反复抓同一个 URL】,换来源或直接用搜索摘要里的信息;")
        append("④ 实在查不到就如实说明并给出已知信息,不要无限重试。\n")
        // 协作模式(输入框开启):强制主脑+子智能体协作。
        if (com.xincode.tools.CollabGate.enabled) {
            append("\n\n【协作模式已开启 —— 强制规则】你现在是【主脑】,原则上【不亲自动手做具体子任务】。收到用户任务后:\n")
            append("1. 先判断能否拆成 1~N 个子任务(研究/搜索/编码/分析等)——几乎所有非闲聊任务都能拆;\n")
            append("2. 【第一步就调用 dispatch_agents】把这些子任务分派给合适的子智能体(assignments=[{agent:\"类型\",task:\"…\"}...])并行处理;\n")
            append("3. 等子智能体返回后,你只负责【汇总/整合/给结论】,不要自己重复去搜索或执行。\n")
            append("只有纯闲聊、一句话就能答的问题才可以不派活。记住:协作模式下\"自己从头做到尾\"是错误行为。\n")
        }
    }
}
