package com.xincode.app

import com.xincode.data.AppDatabase
import com.xincode.data.ProviderConfigEntity

/**
 * 【功能模型配置】——给应用内部的每个模型调用点单独指定「用哪套供应商配置 + 哪个模型」。
 *
 * 与 [AuxModels] 的区别(两者并存,分工不同):
 *  - AuxModels 是「另配一套 base_url + key + model」,适合主模型缺某种能力时转交外部端点;
 *    代价是每个任务都要重填一遍 URL 和 Key。
 *  - 这里是「从已保存的供应商配置里挑一个」,不用重填任何东西,适合「同样能聊天,但这件事
 *    想用更便宜/更强的模型」的场景。
 *
 * 存储走 settings 键值表,不需要新表也不需要 Room 迁移:
 *   fn_<key>_config_id  → provider_configs.id;"0" 或缺失 = 跟随当前活跃配置
 *   fn_<key>_model      → 模型 ID;空 = 用该配置自己的 model
 *
 * 【重要】这里列出的每一项都必须对应代码里真实存在、且确实会被单独调用的地方。
 * 宁可少列,也不能列一个点了没反应的开关。
 */
object FunctionModels {

    /**
     * 一个可单独配模型的功能。
     *
     * @param key      存储键前缀
     * @param label    页面上显示的名字
     * @param hint     一句话说明它什么时候会被调用
     * @param needs    这个功能需要模型具备的能力;选中的配置没声明该能力时给出警告
     */
    data class Feature(
        val key: String,
        val label: String,
        val hint: String,
        val needs: Capability? = null
    )

    enum class Capability(val label: String) {
        VISION("识图"),
        AUDIO("音频解析"),
        VIDEO("视频解析"),
        TOOL_CALL("ToolCall")
    }

    /**
     * 功能清单。每一项后面注明了对应的调用点,改代码时别让它们对不上。
     *
     * 注:Operit 那张图里的「UI 控制器」「群组规划」「视频识别」XINCODE 目前没有对应实现,
     * 故意不列——列出来只会是三个点了没反应的开关。
     */
    val FEATURES: List<Feature> = listOf(
        Feature(
            "compact", "上下文总结",
            "上下文超阈值自动压缩、以及手动压缩时,把历史浓缩成摘要。用便宜的长上下文模型很划算。"
        ),
        Feature(
            "review", "后台复盘",
            "一轮回答交付后台默默跑的记忆整理与技能复盘,不占用你的等待时间,适合配便宜模型。"
        ),
        Feature(
            "subagent", "子智能体派发",
            "dispatch_agents 派出去的每个子智能体用哪个模型。",
            needs = Capability.TOOL_CALL
        ),
        Feature(
            "wolfpack", "并行子任务",
            "wolfpack_run 并行编排里每个子任务用哪个模型。",
            needs = Capability.TOOL_CALL
        ),
        Feature(
            "judge", "Goal 目标裁判",
            "Goal 模式判断目标是否达成。一轮评估要投 3 票,单配便宜模型能省不少。"
        ),
        Feature(
            "cron", "定时任务",
            "cron 到点后台自动执行任务时用哪个模型。",
            needs = Capability.TOOL_CALL
        ),
        Feature(
            "embedding", "记忆向量化",
            "记忆存取与语义召回用的 embedding 模型。注意这里要填 embedding 模型而不是对话模型。"
        ),
        Feature(
            "vision", "图像识别",
            "describe_image 看图。也可以在「模型委托」里配外部端点。",
            needs = Capability.VISION
        ),
        Feature(
            "transcribe", "语音转写",
            "transcribe_audio 把音频转成文字。",
            needs = Capability.AUDIO
        ),
        Feature(
            "translate", "翻译",
            "translate_text 工具。"
        ),
        Feature(
            "reason", "深度推理",
            "ask_reasoning 把难题转交更强的推理模型。"
        )
    )

    /** 某功能的解析结果。[configId] 为 0 表示跟随当前活跃配置。 */
    data class Assignment(val configId: Long, val model: String)

    suspend fun get(db: AppDatabase, key: String): Assignment {
        // 走当前 profile 的键名(默认 profile 就是原键名,老数据不受影响)
        val p = Profiles.currentId(db)
        val id = db.settingDao().get(Profiles.key(p, "fn_${key}_config_id"))?.toLongOrNull() ?: 0L
        val model = db.settingDao().get(Profiles.key(p, "fn_${key}_model"))?.trim().orEmpty()
        return Assignment(id, model)
    }

    suspend fun set(db: AppDatabase, key: String, configId: Long, model: String) {
        val p = Profiles.currentId(db)
        db.settingDao().put(Profiles.key(p, "fn_${key}_config_id"), configId.toString())
        db.settingDao().put(Profiles.key(p, "fn_${key}_model"), model.trim())
    }

    /** 清除指定功能的分配,回到「跟随主模型」。 */
    suspend fun clear(db: AppDatabase, key: String) = set(db, key, 0L, "")

    /**
     * 解析出实际要用的配置。指定的配置被删掉时回落到活跃配置,
     * 而不是让这个功能直接崩掉——删一个供应商不该把定时任务一起带走。
     */
    suspend fun resolveConfig(db: AppDatabase, key: String): ProviderConfigEntity? {
        val a = get(db, key)
        val dao = db.providerConfigDao()
        return if (a.configId > 0) dao.getById(a.configId) ?: dao.getActive() else dao.getActive()
    }

    /**
     * 该配置是否声明了此功能需要的能力。用于页面上的红字警告——
     * 只提示,不阻止保存:用户可能比我们更清楚自己那个模型行不行。
     */
    fun capabilityMissing(feature: Feature, cfg: ProviderConfigEntity?): Boolean {
        val need = feature.needs ?: return false
        if (cfg == null) return false
        return when (need) {
            Capability.VISION -> !cfg.supportsVision
            Capability.AUDIO -> !cfg.supportsAudio
            Capability.VIDEO -> !cfg.supportsVideo
            Capability.TOOL_CALL -> !cfg.supportsToolCall
        }
    }
}
