package com.xincode.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
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
     * 防「两个成员无限对聊烧光额度」的硬闸,不是建议值 —— 到数就停,
     * 不管它们聊得多起劲。
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

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

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
    /** 摘要消息(自动压缩产生的)标记,渲染时区别对待。 */
    val isDigest: Boolean = false,
    val ts: Long = System.currentTimeMillis()
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

    @Query("DELETE FROM group_messages WHERE roomId = :roomId AND id <= :maxId")
    suspend fun deleteMessagesUpTo(roomId: Long, maxId: Long)
}
