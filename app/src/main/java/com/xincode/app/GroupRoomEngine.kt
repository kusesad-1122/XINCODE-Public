package com.xincode.app

import android.util.Base64
import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.GroupMemberEntity
import com.xincode.data.GroupMessageEntity
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
 * 群聊房间的运行引擎。
 *
 * 刻意【不复用 AgentCore】:群聊里的成员只需要「读一段共享历史 → 说一句话」,不需要工具
 * 调用、不需要游标持久化、不需要安全门。套 AgentCore 会把整条工具回环拖进来,还得给每个
 * 成员各建一个注册表,复杂度和风险都不划算。这里直接发一次非流式请求就够。
 *
 * 防失控是这套东西的重点,见 [respondTo] 里的说明。
 */
object GroupRoomEngine {

    private const val TAG = "XincodeGroup"

    /** 一条用户消息最多叫醒几个成员。@all 时也受这个上限约束。 */
    private const val MAX_TARGETS_PER_TURN = 6

    /** 超过多少字符触发一次压缩。字符不是 token,但作为阈值够用且不用引分词器。 */
    private const val DEFAULT_COMPACT_CHARS = 24_000

    /** 压缩后保留最近多少条原始消息。 */
    private const val KEEP_RECENT = 12

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 处理一条新消息:解析 @,让被点到的成员依次回答。
     *
     * 关于「依次」而不是并行:群里成员应该能看到彼此刚说了什么(否则就成了各说各话的
     * 平行宇宙)。代价是慢一些,但这才是群聊该有的样子。
     *
     * @param onReply 每条回复落库后回调,供 UI 即时刷新。
     */
    suspend fun onMessage(
        database: AppDatabase,
        keystore: KeystoreProvider,
        roomId: Long,
        content: String,
        senderName: String,
        onReply: (suspend () -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val dao = database.groupRoomDao()
        val members = dao.getMembers(roomId)
        if (members.isEmpty()) return@withContext

        val targets = MentionRouting
            .resolveTargets(members.map { it.displayName }, content, senderName)
            .take(MAX_TARGETS_PER_TURN)
        if (targets.isEmpty()) return@withContext

        for (name in targets) {
            val member = members.firstOrNull { it.displayName == name } ?: continue
            val reply = runCatching { respondTo(database, keystore, roomId, member, members) }
                .getOrElse { e ->
                    Log.w(TAG, "member ${member.displayName} failed: ${e.message}")
                    "(${member.displayName} 回复失败:${e.message?.take(80)})"
                }
            if (reply.isBlank()) continue
            dao.insertMessage(
                GroupMessageEntity(roomId = roomId, sender = member.displayName, content = reply)
            )
            onReply?.invoke()

            // 成员回复里若 @ 了别人,【不】自动续接。
            // 自动续接看起来聪明,实际会让两个成员无限对话下去 —— 必须由用户再推一把。
        }

        maybeCompact(database, keystore, roomId)
    }

    /** 让某个成员基于房间历史说一句话。 */
    private suspend fun respondTo(
        database: AppDatabase,
        keystore: KeystoreProvider,
        roomId: Long,
        member: GroupMemberEntity,
        allMembers: List<GroupMemberEntity>
    ): String {
        val dao = database.groupRoomDao()
        val cfgDao = database.providerConfigDao()
        val cfg = (if (member.providerConfigId > 0) cfgDao.getById(member.providerConfigId) else null)
            ?: cfgDao.getActive()
            ?: throw IllegalStateException("没有可用的供应商配置")
        val apiKey = runCatching {
            keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        }.getOrElse { throw IllegalStateException("无法解密 API Key") }
        val model = member.model.ifBlank { cfg.model }

        val identity = if (member.identityId > 0)
            database.identityDao().getById(member.identityId) else null

        val roster = allMembers.joinToString("、") { "@${it.displayName}" }
        val system = buildString {
            append("你现在在一个多人群聊里,你的名字是「${member.displayName}」。\n")
            append("群成员:$roster,以及用户。\n")
            append("历史里 [某某]: 开头的是别人说的话,没有前缀的是你自己说过的。\n")
            append("规则:\n")
            append("- 只说你自己要说的话,不要替别人发言,不要模拟别人的回复。\n")
            append("- 不要在开头写自己的名字,系统会自动标注是谁说的。\n")
            append("- 简洁,群聊里没人想读长篇大论。\n")
            identity?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                append("\n你的角色设定:\n").append(it).append("\n")
            }
        }

        val history = dao.getMessages(roomId)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        messages.putAll(projectHistory(history, member.displayName))
        messages.put(JSONObject().put("role", "user")
            .put("content", "(现在轮到你「${member.displayName}」说话)"))

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", false)
        }
        val url = chatUrl(cfg.baseUrl)
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${text.take(150)}")
            }
            return JSONObject(text).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")
                ?.optString("content").orEmpty().trim()
        }
    }

    /**
     * 历史过长时压缩一次:把旧消息总结成一条摘要,删掉被总结的原文。
     * 与主对话的压缩同理,只是这里按字符数判断,不引 token 分词。
     */
    private suspend fun maybeCompact(
        database: AppDatabase,
        keystore: KeystoreProvider,
        roomId: Long
    ) {
        val dao = database.groupRoomDao()
        val room = dao.getRoom(roomId) ?: return
        val threshold = room.compactThreshold.takeIf { it > 0 } ?: DEFAULT_COMPACT_CHARS
        val all = dao.getMessages(roomId)
        val totalChars = all.sumOf { it.content.length }
        if (totalChars < threshold || all.size <= KEEP_RECENT + 2) return

        val old = all.dropLast(KEEP_RECENT)
        if (old.isEmpty()) return
        val cutoffId = old.last().id

        val cfg = database.providerConfigDao().getActive() ?: return
        val apiKey = runCatching {
            keystore.decrypt(Base64.decode(cfg.apiKeyEnc, Base64.NO_WRAP))
        }.getOrNull() ?: return

        val transcript = old.joinToString("\n") { m ->
            val who = if (m.sender.isBlank()) "用户" else m.sender
            "$who: ${m.content}"
        }
        val body = JSONObject().apply {
            put("model", cfg.model)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put(
                "content",
                "把下面这段多人群聊压缩成不超过 600 字的摘要,保留每个人的立场、结论和未决问题。" +
                    "只输出摘要正文。\n\n$transcript"
            )))
            put("stream", false)
        }
        runCatching {
            val req = Request.Builder().url(chatUrl(cfg.baseUrl))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching
                val digest = JSONObject(resp.body?.string().orEmpty())
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    ?.optString("content").orEmpty().trim()
                if (digest.isBlank()) return@runCatching
                // 先插摘要再删原文。反过来的话中途失败会把历史整段丢掉。
                dao.insertMessage(
                    GroupMessageEntity(
                        roomId = roomId, sender = "", content = digest, isDigest = true,
                        ts = old.last().ts   // 摘要要排在被它替换的那段位置上,不能跑到最新
                    )
                )
                dao.deleteMessagesUpTo(roomId, cutoffId)
                Log.i(TAG, "room $roomId compacted: ${old.size} msgs → digest")
            }
        }
    }

    /**
     * 把房间历史投影成【这个成员视角】的多轮对话。
     *
     * 关键在于每个成员看到的历史是不一样的:
     *  - 自己说过的话 → role=assistant,无前缀
     *  - 别人说的话   → role=user,内容前加 `[名字]: `
     *
     * 这样模型天然知道哪些话是自己说的,不必靠提示词反复叮嘱「别替别人发言」。
     * 之前把整段历史拼成一条 user 消息,模型只能从文本里猜谁是谁,弱模型很容易
     * 串台开始替别人说话。
     *
     * 另一个要点:投影时【去掉所有 @】。历史里的 @ 已经完成了路由使命,再喂给模型
     * 只会诱导它照着模仿、也去 @ 别人 —— 而被 @ 的人并不会自动接话,徒增噪音。
     * 从源头去掉比在系统提示里写「不要滥用 @」可靠得多。
     */
    private fun projectHistory(
        history: List<GroupMessageEntity>,
        ownName: String
    ): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (m in history) {
            if (m.isDigest) {
                // 摘要用一问一答对注入,让它成为合法的对话轮次;
                // 直接塞一条 user 会让后面紧跟的 user 消息连成两条同 role,部分服务端会拒。
                out += JSONObject().put("role", "user").put("content", "[之前的对话摘要]\n${m.content}")
                out += JSONObject().put("role", "assistant").put("content", "我已了解之前的对话内容。")
                continue
            }
            val isOwn = m.sender.equals(ownName, ignoreCase = true)
            val body = stripMentions(m.content)
            out += if (isOwn) {
                JSONObject().put("role", "assistant").put("content", body)
            } else {
                val who = if (m.sender.isBlank()) "用户" else m.sender
                JSONObject().put("role", "user").put("content", "[$who]: $body")
            }
        }
        return out
    }

    /** 去掉正文里的 @提及,并收拢因此产生的多余空格。 */
    private fun stripMentions(content: String): String =
        content.replace(Regex("@([^\\s@]+)"), "")
            .replace(Regex("[ \\t]{2,}"), " ")
            .trimStart()

    private fun JSONArray.putAll(items: List<JSONObject>) {
        items.forEach { put(it) }
    }

    /** 与 OpenAiClient 同一套版本段规则:base_url 自带 /v1 就不再补。 */
    private fun chatUrl(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (Regex("/v\\d+[a-zA-Z0-9]*$").containsMatchIn(b)) "$b/chat/completions"
        else "$b/v1/chat/completions"
    }
}
