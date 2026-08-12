package com.xincode.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 群聊房间:多个智能体同处一室,用 `@名字` 点谁谁回答。
 *
 * 与「子智能体派发」的区别:那边是主脑把活派下去、结果汇总回主脑,子智能体之间互相看不见;
 * 这边是所有成员共享同一份可见的对话历史,你可以让它们围绕同一个话题各说各的。
 */
@Entity(tableName = "group_rooms")
data class GroupRoomEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val note: String = "",
    /** 历史超过这个 token 数就自动压缩一次。0 = 用默认值。 */
    val compactThreshold: Int = 0,

    /**
     * 成员之间是否可以互相 @ 触发。
     *
     * 关掉时只有用户的 @ 能叫人,成员回复里的 @ 是死的 —— 安全,但话题推不下去:
     * 谁都不接话,群聊就断在第一轮(「秘书说了『大家聊聊』然后没人应」就是这么来的)。
     * 打开后成员 @ 别人会真把人叫起来,讨论才能自己滚动,代价是必须有 [maxHops]
     * 和停止按钮兜着。
     */
    val allowMemberMentions: Boolean = true,

    /**
     * 一条消息最多能引发几跳连锁。用户发言是第 0 跳。
     *
     * 防「两个成员无限对聊烧光额度」的硬闸 —— 到数就停,不管它们聊得多起劲。
     *
     * 设成 [UNLIMITED_HOPS] 则不按跳数刹车,讨论一直往下走到没人再被 @ 为止,
     * 什么时候收由你点停止决定。引擎里仍有一个大到正常碰不到的跑飞兜底,
     * 因为「互相 @」是个真实闭环,人不看着的时候它能一路烧到额度见底。
     */
    val maxHops: Int = 3,

    /**
     * 完全访问:成员能不能调用工具(联网、读写文件、执行命令)。
     *
     * 默认关。关着时成员只凭上下文说话,一次请求出一句,轻量可控;
     * 打开后每个成员各走一条完整的工具回环,能真干活,但更慢更贵,
     * 而且它们的动作会落到真实环境里。
     */
    val fullAccess: Boolean = false,

    /**
     * 这屋人干活的目录。空 = 按房间名自动落在 `<工作区根>/rooms/<房间名>/`。
     *
     * 房间内【共享】而不是每人一个私有目录:他们本来就在协作,架构师写的方案工程师
     * 必须读得到,给每人一个隔离目录只会让产出散落各处、谁也看不见谁的。
     * 同一批被 @ 的成员会并行发言,所以成员之间可能同时写这个目录 ——
     * 各自写各自的文件没问题,但不要假设「上一刻只有一个人在动」。
     */
    val workspacePath: String = "",

    /**
     * 滚动总结:开时每隔 [summaryEveryTurns] 轮把「旧总结 + 新增消息」合并成新总结,
     * 历史投影只保留总结 + 游标之后的原文。关时退回旧的字符阈值一次性压缩。
     */
    val summaryEnabled: Boolean = true,
    /** 每累计多少轮用户发言触发一次滚动总结。 */
    val summaryEveryTurns: Int = 20,
    /** 总结专用模型;空 = 跟随当前活跃配置。 */
    val summaryModel: String = "",

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /** [maxHops] 取这个值表示不按跳数刹车,由用户手动停。 */
        const val UNLIMITED_HOPS = 0
    }
}

/**
 * 房间成员。一个成员 = 一个身份卡 + 一个在房间里的显示名。
 *
 * 显示名要独立于身份卡名字:同一张「代码评审」卡可以在房间里放两份,
 * 分别叫「评审甲」「评审乙」让它们对着吵;共用一个名字 @ 就分不清了。
 */
