package com.xincode.provider

/**
 * Hermes-⑩ 声明式 Provider Profile(受 Hermes `ProviderProfile` 启发)。
 *
 * 把"某供应商怎么连"集中成一份**数据**:base_url、api 形态(chat_completions/anthropic/responses)、
 * 默认模型、需要的额外请求头、别名。新增供应商 = 往 [BUILTIN] 加一条,无需改传输/核心代码。
 *
 * XINCODE 的实际请求参数仍来自用户的 provider 配置(ProviderConfigEntity);这里作为
 * **预填/自动识别**的种子:用户选/输入一个供应商名或 base_url,就能带出正确的 apiPathType 与默认头。
 */
data class ProviderProfile(
    val name: String,
    val baseUrl: String,
    /** 对应 OpenAiClient.chatEndpoint 的分派:"chat_completions" | "anthropic" | "responses" | "custom"。 */
    val apiPathType: String = "chat_completions",
    val defaultModel: String = "",
    /** 额外请求头(verbatim 注入),对应 ProviderConfig.extraHeadersJson。 */
    val extraHeaders: Map<String, String> = emptyMap(),
    val aliases: List<String> = emptyList()
) {
    fun extraHeadersJson(): String =
        if (extraHeaders.isEmpty()) "" else org.json.JSONObject(extraHeaders as Map<*, *>).toString()
}

object ProviderProfiles {
    val BUILTIN: List<ProviderProfile> = listOf(
        ProviderProfile("nous", "https://inference.nousresearch.com", "chat_completions",
            aliases = listOf("nous-portal", "nousresearch")),
        ProviderProfile("openrouter", "https://openrouter.ai/api", "chat_completions",
            aliases = listOf("or")),
        ProviderProfile("openai", "https://api.openai.com", "chat_completions",
            defaultModel = "gpt-4o", aliases = listOf("oai")),
        ProviderProfile("openai-responses", "https://api.openai.com", "responses",
            defaultModel = "gpt-4o"),
        ProviderProfile("anthropic", "https://api.anthropic.com", "anthropic",
            defaultModel = "claude-sonnet-4", aliases = listOf("claude")),
        ProviderProfile("deepseek", "https://api.deepseek.com", "chat_completions",
            defaultModel = "deepseek-chat", aliases = listOf("ds")),
        ProviderProfile("groq", "https://api.groq.com/openai", "chat_completions",
            aliases = listOf("groqcloud")),
        ProviderProfile("xai", "https://api.x.ai", "chat_completions",
            defaultModel = "grok-2", aliases = listOf("grok")),
        ProviderProfile("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "chat_completions",
            aliases = listOf("google"))
    )

    /** 按名字或别名查(大小写不敏感)。 */
    fun byName(name: String): ProviderProfile? {
        val n = name.trim().lowercase()
        return BUILTIN.firstOrNull { it.name == n || it.aliases.any { a -> a == n } }
    }

    /** 按 base_url 的 host 自动识别供应商(用户只填了 URL 时)。 */
    fun byBaseUrl(baseUrl: String): ProviderProfile? {
        val host = try { java.net.URI(baseUrl.trim()).host ?: "" } catch (_: Exception) { "" }.lowercase()
        if (host.isBlank()) return null
        return BUILTIN.firstOrNull { p ->
            val ph = try { java.net.URI(p.baseUrl).host ?: "" } catch (_: Exception) { "" }.lowercase()
            ph.isNotBlank() && (host == ph || host.endsWith("." + ph.substringAfter("www.")))
        }
    }

    fun names(): List<String> = BUILTIN.map { it.name }
}
