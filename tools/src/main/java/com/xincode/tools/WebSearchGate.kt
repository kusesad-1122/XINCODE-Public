package com.xincode.tools

/**
 * 联网搜索/抓取总开关。【默认关闭】——不打开就完全不能联网获取信息
 * (web_search / web_fetch 不暴露给模型,主 agent 与子智能体都受控)。输入框「联网搜索」可切换。
 */
object WebSearchGate {
    @Volatile
    var enabled: Boolean = false
}
