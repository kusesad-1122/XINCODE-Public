package com.xincode.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getBySessionId(sessionId: Long): List<MessageEntity>

    /**
     * 观察某条会话的消息。群聊里内嵌的「工作台」用它 ——
     * 成员正在干活时那条会话在被实时写入,轮询或一次性查都看不到进度。
     */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeBySessionId(sessionId: Long): kotlinx.coroutines.flow.Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    @Query("UPDATE messages SET reasoning = :reasoning WHERE id = :id")
    suspend fun updateReasoning(id: Long, reasoning: String)

    @Query("UPDATE messages SET promptTokens = :pt, cacheHitTokens = :hit, cacheMissTokens = :miss, completionTokens = :ct WHERE id = :id")
    suspend fun updateUsage(id: Long, pt: Long?, hit: Long?, miss: Long?, ct: Long?)

    @Query("UPDATE messages SET sessionId = :sessionId WHERE id = :id")
    suspend fun updateSessionId(id: Long, sessionId: Long)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Session-scoped token totals. Nullable Long because SUM over an empty column set
     * returns NULL, not 0 — Room returns null cleanly rather than crashing.
     */
    data class TokenTotals(
        val prompt: Long?,
        val cacheHit: Long?,
        val cacheMiss: Long?,
        val completion: Long?
    )

    @Query("SELECT SUM(promptTokens) as prompt, SUM(cacheHitTokens) as cacheHit, SUM(cacheMissTokens) as cacheMiss, SUM(completionTokens) as completion FROM messages WHERE sessionId = :sessionId")
    suspend fun getSessionTokenTotals(sessionId: Long): TokenTotals

    /** Search assistant + user messages across all sessions for a keyword. Used by sidebar search. */
    @Query("""
        SELECT * FROM messages
        WHERE role IN ('user','assistant')
          AND content LIKE '%' || :q || '%'
          AND sessionId NOT IN (SELECT workSessionId FROM group_members WHERE workSessionId > 0)
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun searchByKeyword(q: String, limit: Int = 40): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}
