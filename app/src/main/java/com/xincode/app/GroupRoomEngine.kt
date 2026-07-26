package com.xincode.app

import android.util.Base64
import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.GroupMemberEntity
import com.xincode.data.GroupMessageEntity
import com.xincode.core.AgentState
import com.xincode.security.KeystoreProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
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

    /** 一条消息最多叫醒几个成员。@all 时也受这个上限约束。 */
    private const val MAX_TARGETS_PER_TURN = 6

    /**
     * 一条用户发言引发的连锁里,总共最多产生多少条回复。
     *
     * 跳数上限([GroupRoomEntity.maxHops])管的是「链有多深」,这个管的是「树有多大」——
     * 光限深度不够:3 跳但每跳 @all,6 个成员就是 6+36+216 条。两个闸都要有。
     */
    private const val MAX_REPLIES_PER_CHAIN = 12

    /** 同一个成员在一条链里最多被叫醒几次。防两人来回对喷占满整条链。 */
    private const val MAX_TURNS_PER_MEMBER = 3

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
        onReply: (suspend () -> Unit)? = null,
        /** 完全访问模式下用它造带工具的 agent。传 null 则强制退回轻量模式。 */
        agentFactory: (() -> com.xincode.core.AgentCore)? = null,
        /** 每次状态变化告诉 UI 现在轮到谁在说话,空串表示没人在说。 */
        onSpeaking: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val dao = database.groupRoomDao()
        val members = dao.getMembers(roomId)
        if (members.isEmpty()) return@withContext
        val room = dao.getRoom(roomId)

        val allowChain = room?.allowMemberMentions ?: true
        val maxHops = (room?.maxHops ?: 3).coerceIn(1, 8)
        val fullAccess = (room?.fullAccess ?: false) && agentFactory != null

        driveChain(
            memberNames = members.map { it.displayName },
            seedContent = content,
            seedSender = senderName,
            allowChain = allowChain,
            maxHops = maxHops
        ) { name ->
            val member = members.firstOrNull { it.displayName == name } ?: return@driveChain null
            onSpeaking?.invoke(name)

            val reply = runCatching {
                if (fullAccess) respondWithTools(database, roomId, member, members, agentFactory!!)
                else respondTo(database, keystore, roomId, member, members, allowChain)
            }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "member $name failed: ${e.message}")
                "($name 回复失败:${e.message?.take(80)})"
            }
            if (reply.isBlank()) return@driveChain null

            // 落库要挡住取消:这句话已经花过钱了,不能因为用户此刻点停止就丢掉。
            withContext(NonCancellable) {
                dao.insertMessage(
                    GroupMessageEntity(roomId = roomId, sender = name, content = reply)
                )
            }
            onReply?.invoke()
            reply
        }

        onSpeaking?.invoke("")
        maybeCompact(database, keystore, roomId)
    }

    /**
     * 连锁调度:谁被 @ 了就让谁说话,他说的话里再 @ 谁就继续往下传,直到撞上闸门。
     *
     * 单独抽出来是因为这段是【防失控的全部逻辑】,而且它必须能在没有网络的情况下被测到——
     * 混在网络请求里就只能靠肉眼审。三道闸缺一不可:
     *
     *  - **跳数** [maxHops]:链有多深。防 A→B→A→B 无限传下去。
     *  - **总量** [MAX_REPLIES_PER_CHAIN]:树有多大。光限深度不够,3 跳但每跳 @all,
     *    6 个成员就是 6+36+216 条。
     *  - **单人次数** [MAX_TURNS_PER_MEMBER]:防两个人在一条链里来回对喷把预算占满。
     *
     * 外加 [ensureActive]:用户点停止时,链在两次发言【之间】干净断开。
     *
     * @param speak 让某人说话,返回他说的内容;返回 null 表示这次没产出(不计数、不续接)。
     * @return 这条链一共产生了多少条发言。
     */
    internal suspend fun driveChain(
        memberNames: List<String>,
        seedContent: String,
        seedSender: String,
        allowChain: Boolean,
        maxHops: Int,
        speak: suspend (name: String) -> String?
    ): Int {
        // 队列元素:(这句话的内容, 谁说的, 这句话处在第几跳)
        val queue = ArrayDeque<Triple<String, String, Int>>()
        queue += Triple(seedContent, seedSender, 0)

        var repliesMade = 0
        val turnsByMember = mutableMapOf<String, Int>()

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (text, from, hop) = queue.removeFirst()
            if (hop >= maxHops) continue

            val targets = MentionRouting
                .resolveTargets(memberNames, text, from)
                .take(MAX_TARGETS_PER_TURN)
            if (targets.isEmpty()) continue

            for (name in targets) {
                currentCoroutineContext().ensureActive()
                if (repliesMade >= MAX_REPLIES_PER_CHAIN) {
                    queue.clear()
                    break
                }
                val used = turnsByMember.getOrDefault(name, 0)
                if (used >= MAX_TURNS_PER_MEMBER) continue
                turnsByMember[name] = used + 1

                val reply = speak(name) ?: continue
                if (reply.isBlank()) continue
                repliesMade++

                // 成员回复里的 @ 是否续接下一跳,由房间开关决定。
                // 关着时整个群聊只能靠用户一句一句推,谁都不接话 —— 那是缺陷,不是安全。
                if (allowChain) queue += Triple(reply, name, hop + 1)
            }
        }
        return repliesMade
    }

    /**
     * 完全访问模式:让成员走一条完整的 agent 工具回环,能联网、读写文件、执行命令。
     *
     * 与轻量模式的取舍写在 [GroupRoomEntity.fullAccess] 上。这里把群聊历史压成一段
     * 交待塞进 prompt,而不是接 AgentCore 的会话历史 —— 群聊的历史是多人共享的,
     * 塞进单 agent 的会话游标里会和它自己的工具轮次搅在一起。
     */
    private suspend fun respondWithTools(
        database: AppDatabase,
        roomId: Long,
        member: GroupMemberEntity,
        allMembers: List<GroupMemberEntity>,
        agentFactory: () -> com.xincode.core.AgentCore
    ): String {
        val dao = database.groupRoomDao()
        val identity = if (member.identityId > 0)
            database.identityDao().getById(member.identityId) else null

        val history = dao.getMessages(roomId).takeLast(30).joinToString("\n") { m ->
            val who = if (m.sender.isBlank()) "用户" else m.sender
            "$who: ${m.content}"
        }
        val roster = allMembers.joinToString("、") { "@${it.displayName}" }
        val prompt = buildString {
            append("你在一个多人群聊里,你的名字是「${member.displayName}」。\n")
            append("群成员:$roster,以及用户。\n")
            identity?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                append("\n你的角色设定:\n").append(it).append("\n")
            }
            append("\n以下是群里到目前为止的对话:\n").append(history).append("\n\n")
            append("现在轮到你发言。你可以使用工具去查证或动手,完成后只输出你要在群里说的那句话。")
            append("不要写自己的名字当前缀,系统会标注。需要谁接着做,就 @ 他的名字。")
        }

        val core = agentFactory()
        val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())
        core.run(prompt, scope, thinkingEnabled = false, thinkingLevel = 1).join()
        core.state.first { it is AgentState.Idle || it is AgentState.Error }
        return core.lastAssistantText().orEmpty().trim()
    }

    /** 让某个成员基于房间历史说一句话。 */
    private suspend fun respondTo(
        database: AppDatabase,
        keystore: KeystoreProvider,
        roomId: Long,
        member: GroupMemberEntity,
        allMembers: List<GroupMemberEntity>,
        allowChain: Boolean
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
            if (allowChain) {
                // 开了链式路由,@ 就是一个真实动作而不是修辞 —— 必须讲清楚它的后果,
                // 否则模型要么不敢用(话题推不下去),要么见人就 @(一句话炸出一群回复)。
                append("- 你可以用 @名字 点名让某个成员接着说,他会真的被叫起来回答。\n")
                append("- 正因为会真把人叫起来,只在确实需要那个人时才 @,一次一般只 @ 一个。\n")
                append("- 话题已经聊完、或者该由用户拍板时,就不要再 @ 任何人,让它停下来。\n")
            } else {
                append("- 不要 @ 别人,本房间关闭了成员之间的互相召唤,@ 了也没人会应。\n")
            }
            identity?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                append("\n你的角色设定:\n").append(it).append("\n")
            }
        }

        val history = dao.getMessages(roomId)
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        messages.putAll(projectHistory(history, member.displayName, keepMentions = allowChain))
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
     * 另一个要点:@ 要不要留,取决于房间开没开成员互相召唤。
     *  - 关着:去掉。历史里的 @ 已经完成路由使命,再喂给模型只会诱导它照着模仿,
     *    而被 @ 的人根本不会接话,纯噪音。
     *  - 开着:保留。这时 @ 是一个有真实后果的动作,把它从历史里抹掉,模型就看不到
     *    「这里的人是怎么互相点名的」,也就学不会用 —— 讨论照样推不下去。
     */
    private fun projectHistory(
        history: List<GroupMessageEntity>,
        ownName: String,
        keepMentions: Boolean
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
            val body = if (keepMentions) m.content else stripMentions(m.content)
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
