package com.xincode.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 守住「迁移建出来的库」和「实体声明的库」必须长得一模一样。
 *
 * ## 为什么需要这个测试
 *
 * Room 升级时会拿实体声明和数据库实况逐项比对,**索引集合也在比对范围内**。
 * 两边对不上就抛 IllegalStateException,而且 `fallbackToDestructiveMigration()`
 * 在这一步【完全不介入】—— 它只兜底「找不到迁移路径」,迁移跑完后的校验失败被 Room
 * 认定为开发者写错了迁移,必须让人看见。
 *
 * 后果:一个疏忽就能让所有老用户永远打不开 App。而且编译期毫无提示、全新安装
 * 完全正常(全新安装走 Room 自己建表,压根不做比对),只有装了旧版的人会中招 ——
 * 恰恰是开发机上最不容易复现的那种。
 *
 * ## 这个测试怎么做
 *
 * 直接读源码文本比对,不需要模拟器:
 * - 从 AppDatabase.kt 的迁移 SQL 里按版本顺序回放 CREATE INDEX / DROP INDEX,
 *   算出「一路升级上来的库里最终会有哪些索引」
 * - 从各个 @Entity 注解里算出「Room 认为应该有哪些索引」
 * - 两边必须完全相等
 *
 * 注意只统计 `index_` 前缀的索引:Room 读取数据库索引时会过滤这个前缀,
 * 其它名字(比如 `idx_xxx`)它看不见,自然也不参与比对。
 */
class SchemaConsistencyTest {

    private val dataSrc = File("src/main/java/com/xincode/data")

    /** Room 只认这个前缀的索引,别的名字它读都不读。 */
    private val ROOM_INDEX_PREFIX = "index_"

    /** 迁移建的索引与实体声明的索引必须一致。 */
    @Test
    fun migrationIndicesMatchEntityIndices() {
        val fromMigrations = indicesAfterReplayingMigrations()
        val fromEntities = indicesDeclaredOnEntities()

        val onlyInMigrations = (fromMigrations.keys - fromEntities.keys).sorted()
        val onlyInEntities = (fromEntities.keys - fromMigrations.keys).sorted()

        val problems = buildList {
            onlyInMigrations.forEach {
                add("迁移里建了索引 `$it`(表 ${fromMigrations[it]}),但对应 @Entity 没有声明 " +
                    "indices —— 老用户升级时 Room 校验会失败,启动即崩")
            }
            onlyInEntities.forEach {
                add("@Entity 声明了索引 `$it`(表 ${fromEntities[it]}),但迁移 SQL 里没有 " +
                    "CREATE INDEX —— 老用户升级后缺这个索引,Room 校验同样会失败")
            }
        }

        assertEquals(
            "索引声明不一致,会导致升级用户【打开即闪退】:\n" + problems.joinToString("\n") { "  - $it" },
            emptyList<String>(),
            problems
        )
    }

    /** 每张迁移里建的表都要有对应实体。 */
    @Test
    fun everyMigratedTableHasAnEntity() {
        val tablesInMigrations = Regex(
            """CREATE TABLE(?:\s+IF NOT EXISTS)?\s+`?(\w+)`?\s*\(""",
            RegexOption.IGNORE_CASE
        ).findAll(appDatabaseSource()).map { it.groupValues[1] }.toSet()

        val tablesOnEntities = entityDeclarations().map { it.table }.toSet()

        // 虚拟表(FTS)由 createFts5Tables 单独建,不走实体声明,排除掉。
        val ftsTables = Regex("""CREATE VIRTUAL TABLE(?:\s+IF NOT EXISTS)?\s+`?(\w+)`?""",
            RegexOption.IGNORE_CASE)
            .findAll(appDatabaseSource()).map { it.groupValues[1] }.toSet()

        val orphans = (tablesInMigrations - tablesOnEntities - ftsTables).sorted()
        assertEquals(
            "迁移里建了这些表但找不到对应 @Entity(要么表名写错了,要么实体被删了没清迁移):$orphans",
            emptyList<String>(), orphans
        )
    }

    // ---------------------------------------------------------------- 解析

