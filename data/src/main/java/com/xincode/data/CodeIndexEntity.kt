package com.xincode.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 代码索引:一个符号(函数/类/方法…)的定义位置。
 *
 * 这张表的意义是把「这个符号在哪」从**读文件**变成**查表**。agent 想知道
 * `parseMarkdownBlocks` 定义在哪,以前要 grep 整个工作区再把命中的文件读进上下文;
 * 现在一次查询拿到 file:line,只在真要看实现时才读。手机上下文紧张,这个差别很大。
 */
@Entity(
    tableName = "code_symbols",
    // 三个索引对应三种查法:按名字找定义、列出某文件的符号、按工作区清理。
    // 少任何一个都会在几万行代码的工程上退化成全表扫。
    indices = [Index("name"), Index("filePath"), Index("root")]
)
data class CodeSymbolEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 属于哪个工作区。换项目时按这个清理,不会误删别的项目的索引。 */
    val root: String,
    val filePath: String,
    /** 符号名,如 `parseMarkdownBlocks`。 */
    val name: String,
    /** 带命名空间的全名,如 `com.xincode.app.MarkdownContent`。可能为空。 */
    val qualifiedName: String = "",
    /** 内核给的类型:function / class / method / interface … */
    val kind: String = "",
    val startLine: Int = 0,
    val endLine: Int = 0,
    /** 签名,如 `fun parse(usage: JSONObject): Parsed?`。可能为空。 */
    val signature: String = "",
    val indexedAt: Long = System.currentTimeMillis()
)

/**
 * 符号之间的关系:谁调用了谁、谁继承了谁、谁引用了谁。
 *
 * 「改这个函数会影响什么」靠这张表回答 —— 这是 grep 做不到的,
 * grep 只能给你文本匹配,给不了「A 调用了 B」这种结构信息。
 */
@Entity(
    tableName = "code_edges",
    indices = [Index("fromName"), Index("toName"), Index("root")]
)
data class CodeEdgeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val root: String,
    val filePath: String,
    /** calls / imports / extends / implements / references … */
    val kind: String = "",
    val fromName: String = "",
    val toName: String = "",
    val line: Int = 0
)

/** 已索引文件的指纹,用来判断哪些文件需要重新索引。 */
@Entity(tableName = "code_files", indices = [Index("root")])
data class CodeFileEntity(
    @PrimaryKey
    val filePath: String,
    val root: String,
    /**
     * 内容指纹。用「大小 + 修改时间」而不是内容哈希:
     * 手机上对几千个文件算哈希要读全部内容,慢且费电;而这两个值组合起来
     * 已经足够可靠地判断「文件变没变」。
     */
    val fingerprint: String,
    val symbolCount: Int = 0,
    val indexedAt: Long = System.currentTimeMillis()
)

@Dao
interface CodeIndexDao {

    // ---- 写入 ----

    @Insert
    suspend fun insertSymbols(items: List<CodeSymbolEntity>)

    @Insert
    suspend fun insertEdges(items: List<CodeEdgeEntity>)

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertFile(file: CodeFileEntity)

    // ---- 重新索引某个文件前先清掉它的旧数据 ----

    @Query("DELETE FROM code_symbols WHERE filePath = :path")
    suspend fun deleteSymbolsOf(path: String)

    @Query("DELETE FROM code_edges WHERE filePath = :path")
    suspend fun deleteEdgesOf(path: String)

    // ---- 查询 ----

    /**
     * 按名字找定义。前缀匹配,因为用户/模型往往只记得一半。
     * 完全匹配的排前面 —— 否则搜 `parse` 会被一堆 `parseXxx` 淹掉。
     */
    @Query("""
        SELECT * FROM code_symbols
        WHERE root = :root AND (name = :name OR name LIKE :name || '%')
        ORDER BY CASE WHEN name = :name THEN 0 ELSE 1 END, LENGTH(name), name
        LIMIT :limit
    """)
    suspend fun findByName(root: String, name: String, limit: Int = 30): List<CodeSymbolEntity>

    /** 列出某个文件里的所有符号,给「这个文件都有什么」用。 */
    @Query("SELECT * FROM code_symbols WHERE filePath = :path ORDER BY startLine")
    suspend fun symbolsOf(path: String): List<CodeSymbolEntity>

    /** 谁调用/引用了这个符号 —— 「改它会影响谁」。 */
    @Query("""
        SELECT * FROM code_edges
        WHERE root = :root AND toName = :name
        ORDER BY filePath, line LIMIT :limit
    """)
    suspend fun callersOf(root: String, name: String, limit: Int = 50): List<CodeEdgeEntity>

    /** 这个符号用到了谁 —— 「它依赖什么」。 */
    @Query("""
        SELECT * FROM code_edges
        WHERE root = :root AND fromName = :name
        ORDER BY line LIMIT :limit
    """)
    suspend fun calleesOf(root: String, name: String, limit: Int = 50): List<CodeEdgeEntity>

    @Query("SELECT * FROM code_files WHERE filePath = :path")
    suspend fun fileRecord(path: String): CodeFileEntity?

    @Query("SELECT COUNT(*) FROM code_symbols WHERE root = :root")
    suspend fun symbolCount(root: String): Int

    @Query("SELECT COUNT(*) FROM code_files WHERE root = :root")
    suspend fun fileCount(root: String): Int

    // ---- 清理 ----

    @Query("DELETE FROM code_symbols WHERE root = :root")
    suspend fun clearSymbols(root: String)

    @Query("DELETE FROM code_edges WHERE root = :root")
    suspend fun clearEdges(root: String)

    @Query("DELETE FROM code_files WHERE root = :root")
    suspend fun clearFiles(root: String)
}