// 索引必须在实体上声明,只在迁移 SQL 里 CREATE INDEX 会让 Room 升级校验失败(见 UsageRecordEntity 的说明)。
@Entity(tableName = "group_members", indices = [Index("roomId")])
data class GroupMemberEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val roomId: Long,
    /** 房间内的 @名字,房间内唯一。 */
    val displayName: String,
    /** 关联的身份卡;0 = 用默认系统提示。 */
    val identityId: Long = 0,
    /** 指定的供应商配置;0 = 跟随活跃配置。 */
    val providerConfigId: Long = 0,
    val model: String = "",

    /**
     * 这个成员自己的工作会话 id;0 = 还没建过。
     *
     * 群聊里只该出现【结论】。成员真干活时的那些工具调用、试错、中间稿子全都放进
     * 这条独立会话 —— 你想看他现在在干什么就点进去看,不想看时群里就是干净的汇报。
     * 把过程和结论混在一条流里,人读群聊会非常累(工程师贴一整张工时表就把讨论淹了)。
     */
    val workSessionId: Long = 0,

    val createdAt: Long = System.currentTimeMillis()
)

/** 房间里的一条消息。sender 为空串表示是用户发的。 */
@Entity(tableName = "group_messages", indices = [Index("roomId")])
data class GroupMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val roomId: Long,
    /** 空 = 用户;否则是成员的 displayName。 */
    val sender: String = "",
    val content: String,
    /**
     * 这条消息回复引用的原消息 id;0 = 没有引用。
     *
     * sender/content 是引用时的快照,不是外键 —— 被引用的消息可能被自动压缩删掉,
     * 但引用块仍然要能正常渲染,模型上下文也要能看出「这是在回谁」。
     */
    val replyToId: Long = 0,
    /** 被引用消息的发送者;空 = 用户。 */
    val replyToSender: String = "",
    /** 被引用消息的正文快照。 */
    val replyToContent: String = "",
    /** 同一次成员回复的稳定分组 id;工具事件、diff 与最终正文共用,用于规范排序。 */
    val runId: String = "",
    /**
     * 同一 run 内的展示顺序:0=用户消息,1=工具调用,2=工具结果/diff,3=助手最终正文。
     * 排序先按 run 的起始时间分组,组内再按 phase,避免并行回复与工具事件交错。
     */
    val phase: Int = 0,
    /** 消息类型:message=普通对话,toolcall=工具调用,toolresult=工具结果,diff=工作区变更。 */
    val kind: String = "message",
    /** 是否正在流式写入(前端据此显示光标与实时内容)。 */
    val streaming: Boolean = false,
    /** 被用户打断/失败中止。 */
    val interrupted: Boolean = false,
    /** 流式过程中的思考内容快照。 */
    val reasoning: String = "",
    /** 生成这条回复用的模型,前端展示在成员名旁边。 */
    val model: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    /** 摘要消息(自动压缩产生的)标记,渲染时区别对待。 */
    val isDigest: Boolean = false,
    val ts: Long = System.currentTimeMillis()
)

/**
 * 房间的滚动总结状态。
 *
 * summaryThroughMessageId 是游标:总结覆盖到哪条消息为止。历史投影 = 总结 + 游标之后的原文,
 * 总结失败时保留旧版本并在 lastError 记录原因,不阻塞聊天。
 */
@Entity(tableName = "group_room_summaries")
data class GroupRoomSummaryEntity(
    @PrimaryKey
    val roomId: Long,
    val summary: String = "",
    val summaryThroughMessageId: Long = 0,
    val summaryThroughMessageTimestamp: Long = 0,
    val summarizedTurnCount: Int = 0,
    /** idle / summarizing / success / failed。 */
    val status: String = "idle",
    val version: Int = 0,
    val updatedAt: Long = 0,
    val lastError: String = ""
)

@Dao
interface GroupRoomDao {

    @Query("SELECT * FROM group_rooms ORDER BY updatedAt DESC")
    fun observeRooms(): Flow<List<GroupRoomEntity>>

    @Query("SELECT * FROM group_rooms WHERE id = :id")
    suspend fun getRoom(id: Long): GroupRoomEntity?

    /** 房间设置改了要立刻反映到聊天页(开关状态、完全访问角标)。 */
    @Query("SELECT * FROM group_rooms WHERE id = :id")
    fun observeRoom(id: Long): Flow<GroupRoomEntity?>