    private fun appDatabaseSource(): String =
        File(dataSrc, "AppDatabase.kt").also {
            check(it.exists()) { "找不到 AppDatabase.kt,当前工作目录=${File(".").absolutePath}" }
        }.readText()

    /**
     * 按版本号顺序回放所有迁移里的 CREATE INDEX / DROP INDEX,得到最终索引集合。
     *
     * 必须按顺序回放而不是简单收集:有的索引先建后删(比如 memories 的唯一约束
     * 从 title 改成 (title, projectId) 时就 DROP 掉了旧的),只做收集会误判。
     */
    private fun indicesAfterReplayingMigrations(): Map<String, String> {
        val src = appDatabaseSource()

        // 把源码按 `Migration(a, b)` 切成段,记下每段的起始版本用于排序
        val blockStarts = Regex("""Migration\((\d+),\s*(\d+)\)""").findAll(src)
            .map { it.groupValues[1].toInt() to it.range.first }
            .toList()
            .sortedBy { it.second }

        val ordered = blockStarts.mapIndexed { i, (fromVersion, start) ->
            val end = blockStarts.getOrNull(i + 1)?.second ?: src.length
            fromVersion to src.substring(start, end)
        }.sortedBy { it.first }

        val createRe = Regex(
            """CREATE\s+(?:UNIQUE\s+)?INDEX(?:\s+IF NOT EXISTS)?\s+`?(\w+)`?\s+ON\s+`?(\w+)`?""",
            RegexOption.IGNORE_CASE
        )
        val dropRe = Regex(
            """DROP\s+INDEX(?:\s+IF EXISTS)?\s+`?(\w+)`?""",
            RegexOption.IGNORE_CASE
        )

        val live = linkedMapOf<String, String>()   // 索引名 -> 表名
        for ((_, block) in ordered) {
            // 同一段里 DROP 与 CREATE 的先后有讲究,按它们在文本里的位置依次处理
            val events = (createRe.findAll(block).map { it.range.first to ('C' to it) } +
                dropRe.findAll(block).map { it.range.first to ('D' to it) })
                .sortedBy { it.first }
            for ((_, ev) in events) {
                val (kind, m) = ev
                val name = m.groupValues[1]
                if (!name.startsWith(ROOM_INDEX_PREFIX)) continue  // Room 看不见的索引不参与比对
                if (kind == 'C') live[name] = m.groupValues[2] else live.remove(name)
            }
        }
        return live
    }

    private data class EntityDecl(val table: String, val indicesArg: String?)

    /** 扫描 data 模块所有 kt 文件里的 @Entity 注解。 */
    private fun entityDeclarations(): List<EntityDecl> {
        val result = mutableListOf<EntityDecl>()
        val entityRe = Regex("""@Entity\s*\(([\s\S]*?)\)\s*\r?\n\s*data class""")
        dataSrc.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            entityRe.findAll(f.readText()).forEach { m ->
                val args = m.groupValues[1]
                val table = Regex("""tableName\s*=\s*"(\w+)"""").find(args)?.groupValues?.get(1)
                    ?: return@forEach
                val indicesArg = Regex("""indices\s*=\s*\[([\s\S]*)""").find(args)?.groupValues?.get(1)
                result += EntityDecl(table, indicesArg)
            }
        }
        return result
    }

    /**
     * 算出 Room 会为各实体建哪些索引。
     *
     * 名字规则跟 Room 生成器保持一致:`index_<表名>_<列名以下划线连接>`,
     * 除非注解里显式写了 name =。
     */
    private fun indicesDeclaredOnEntities(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        entityDeclarations().forEach { decl ->
            val arg = decl.indicesArg ?: return@forEach
            Regex("""Index\s*\(([^)]*)\)""").findAll(arg).forEach { im ->
                val body = im.groupValues[1]
                val explicitName = Regex("""name\s*=\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                val cols = Regex(""""([^"]+)"""").findAll(body).map { it.groupValues[1] }
                    .filter { it != explicitName }
                    .toList()
                if (cols.isEmpty()) return@forEach
                val name = explicitName ?: "index_${decl.table}_${cols.joinToString("_")}"
                out[name] = decl.table
            }
        }
        return out
    }
}
