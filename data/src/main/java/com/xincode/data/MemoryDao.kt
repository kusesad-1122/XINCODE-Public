package com.xincode.data

import androidx.room.*
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * DAO for [MemoryEntity] with FTS5 full-text search.
 *
 * FTS5 virtual table [memories_fts] is created in Migration 6→7
 * with external content synced via triggers.
 */
@Dao
abstract class MemoryDao {

    /** Upsert memory. On conflict (same title) → replace (dedup). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(memory: MemoryEntity)

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    abstract suspend fun getAll(): List<MemoryEntity>

    /** 记忆按项目隔离:只取指定项目(0=全局)的记忆。 */
    @Query("SELECT * FROM memories WHERE projectId = :projectId ORDER BY updatedAt DESC")
    abstract suspend fun getAllByProject(projectId: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    abstract suspend fun getById(id: Long): MemoryEntity?

    @Query("SELECT * FROM memories WHERE title = :title LIMIT 1")
    abstract suspend fun getByTitle(title: String): MemoryEntity?

    @Query("SELECT COUNT(*) FROM memories")
    abstract suspend fun count(): Int

    @Delete
    abstract suspend fun delete(memory: MemoryEntity)

    @Query("DELETE FROM memories")
    abstract suspend fun deleteAll()

    @Query("SELECT * FROM memories WHERE embedding IS NOT NULL")
    abstract suspend fun getAllEmbedded(): List<MemoryEntity>

    /**
     * FTS4 full-text search ranked by relevance.
     * Query syntax: plain keywords (space-separated). Use "phrase" for exact phrases.
     */
    @RawQuery
    protected abstract suspend fun rawFtsSearch(query: SupportSQLiteQuery): List<MemoryEntity>

    /**
     * Search memories by FTS5 MATCH, ranked by relevance (bm25).
     * @param query space-separated keywords or FTS5 expression.
     * @param limit max results.
     */
    private fun sanitizeFts(q: String): String {
        // 仅保留字母数字空格与引号，其余 FTS4 保留字 *-:()ORAND 转为空格，避免 SQLiteException
        var s = q.replace("\"", "\"\"")
        s = s.replace(Regex("""[*\-:()]+"""), " ")
        s = s.replace(Regex("""\b(OR|AND|NOT)\b""", RegexOption.IGNORE_CASE), " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s.ifBlank { "\"\"" }
    }

    suspend fun search(query: String, limit: Int = 20): List<MemoryEntity> {
        if (query.isBlank()) return getAll().take(limit)
        val sanitized = sanitizeFts(query)
        val sql = """
            SELECT memories.* FROM memories
            JOIN memories_fts ON memories.id = memories_fts.rowid
            WHERE memories_fts MATCH ?
            ORDER BY memories.updatedAt DESC
            LIMIT ?
        """.trimIndent()
        return try { rawFtsSearch(SimpleSQLiteQuery(sql, arrayOf(sanitized, limit))) } catch (_: Exception) { emptyList() }
    }

    /** 项目隔离的 FTS 检索:只在指定项目(0=全局)内匹配。 */
    suspend fun searchByProject(query: String, projectId: Long, limit: Int = 20): List<MemoryEntity> {
        if (query.isBlank()) return getAllByProject(projectId).take(limit)
        val sanitized = sanitizeFts(query)
        val sql = """
            SELECT memories.* FROM memories
            JOIN memories_fts ON memories.id = memories_fts.rowid
            WHERE memories_fts MATCH ? AND memories.projectId = ?
            ORDER BY memories.updatedAt DESC
            LIMIT ?
        """.trimIndent()
        return try { rawFtsSearch(SimpleSQLiteQuery(sql, arrayOf(sanitized, projectId, limit))) } catch (_: Exception) { emptyList() }
    }
}