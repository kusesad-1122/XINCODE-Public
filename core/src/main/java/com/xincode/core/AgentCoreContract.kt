package com.xincode.core

/**
 * Agent Core 契约常量表 —— 与 AGENT-CORE-CONTRACT.md §附录「常量总表」以及桌面端
 * `src/services/toolLimits.ts` **逐字一致**。任何一端修改都必须在另一端同步,
 * 由 `AgentCoreContractTest`(app) + `ToolContractLogicTest`(core) 机械校验不漂移。
 *
 * 三大类:
 *  - §1 工具并发语义:    [MAX_PARALLEL_TOOLS] + [CONCURRENCY_SAFE_TOOLS]
 *  - §2 结果三层截断:    [MAX_RESULT_CHARS_PER_TOOL] / [MAX_RESULT_CHARS_PER_MESSAGE] /
 *                        [OFFLOAD_THRESHOLD_CHARS] / [TOOL_SUMMARY_MAX_CHARS] 等
 *  - §3 单轮预算:        [MAX_TOOL_CALLS_PER_TURN] / [MAX_TURNS_DEFAULT] /
 *                        [TURN_TOKEN_BUDGET_RATIO] / [MAX_CONSECUTIVE_TRUNCATIONS] /
 *                        [MAX_REPEATED_TOOL_ERRORS]
 */
object AgentCoreContract {

    // ───────────────────────── §1 工具并发语义 ─────────────────────────

    /** 单批并发执行的安全工具上限(连续安全工具成批并发,单批不超过此值)。 */
    const val MAX_PARALLEL_TOOLS = 8

    /**
     * 并发安全(只读 + 幂等)工具白名单。命中即视为 [Tool.concurrencySafe]=true。
     * 未在 [CONCURRENCY_SAFE_TOOLS] 中、且工具自身未显式声明 true 的工具,
     * 一律按 false 处理(fail-closed,独占串行执行)。
     *
     * 判定依据(逐个见交付报告):
     *  - 只读/幂等、无副作用网络调用 → true : file_read, list_dir, glob, grep, code_graph,
     *    current_time, describe_image, get_memory_by_title, recall_memory,
     *    transcribe_audio, translate_text, web_fetch, web_search
     *  - 写文件 / 执行命令 / 有副作用 → 不列入(fail-closed):file_write, file_edit, multi_edit,
     *    delete_file, make_directory, shell_exec, su_exec, env_exec, execute_code,
     *    download_file, generate_image, save_memory, ...
     */
    val CONCURRENCY_SAFE_TOOLS: Set<String> = setOf(
        "file_read",
        "list_dir",
        "glob",
        "grep",
        "code_graph",
        "current_time",
        "describe_image",
        "get_memory_by_title",
        "recall_memory",
        "transcribe_audio",
        "translate_text",
        "web_fetch",
        "web_search"
    )

    // ───────────────────────── §2 结果三层截断 ─────────────────────────

    /** L1:单个工具结果硬上限(字符)。超出 → 头 60% + 尾 30% + 截断标记。 */
    const val MAX_RESULT_CHARS_PER_TOOL = 50_000

    /** L2:单轮(一次模型回复产生的所有 tool 结果)聚合预算(字符)。超出 → 降级为摘要。 */
    const val MAX_RESULT_CHARS_PER_MESSAGE = 200_000

    /** L3:落盘阈值(字符)。非豁免工具结果超出 → 写盘,模型只收「摘要 + 文件路径」。 */
    const val OFFLOAD_THRESHOLD_CHARS = 50_000

    /** 摘要 / 落盘预览最大长度(字符)。 */
    const val TOOL_SUMMARY_MAX_CHARS = 50

    /** L1 头部保留比例。 */
    const val RESULT_HEAD_RATIO = 0.6

    /** L1 尾部保留比例。 */
    const val RESULT_TAIL_RATIO = 0.3

    /** L1 截断标记模板(用 %d 接收被删字符数)。 */
    const val RESULT_TRUNCATE_MARKER = "\n[...已截断 %,d 字符,请用精确命令重新查询...]\n"

    /** 落盘文件保留天数,启动时/首次落盘时清理过期文件。 */
    const val OFFLOAD_RETENTION_DAYS = 7L

    /** L3 落盘目录名(位于 app 的 cache/files 目录下的子目录)。 */
    const val OFFLOAD_DIR_NAME = "tool-results"

    /**
     * L3 豁免工具:避免「读文件还要二次读」的死循环。file_read 自身命中 L3 时
     * 不落盘(但 L1 截断仍生效)。
     */
    const val EXEMPT_OFFLOAD_TOOL = "file_read"
    val OFFLOAD_EXEMPT_TOOLS: Set<String> = setOf(EXEMPT_OFFLOAD_TOOL)

    /**
     * 与旧 [TURN_END_RESULT_CAP_TOKENS] 压缩层的关系:三层预算是「入 prompt 前的硬预算」,
     * 旧的 compactToolResults 是「发 API 前的最后一层压缩」,两者共存、分工明确。
     * 执行顺序:先三层预算(本对象),再 compactToolResults(AgentCore 内)。
     */

    // ───────────────────────── §3 单轮预算 ─────────────────────────

    /** 单轮 tool_calls 数量上限。超出部分【不执行】,回明确失败结果让模型收尾。 */
    const val MAX_TOOL_CALLS_PER_TURN = 25

    /**
     * maxIterations 默认值。约定 0 = 不限(由 PowerMode.UNLIMITED_ITERS 使用),
     * 保留用户显式配置为「不限」的能力,配置项不删除。
     */
    const val MAX_TURNS_DEFAULT = 60

    /**
     * 软 token 预算比例。当上一轮 prompt_tokens ≥ contextWindow × 本比例时,
     * 注入一条系统提醒让模型收敛(不硬停)。
     */
    const val TURN_TOKEN_BUDGET_RATIO = 0.5

    /** 流连续被截断上限(达到即放弃续跑)。与旧值一致。 */
    const val MAX_CONSECUTIVE_TRUNCATIONS = 3

    /** 同一工具连续相同错误上限(达到即中止整轮防空转)。与旧值一致。 */
    const val MAX_REPEATED_TOOL_ERRORS = 3

    // ───────────────── 旧 P1 compactToolResults 常量(集中维护) ─────────────────

    /** 单条 tool 消息超过此 token 数即被 compactToolResults 压缩(头 1500 + 尾 1000 token)。 */
    const val TURN_END_RESULT_CAP_TOKENS = 3_000

    /** compactToolResults 估算 token 时的 字符/ token 比。 */
    const val RESULT_COMPACT_CHARS_PER_TOKEN = 4

    /** compactToolResults 头部保留 token 数。 */
    const val RESULT_COMPACT_PREFIX_TOKENS = 1_500

    /** compactToolResults 尾部保留 token 数。 */
    const val RESULT_COMPACT_SUFFIX_TOKENS = 1_000
}
