package com.xincode.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SettingEntity::class, MessageEntity::class, ProviderConfigEntity::class, SessionEntity::class, StateCursorEntity::class, AuditLogEntity::class, MemoryEntity::class, TrajectoryEntity::class, SkillEntity::class, McpServerEntity::class, GlobalSettingsEntity::class, ProjectEntity::class, IdentityEntity::class, PermissionRuleEntity::class, HookEntity::class, CronJobEntity::class, SubAgentEntity::class, UsageRecordEntity::class, KanbanTaskEntity::class, GroupRoomEntity::class, GroupMemberEntity::class, GroupMessageEntity::class], version = 34, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun settingDao(): SettingDao
    abstract fun messageDao(): MessageDao
    abstract fun providerConfigDao(): ProviderConfigDao
    abstract fun sessionDao(): SessionDao
    abstract fun stateCursorDao(): StateCursorDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun memoryDao(): MemoryDao
    abstract fun trajectoryDao(): TrajectoryDao
    abstract fun skillDao(): SkillDao
    abstract fun permissionRuleDao(): PermissionRuleDao   // gap-12
    abstract fun hookDao(): HookDao                       // gap-24
    abstract fun mcpServerDao(): McpServerDao
    abstract fun globalSettingsDao(): GlobalSettingsDao
    abstract fun projectDao(): ProjectDao
    abstract fun identityDao(): IdentityDao
    abstract fun cronJobDao(): CronJobDao   // Hermes-⑦
    abstract fun subAgentDao(): SubAgentDao
    abstract fun usageRecordDao(): UsageRecordDao
    abstract fun kanbanTaskDao(): KanbanTaskDao
    abstract fun groupRoomDao(): GroupRoomDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add enabledModelIds column; populate with JSON array wrapping old model
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN enabledModelIds TEXT NOT NULL DEFAULT '[]'")
                // Migrate existing: enabledModelIds = [old_model]
                db.execSQL("UPDATE provider_configs SET enabledModelIds = '[' || '\"' || model || '\"' || ']' WHERE model IS NOT NULL AND model != '' AND enabledModelIds = '[]'")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create memories table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        tags TEXT NOT NULL DEFAULT '',
                        sourceMessageId INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // 1b. Unique index on title for dedup
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memories_title ON memories(title)")

                // 2. Create FTS4 virtual table (FTS5 not available on some OEM ROMs)
                db.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts4(
                        title, content, tags,
                        content='memories'
                    )
                """.trimIndent())

                // 3. Triggers to keep FTS5 in sync with memories table
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories
                    BEGIN
                        INSERT INTO memories_fts(rowid, title, content, tags)
                        VALUES (new.id, new.title, new.content, new.tags);
                    END
                """.trimIndent())

                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories
                    BEGIN
                        INSERT INTO memories_fts(memories_fts, rowid, title, content, tags)
                        VALUES ('delete', old.id, old.title, old.content, old.tags);
                    END
                """.trimIndent())

                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories
                    BEGIN
                        INSERT INTO memories_fts(memories_fts, rowid, title, content, tags)
                        VALUES ('delete', old.id, old.title, old.content, old.tags);
                        INSERT INTO memories_fts(rowid, title, content, tags)
                        VALUES (new.id, new.title, new.content, new.tags);
                    END
                """.trimIndent())
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memories ADD COLUMN embedding BLOB DEFAULT NULL")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS trajectories (
                        sessionId INTEGER PRIMARY KEY NOT NULL,
                        eventsJson TEXT NOT NULL DEFAULT '[]',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS skills (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS mcp_servers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        url TEXT NOT NULL,
                        authHeader TEXT NOT NULL DEFAULT '',
                        connected INTEGER NOT NULL DEFAULT 0,
                        toolNames TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add columns to sessions table
                db.execSQL("ALTER TABLE sessions ADD COLUMN systemPromptOverride TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN currentModelId TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN currentEffortLevel TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN thinkingEnabled INTEGER DEFAULT NULL")
                // Add sessionId column to messages table
                db.execSQL("ALTER TABLE messages ADD COLUMN sessionId INTEGER NOT NULL DEFAULT 1")
                // Create global_settings table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS global_settings (
                        id INTEGER PRIMARY KEY NOT NULL DEFAULT 1,
                        globalSystemPrompt TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN apiPathType TEXT NOT NULL DEFAULT 'openai'")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN promptTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN cacheHitTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN cacheMissTokens INTEGER")
                db.execSQL("ALTER TABLE messages ADD COLUMN completionTokens INTEGER")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN prefixHash TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN turnId INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS projects (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        isExpanded INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE sessions ADD COLUMN projectId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE sessions ADD COLUMN isStarred INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS identities (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        systemPrompt TEXT NOT NULL,
                        temperature REAL NOT NULL DEFAULT 1.0,
                        isStarred INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("ALTER TABLE sessions ADD COLUMN identityId INTEGER DEFAULT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_identityId ON sessions(identityId)")

                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT INTO identities (name, systemPrompt, temperature, createdAt)
                    VALUES (
                        '默认助手',
                        '你是 XINCODE,一个跑在 Android root 设备上的 AI Agent,可调用工具完成任务。回答简洁、技术化、不啰嗦。',
                        1.0,
                        $now
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO identities (name, systemPrompt, temperature, createdAt)
                    VALUES (
                        '代码助手',
                        '你是一个资深 Android / Kotlin 开发者,擅长 Jetpack Compose、Room、协程。回答优先给可运行的代码示例,后给解释。指出反模式和性能问题。',
                        0.7,
                        $now
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO identities (name, systemPrompt, temperature, createdAt)
                    VALUES (
                        'Linux 老司机',
                        '你是一个 Linux / Android 底层老手,精通 root、shell、Magisk、KSU、Zygisk、SELinux、binder。回答先给命令和路径,后给原理。',
                        0.7,
                        $now
                    )
                """.trimIndent())
            }
        }

        // gap-08/09/10:provider 增 extra_headers/context_window/自动压缩阈值;identity 增 max_tokens/top_p。
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN extraHeadersJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN contextWindow INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN autoCompactThresholdPercent INTEGER NOT NULL DEFAULT 85")
                db.execSQL("ALTER TABLE identities ADD COLUMN maxTokens INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE identities ADD COLUMN topP REAL DEFAULT NULL")
            }
        }

        // gap-12:持久化 allow/deny 权限规则表。
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS permission_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        action TEXT NOT NULL,
                        toolFilter TEXT NOT NULL,
                        pattern TEXT NOT NULL DEFAULT '',
                        note TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // gap-24:hooks 生命周期钩子表。
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS hooks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        event TEXT NOT NULL,
                        matcher TEXT NOT NULL DEFAULT '',
                        command TEXT NOT NULL,
                        runAsRoot INTEGER NOT NULL DEFAULT 0,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        note TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        // gap-22:MCP 服务器增 stdio 传输字段(transport/command/args/env/runAsRoot)。
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN transport TEXT NOT NULL DEFAULT 'http'")
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN command TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN argsJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN envJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN runAsRoot INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // gap-19 结构化输出:全局存一份 json_schema 形态的 response_format(空=不启用)。
                db.execSQL("ALTER TABLE global_settings ADD COLUMN structuredOutputSchema TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Hermes-① 技能出处/保护档(user/bundled/agent);后台复盘只能改 user/agent。
                db.execSQL("ALTER TABLE skills ADD COLUMN source TEXT NOT NULL DEFAULT 'user'")
                // Hermes-⑦ cron 定时任务表。
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS cron_jobs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL DEFAULT '',
                        prompt TEXT NOT NULL DEFAULT '',
                        scheduleKind TEXT NOT NULL DEFAULT 'interval',
                        scheduleSpec TEXT NOT NULL DEFAULT '',
                        intervalMinutes INTEGER NOT NULL DEFAULT 0,
                        nextRunAt INTEGER NOT NULL DEFAULT 0,
                        lastRunAt INTEGER NOT NULL DEFAULT 0,
                        lastStatus TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        deliver TEXT NOT NULL DEFAULT 'local',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 工作区:项目级根目录。
                db.execSQL("ALTER TABLE projects ADD COLUMN workspaceRoot TEXT NOT NULL DEFAULT ''")
                // 记忆按项目隔离:memories 增 projectId,并把唯一约束从 title 改为 (title, projectId)。
                db.execSQL("ALTER TABLE memories ADD COLUMN projectId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DROP INDEX IF EXISTS index_memories_title")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_memories_title_projectId ON memories(title, projectId)")
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sub_agents (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        systemPrompt TEXT NOT NULL DEFAULT '',
                        skillNames TEXT NOT NULL DEFAULT '',
                        toolNames TEXT NOT NULL DEFAULT '',
                        builtin INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // 上下文压缩:全局设置增 上下文窗口覆盖 / 压缩阈值覆盖 / 自定义总结规则。
        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE global_settings ADD COLUMN contextWindowOverride INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE global_settings ADD COLUMN autoCompactThresholdOverride INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE global_settings ADD COLUMN customSummaryRule TEXT NOT NULL DEFAULT ''")
            }
        }

        // Goal/Work 模式:sessions 增 isGoal / goalStatus。
        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN isGoal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN goalStatus TEXT NOT NULL DEFAULT ''")
            }
        }

        // 供应商能力声明:provider_configs 增识图/音频/视频/ToolCall 四个开关。
        // ToolCall 默认 1 —— 老用户都靠原生工具调用在用,默认 0 会让所有人的 agent 突然罢工。
        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN supportsVision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN supportsAudio INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN supportsVideo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE provider_configs ADD COLUMN supportsToolCall INTEGER NOT NULL DEFAULT 1")
            }
        }

        // 身份设定扩展:描述/开场白/备注/工具白名单/绑定模型。
        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE identities ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE identities ADD COLUMN openingStatement TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE identities ADD COLUMN marks TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE identities ADD COLUMN allowedTools TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE identities ADD COLUMN providerConfigId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE identities ADD COLUMN modelOverride TEXT NOT NULL DEFAULT ''")
            }
        }

        // 用量分析:按【每次模型调用】追加记录(messages 里的 usage 只存最后一次,会严重少算)。
        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS usage_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ts INTEGER NOT NULL DEFAULT 0,
                        sessionId INTEGER NOT NULL DEFAULT 0,
                        model TEXT NOT NULL DEFAULT '',
                        provider TEXT NOT NULL DEFAULT '',
                        source TEXT NOT NULL DEFAULT 'chat',
                        inputTokens INTEGER NOT NULL DEFAULT 0,
                        outputTokens INTEGER NOT NULL DEFAULT 0,
                        cacheReadTokens INTEGER NOT NULL DEFAULT 0,
                        cacheWriteTokens INTEGER NOT NULL DEFAULT 0,
                        reasoningTokens INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // 趋势与聚合全都按时间范围查,没这个索引数据一多就会全表扫。
                db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_records_ts ON usage_records(ts)")
            }
        }

        // 看板:跨会话的长期待办(与 agent_plan 的回合内临时清单分开)。
        private val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS kanban_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        note TEXT NOT NULL DEFAULT '',
                        status TEXT NOT NULL DEFAULT 'todo',
                        position INTEGER NOT NULL DEFAULT 0,
                        sessionId INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        // 群聊房间:房间 / 成员 / 消息三张表。
        private val MIGRATION_33_34 = object : Migration(33, 34) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_rooms (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        note TEXT NOT NULL DEFAULT '',
                        compactThreshold INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_members (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        roomId INTEGER NOT NULL,
                        displayName TEXT NOT NULL,
                        identityId INTEGER NOT NULL DEFAULT 0,
                        providerConfigId INTEGER NOT NULL DEFAULT 0,
                        model TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS group_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        roomId INTEGER NOT NULL,
                        sender TEXT NOT NULL DEFAULT '',
                        content TEXT NOT NULL,
                        isDigest INTEGER NOT NULL DEFAULT 0,
                        ts INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                // 消息与成员永远按 roomId 查,不建索引房间一多就全表扫
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_messages_roomId ON group_messages(roomId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_members_roomId ON group_members(roomId)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "xincode.db"
                )
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            createFts5Tables(db)
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }

        /** Create FTS4 virtual table + sync triggers. Idempotent (IF NOT EXISTS). */
        private fun createFts5Tables(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts4(
                    title, content, tags,
                    content='memories'
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories
                BEGIN
                    INSERT INTO memories_fts(rowid, title, content, tags)
                    VALUES (new.id, new.title, new.content, new.tags);
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories
                BEGIN
                    INSERT INTO memories_fts(memories_fts, rowid, title, content, tags)
                    VALUES ('delete', old.id, old.title, old.content, old.tags);
                END
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories
                BEGIN
                    INSERT INTO memories_fts(memories_fts, rowid, title, content, tags)
                    VALUES ('delete', old.id, old.title, old.content, old.tags);
                    INSERT INTO memories_fts(rowid, title, content, tags)
                    VALUES (new.id, new.title, new.content, new.tags);
                END
            """.trimIndent())
        }
    }
}