package com.xincode.app

import com.xincode.data.AppDatabase
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 对话自动起名:首轮回复交付后,若会话仍是默认标题「新对话」,
 * 用「对话起名」委托模型(未配置则回退主模型,见 AuxModels.resolve)生成一个短标题。
 * 失败静默保留默认标题;用户手动改过名字的会话永不覆盖。
 */
object ChatTitleGenerator {
    private const val DEFAULT_TITLE = "新对话"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // KeystoreProvider 无需上下文,这里自持一个实例,免去给 AgentChatState 扩构造参数
    private val keystore by lazy { KeystoreProvider() }

    fun maybeGenerate(database: AppDatabase, sessionId: Long, userText: String, assistantText: String) {
        if (userText.isBlank() && assistantText.isBlank()) return
        scope.launch {
            try {
                val session = database.sessionDao().getById(sessionId) ?: return@launch
                if (session.title.trim() != DEFAULT_TITLE) return@launch
                val raw = AuxModels.chat(
                    database, keystore, "title",
                    systemPrompt = "你是对话命名器。根据对话内容起一个不超过12个字的中文标题,概括主题。" +
                        "直接输出标题本身:不要引号、不要句号、不要任何解释或前缀。",
                    userText = "用户:${userText.take(800)}\n\n助手:${assistantText.take(1200)}"
                ).getOrNull()?.trim() ?: return@launch
                // 去掉可能被带出来的引号/书名号,截到安全长度
                val title = raw.trim('"', '“', '”', '「', '」', '。', '.').take(24)
                if (title.isBlank()) return@launch
                database.sessionDao().rename(sessionId, title)
            } catch (_: Exception) {
                // 静默:起名失败不影响对话本身
            }
        }
    }
}
