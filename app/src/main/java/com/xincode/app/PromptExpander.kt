package com.xincode.app

import android.util.Base64
import com.xincode.data.AppDatabase
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 把一句话扩写成能用的提示词。
 *
 * ## 为什么这个功能比看起来重要
 *
 * 身份卡也好、需求也好,写得好不好差别巨大 —— 一个只写「架构师」的身份卡,模型会
 * 给出泛泛而谈的通用回答;一个写清楚了「盯什么、不管什么、什么时候闭嘴」的卡,
 * 产出完全不同(预制团队那六张卡就是这么写出来的)。
 *
 * 但**没人愿意每次都手写三百字**。于是绝大多数用户的身份卡就停在「架构师」四个字,
 * 这个功能等于没用上。扩展按钮是在补这一段:你写四个字,它给你一份合格的初稿,
 * 你再改。
 *
 * ## 关键:扩展必须有结构,不能只是「写详细点」
 *
 * 让模型「把这段写详细一点」,得到的是同一句话的注水版 —— 更长,但没有新增信息量。
 * 所以每种场景都给死了产出骨架([Kind]),模型要做的是**按骨架把缺的部分想出来**,
 * 而不是把已有的话铺开。
 */
object PromptExpander {

    /** 扩展场景。不同场景要补的东西完全不同,不能共用一套指令。 */
    enum class Kind {
        /** 身份卡设定:补角色边界与行为约束。 */
        IDENTITY,

        /** 任务/需求:补场景、约束、验收标准、不做什么。 */
        TASK,

        /** 群聊议题:补讨论要收敛到什么、需要谁参与。 */
        GROUP_TOPIC
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun instructionFor(kind: Kind): String = when (kind) {
        Kind.IDENTITY -> """
            用户在给一个 AI 智能体设计身份,目前只写了个名字或一句话。
            把它扩写成一份**能直接用**的角色设定。

            必须写清楚这五件事,缺一不可:
            1. 这个角色对什么负责 —— 一句话,要具体
            2. 它盯着什么 —— 3 到 4 条具体的关注点。同一个问题,不同角色关心的
               东西应该明显不同,写出这种差异
            3. 它不管什么 —— 明确的边界。不写这条,角色会忍不住对所有事发表意见
            4. 它该怎么表达 —— 具体要求。不要写「专业」「清晰」这类没法执行的词,
               要写「给区间不给点估计」「必须给取舍」这种能照做的
            5. 什么时候不说话 —— 没有增量信息时该闭嘴

            要求:
            - 用第二人称,以「你是…」开头
            - 只输出角色设定正文,不要任何解释、不要 Markdown 标题层级
            - 300 到 500 字,写满但别注水
        """.trimIndent()

        Kind.TASK -> """
            用户想做一件事,但只说了一句话。帮他把这件事**想周全**。

            必须覆盖:
            - 到底要解决谁的什么问题(不是复述他的话,是往下挖一层)
            - 关键约束:平台、技术、数据量级、时间。能合理推断的就推断,
              推断不出来的列成「待确认」,不要瞎编
            - 具体要做哪几块
            - 什么算做完了 —— 可验收的标准,不要「体验好」这种
            - 这一版明确不做什么

            要求:
            - 保持第一人称,像用户自己说出来的话,不要写成需求文档模板
            - 不要用一堆标题堆砌,该分点的分点,该连着说的连着说
            - 只输出扩展后的内容,不要解释你做了什么
        """.trimIndent()

        Kind.GROUP_TOPIC -> """
            用户要在一个多人智能体群聊里抛出一个议题,目前只写了一句话。
            把它扩写成一个**能让讨论真正推进**的开场。

            必须包含:
            - 议题的背景和要解决的问题
            - 这次讨论要收敛出什么(不写这条,讨论会一直发散)
            - 需要哪些角色先发言、各自该回答什么问题
            - 已知的约束或前提

            要求:
            - 保持第一人称
            - 不要 @ 具体名字(用户会自己 @,你不知道这个房间里有谁)
            - 只输出扩展后的内容
        """.trimIndent()
    }

    /**
     * 扩写 [draft]。
     *
     * 用活跃供应商配置。刻意不做流式:这是个「点一下等一下」的动作,
     * 流式的复杂度换不来什么体验提升。
     *
     * @return 成功时是扩写结果;失败时 Result.failure,消息可直接给用户看。
     */
    suspend fun expand(
        database: AppDatabase,
        keystore: KeystoreProvider,
        kind: Kind,
        draft: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val text = draft.trim()
        if (text.isBlank()) return@withContext Result.failure(IllegalArgumentException("先写点东西再扩展"))

        val cfg = database.providerConfigDao().getActive()
            ?: return@withContext Result.failure(IllegalStateException("没有可用的供应商配置"))
        val apiKey = runCatching {
            keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        }.getOrNull()
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("无法解密 API Key"))
        }

        val body = JSONObject().apply {
            put("model", cfg.model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", instructionFor(kind)))
                put(JSONObject().put("role", "user").put("content", text))
            })
            put("stream", false)
        }

        return@withContext runCatching {
            val req = Request.Builder()
                .url(chatUrl(cfg.baseUrl))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: ${raw.take(120)}")
                }
                val json = JSONObject(raw)

                // 这条也要记账 —— 扩展一次是一次真实的模型调用,漏了账就对不上
                json.optJSONObject("usage")?.let { usage ->
                    UsageRecorder.record(
                        database, usage, sessionId = 0,
                        model = cfg.model, provider = cfg.name, source = "expand"
                    )
                }

                val out = json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty().trim()
                if (out.isBlank()) throw IllegalStateException("模型没有返回内容")
                out
            }
        }
    }

    /** 与 OpenAiClient 同一套版本段规则:base_url 自带 /v1 就不再补。 */
    private fun chatUrl(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (Regex("/v\\d+[a-zA-Z0-9]*$").containsMatchIn(b)) "$b/chat/completions"
        else "$b/v1/chat/completions"
    }
}
