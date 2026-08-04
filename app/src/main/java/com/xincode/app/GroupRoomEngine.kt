package com.xincode.app

import android.util.Base64
import android.util.Log
import com.xincode.data.AppDatabase
import com.xincode.data.GroupMemberEntity
import com.xincode.data.GroupMessageEntity
import com.xincode.data.GroupRoomEntity
import com.xincode.core.AgentState
import com.xincode.tools.WorkspaceContext
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
     *
     * 房间设成无上限时这两个闸都不生效,唯一的终止条件是「没人再被 @」或者用户点停止。
     */
    private const val MAX_REPLIES_PER_CHAIN = 12

    /** 同一个成员在一条链里最多被叫醒几次。防两人来回对喷占满整条链。 */
    private const val MAX_TURNS_PER_MEMBER = 3

    /**
     * 无上限模式下仍然保留的硬顶。
     *
     * 「无上限」是指不再按跳数/条数提前刹车,由用户自己决定什么时候停 —— 但完全不设顶
     * 是不负责任的:两个成员互相 @ 是个真实存在的闭环,用户睡着了或者切后台没看着,
     * 它能一路烧到额度见底。这个数字大到正常讨论碰不到,只在真跑飞了的时候兜底。
     */
    private const val RUNAWAY_CEILING = 500

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
        /**
         * 完全访问模式下,让成员在自己的工作会话里跑一轮并返回汇报。
         * 传 null 则强制退回轻量模式(只说话,不动手)。
         */
        runWorkTurn: (suspend (sessionId: Long, prompt: String, workspace: String) -> String)? = null,
        /** 确保这个成员有工作会话,返回会话 id。完全访问模式下必需。 */
        ensureWorkSession: (suspend (GroupMemberEntity) -> Long)? = null,
        /** 每次状态变化告诉 UI 现在轮到谁在说话,空串表示没人在说。 */
        onSpeaking: ((String) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val dao = database.groupRoomDao()
        var members = dao.getMembers(roomId)
        if (members.isEmpty()) return@withContext
        val room = dao.getRoom(roomId)

        val allowChain = room?.allowMemberMentions ?: true
        // 0 是「无上限」的哨兵值,不能被 coerceIn 夹成 1 —— 那样开关就失效了
        val rawHops = room?.maxHops ?: 3
        val maxHops = if (rawHops == GroupRoomEntity.UNLIMITED_HOPS) rawHops
        else rawHops.coerceIn(1, 20)
        val fullAccess = (room?.fullAccess ?: false) &&
            runWorkTurn != null && ensureWorkSession != null

        // 完全访问模式下先把工作会话补齐,并把房间工作目录建出来 ——
        // 目录不存在的话成员第一次写文件就会失败,而那种失败对模型很难自查。
        if (fullAccess) {
            runCatching { java.io.File(workspaceOf(room)).mkdirs() }
            members = members.map { m ->
                if (m.workSessionId > 0) m
                else m.copy(workSessionId = ensureWorkSession!!(m))
            }
        }

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
                if (fullAccess) respondWithTools(database, roomId, member, members, room, runWorkTurn!!)
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
        /** 跳数上限;[GroupRoomEntity.UNLIMITED_HOPS] 表示无上限。 */
        maxHops: Int,
        speak: suspend (name: String) -> String?
    ): Int {
        val unlimited = maxHops == GroupRoomEntity.UNLIMITED_HOPS

        // 队列元素:(这句话的内容, 谁说的, 这句话处在第几跳)
        val queue = ArrayDeque<Triple<String, String, Int>>()
        queue += Triple(seedContent, seedSender, 0)

        var repliesMade = 0
        val turnsByMember = mutableMapOf<String, Int>()

        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (text, from, hop) = queue.removeFirst()
            if (!unlimited && hop >= maxHops) continue

            val targets = MentionRouting
                .resolveTargets(memberNames, text, from)
                .take(MAX_TARGETS_PER_TURN)
            if (targets.isEmpty()) continue

            for (name in targets) {
                currentCoroutineContext().ensureActive()
                // 无上限模式只受跑飞兜底约束;正常模式受总量闸约束
                val budget = if (unlimited) RUNAWAY_CEILING else MAX_REPLIES_PER_CHAIN
                if (repliesMade >= budget) {
                    Log.w(TAG, "chain stopped at $repliesMade replies (unlimited=$unlimited)")
                    queue.clear()
                    break
                }
                val used = turnsByMember.getOrDefault(name, 0)
                if (!unlimited && used >= MAX_TURNS_PER_MEMBER) continue
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
        room: GroupRoomEntity?,
        runWorkTurn: suspend (sessionId: Long, prompt: String, workspace: String) -> String
    ): String {
        val dao = database.groupRoomDao()
        val identity = if (member.identityId > 0)
            database.identityDao().getById(member.identityId) else null

        val history = dao.getMessages(roomId).takeLast(30).joinToString("\n") { m ->
            val who = if (m.sender.isBlank()) "用户" else m.sender
            "$who: ${m.content}"
        }
        val roster = allMembers.joinToString("、") { "@${it.displayName}" }
        val workspace = workspaceOf(room)

        val prompt = buildString {
            append("你在一个多人群聊里,你的名字是「${member.displayName}」。\n")
            append("群成员:$roster,以及用户。\n")
            identity?.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                append("\n你的角色设定:\n").append(it).append("\n")
            }
            append("\n## 工作目录\n")
            append("这个团队的共享工作区是 `$workspace`。\n")
            append("要产出文档、设计稿、代码,一律写到这个目录里,别人才读得到你的东西。\n")
            append("动手之前先看看目录里已经有什么 —— 别人可能已经写过了。\n")
            append("\n## 群里到目前为止的对话\n").append(history).append("\n\n")
            append("## 现在轮到你\n")
            append("你可以用工具去查证、读写文件、动手做事。这些过程都留在你自己这条工作会话里,")
            append("群里的人看不到,所以不用怕啰嗦。\n\n")

            // 这条必须写死。实测模型会先说一大段计划、再一口气把十几个工具调完,
            // 用户看到的是「一堵文字 + 底下一串操作」,完全不知道它中途在干什么、卡在哪。
            append("**怎么干活**:一句话说你要做什么 → 做那一步 → 说结果 → 再做下一步。\n")
            append("不要先写一大段计划再一口气把工具全调完 —— 那样别人只看得到一堵文字和一串操作,")
            append("中途出问题也认不出是哪一步。工具失败了就说清楚原因和你打算怎么绕,再动手。\n\n")

            // 实测踩过的坑:成员在工作会话中间 @ 了人,那段话根本不进群,于是没人被叫醒,
            // 用户看着像「他说完就没下文了」。必须讲清楚只有最后一段会进群。
            append("**@ 只在最后一段有效**:你在过程中间 @ 谁都不会真的叫醒他,")
            append("因为进群的只有你最后那段汇报。要找人接手,就把 @ 写在汇报里。\n\n")

            append("做完之后,**最后一段话**是你要发到群里的汇报 —— 那一段要简短、只讲结论和下一步,")
            append("不要把中间过程复述一遍。需要谁接着做就 @ 他的名字。不要写自己的名字当前缀。")
        }

        val sessionId = member.workSessionId
        if (sessionId <= 0) return "(${member.displayName} 还没有工作会话)"
        return runWorkTurn(sessionId, prompt, workspace).trim()
    }

    /**
     * 房间的工作目录。没显式配就按房间名落在工作区根的 rooms/ 下。
     *
     * 房间名会进路径,所以必须洗掉路径分隔符和别的危险字符 —— 一个叫「a/../b」的
     * 房间不该能把文件写到工作区外面去。
     */
    fun workspaceOf(room: GroupRoomEntity?): String {
        room?.workspacePath?.takeIf { it.isNotBlank() }?.let { configured ->
            val legacy = WorkspaceContext.LEGACY_SHARED_ROOT
            return if (configured == legacy || configured.startsWith("$legacy/")) {
                WorkspaceContext.defaultRoot + configured.removePrefix(legacy)
            } else configured
        }
        val safe = (room?.name ?: "room")
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace("..", "_")
            .trim()
            .ifBlank { "room" }
        return "${WorkspaceContext.workspaceRoot.trimEnd('/')}/rooms/$safe"
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
            val json = JSONObject(text)

            // 群聊这条路径【不走 OpenAiClient】,所以记账也得自己来 ——
            // 漏了这一步的后果就是:一屋子成员聊了半天烧掉一大笔,
            // 用量分析页却显示「还没有用量记录」,账对不上。
            json.optJSONObject("usage")?.let { usage ->
                UsageRecorder.record(
                    database = database,
                    usage = usage,
                    sessionId = -roomId,   // 负数避开主对话的 sessionId,便于区分来源
                    model = model,
                    provider = cfg.name,
                    source = "group"
                )
            }

            return json.optJSONArray("choices")
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