    /** 预制团队查重用:同名房间已存在就不重复建一屋子人。 */
    @Query("SELECT * FROM group_rooms WHERE name = :name LIMIT 1")
    suspend fun getRoomByName(name: String): GroupRoomEntity?

    @Insert
    suspend fun insertRoom(room: GroupRoomEntity): Long

    @Update
    suspend fun updateRoom(room: GroupRoomEntity)

    @Delete
    suspend fun deleteRoom(room: GroupRoomEntity)

    // 房间删了成员和消息要一起清,否则会留下永远看不到的孤儿行
    @Query("DELETE FROM group_members WHERE roomId = :roomId")
    suspend fun deleteMembersOf(roomId: Long)

    @Query("DELETE FROM group_messages WHERE roomId = :roomId")
    suspend fun deleteMessagesOf(roomId: Long)

    @Query("SELECT * FROM group_members WHERE roomId = :roomId ORDER BY createdAt ASC")
    fun observeMembers(roomId: Long): Flow<List<GroupMemberEntity>>

    @Query("SELECT * FROM group_members WHERE roomId = :roomId ORDER BY createdAt ASC")
    suspend fun getMembers(roomId: Long): List<GroupMemberEntity>

    /** 按工作会话反查成员:App 重启后确认卡也能定位回房间。 */
    @Query("SELECT * FROM group_members WHERE workSessionId = :sessionId LIMIT 1")
    suspend fun getMemberByWorkSession(sessionId: Long): GroupMemberEntity?

    /** Startup repair source: every member whose internal work session must be isolated. */
    @Query("SELECT * FROM group_members WHERE workSessionId > 0 ORDER BY roomId, createdAt ASC")
    suspend fun getMembersWithWorkSessions(): List<GroupMemberEntity>

    @Insert
    suspend fun insertMember(member: GroupMemberEntity): Long

    /** 改成员的供应商/模型绑定 —— 每个角色该用什么档次的模型是不一样的。 */
    @Update
    suspend fun updateMember(member: GroupMemberEntity)

    @Delete
    suspend fun deleteMember(member: GroupMemberEntity)

    @Query("SELECT * FROM group_messages WHERE roomId = :roomId ORDER BY ts ASC, id ASC")
    fun observeMessages(roomId: Long): Flow<List<GroupMessageEntity>>

    @Query("SELECT * FROM group_messages WHERE roomId = :roomId ORDER BY ts ASC, id ASC")
    suspend fun getMessages(roomId: Long): List<GroupMessageEntity>

    @Insert
    suspend fun insertMessage(message: GroupMessageEntity): Long

    /** 流式回复的原地更新:内容、思考、完成/中断状态与用量一次写完。 */
    @Query(
        """
        UPDATE group_messages
        SET content = :content, reasoning = :reasoning,
            streaming = :streaming, interrupted = :interrupted,
            promptTokens = :promptTokens, completionTokens = :completionTokens,
            cacheHitTokens = :cacheHitTokens, cacheMissTokens = :cacheMissTokens
        WHERE id = :id AND roomId = :roomId
        """
    )
    suspend fun updateMessageStream(
        id: Long,
        roomId: Long,
        content: String,
        reasoning: String,
        streaming: Boolean,
        interrupted: Boolean,
        promptTokens: Int,
        completionTokens: Int,
        cacheHitTokens: Int,
        cacheMissTokens: Int
    )

    /** 用户打断/停止时,把所有仍在流式的消息标记为已中断。 */
    @Query("UPDATE group_messages SET streaming = 0, interrupted = 1 WHERE roomId = :roomId AND streaming = 1")
    suspend fun markStreamingInterrupted(roomId: Long)

    @Query("SELECT * FROM group_room_summaries WHERE roomId = :roomId")
    suspend fun getSummary(roomId: Long): GroupRoomSummaryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: GroupRoomSummaryEntity)

    @Query("DELETE FROM group_room_summaries WHERE roomId = :roomId")
    suspend fun deleteSummary(roomId: Long)

    @Query("DELETE FROM group_messages WHERE roomId = :roomId AND id <= :maxId")
    suspend fun deleteMessagesUpTo(roomId: Long, maxId: Long)
}
