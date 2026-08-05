package com.xincode.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.*
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SettingEntity::class, MessageEntity::class, ProviderConfigEntity::class, SessionEntity::class, StateCursorEntity::class, AuditLogEntity::class, MemoryEntity::class, TrajectoryEntity::class, SkillEntity::class, McpServerEntity::class, GlobalSettingsEntity::class, ProjectEntity::class, IdentityEntity::class, PermissionRuleEntity::class, HookEntity::class, CronJobEntity::class, SubAgentEntity::class, UsageRecordEntity::class, KanbanTaskEntity::class, GroupRoomEntity::class, GroupMemberEntity::class, GroupMessageEntity::class, GroupRoomSummaryEntity::class, KanbanRunEntity::class, CodeSymbolEntity::class, CodeEdgeEntity::class, CodeFileEntity::class], version = 43, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    /** Exposes Room's suspending transaction boundary without leaking room-ktx to app. */
    suspend fun <T> inTransaction(block: suspend () -> T): T = withTransaction(block)

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
    abstract fun kanbanRunDao(): KanbanRunDao
    abstract fun codeIndexDao(): CodeIndexDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        const val DB_NAME = "xincode.db"

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

        // 看板升级为「智能体工作队列」:任务加派活字段 + 新增执行记录表。
        private val MIGRATION_34_35 = object : Migration(34, 35) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE kanban_tasks ADD COLUMN assignee TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE kanban_tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE kanban_tasks ADD COLUMN startedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE kanban_tasks ADD COLUMN completedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE kanban_tasks ADD COLUMN result TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE kanban_tasks ADD COLUMN runCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS kanban_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        taskId INTEGER NOT NULL,
                        assignee TEXT NOT NULL DEFAULT '',
                        startedAt INTEGER NOT NULL DEFAULT 0,
                        endedAt INTEGER NOT NULL DEFAULT 0,
                        outcome TEXT NOT NULL DEFAULT '',
                        summary TEXT NOT NULL DEFAULT '',
                        error TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_kanban_runs_taskId ON kanban_runs(taskId)")
            }
        }

        /** 上一次打开数据库时发生的致命错误。非空表示旧库已被改名备份,UI 应当提示用户。 */
        @Volatile
        var lastRecoveredFailure: String? = null
            private set

        /** 被改名备份的旧库文件名(仅在 lastRecoveredFailure 非空时有意义)。 */
        @Volatile
        var lastBackupName: String? = null
            private set

        /**
         * 把 sessions 的 identityId 索引补齐,让所有历史路径收敛到同一个状态。
         *
         * 这个索引是 MIGRATION_18_19 建的,于是老库分成了两拨:
         * - 跨过 18→19 升上来的:有 `idx_sessions_identityId`
         * - 在 v19 之后才全新安装的:Room 按实体建表,而实体当时没声明索引 → 没有它
         *
         * 两拨库结构不一样,而实体只能声明一种,不管声明哪种都会有一拨人升级时校验失败
         * (Room 会拿实体声明和数据库实况比索引集合,对不上就抛异常,fallback 不管这一步)。
         * 所以这里用 IF NOT EXISTS 把缺的那拨补上,两拨就一致了,实体照着声明即可。
         */
        private val MIGRATION_35_36 = object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_identityId ON sessions(identityId)")
            }
        }

        /**
         * 群聊房间加三个开关:成员互相 @、连锁跳数上限、完全访问。
         *
         * allowMemberMentions 默认 1:之前成员回复里的 @ 是死的,谁都不接话,
         * 群聊必然断在第一轮 —— 那是个缺陷,不该把缺陷当默认值保留。
         */
        private val MIGRATION_36_37 = object : Migration(36, 37) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_rooms ADD COLUMN allowMemberMentions INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE group_rooms ADD COLUMN maxHops INTEGER NOT NULL DEFAULT 3")
                db.execSQL("ALTER TABLE group_rooms ADD COLUMN fullAccess INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 群聊成员有了自己的工作会话,房间有了自己的工作区。
         *
         * workSessionId 让「过程」和「结论」分开:群里只出现汇报,干活的全过程
         * 落在成员自己那条会话里,点进去就能看。
         */
        private val MIGRATION_37_38 = object : Migration(37, 38) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_rooms ADD COLUMN workspacePath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE group_members ADD COLUMN workSessionId INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 身份卡分场景。
         *
         * 老卡一律 both(保持现状,不动用户已有的东西);预制团队那六张在装的时候
         * 会被写成 group,于是主对话的身份列表里不再出现「测试工程师」这种
         * 只在群聊里成立的角色。
         */
        private val MIGRATION_38_39 = object : Migration(38, 39) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE identities ADD COLUMN scope TEXT NOT NULL DEFAULT 'both'")
            }
        }

        /**
         * 代码索引三张表:符号、关系、文件指纹。
         *
         * 索引必须在实体上声明【也】在这里建 —— Room 升级后会拿实体声明和数据库实况
         * 逐项比对索引集合,两边对不上直接抛异常崩在启动(这个坑本项目踩过两次)。
         */
        private val MIGRATION_39_40 = object : Migration(39, 40) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS code_symbols (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        root TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        name TEXT NOT NULL,
                        qualifiedName TEXT NOT NULL DEFAULT '',
                        kind TEXT NOT NULL DEFAULT '',
                        startLine INTEGER NOT NULL DEFAULT 0,
                        endLine INTEGER NOT NULL DEFAULT 0,
                        signature TEXT NOT NULL DEFAULT '',
                        indexedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_symbols_name ON code_symbols(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_symbols_filePath ON code_symbols(filePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_symbols_root ON code_symbols(root)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS code_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        root TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        kind TEXT NOT NULL DEFAULT '',
                        fromName TEXT NOT NULL DEFAULT '',
                        toName TEXT NOT NULL DEFAULT '',
                        line INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_edges_fromName ON code_edges(fromName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_edges_toName ON code_edges(toName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_edges_root ON code_edges(root)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS code_files (
                        filePath TEXT PRIMARY KEY NOT NULL,
                        root TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        symbolCount INTEGER NOT NULL DEFAULT 0,
                        indexedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_files_root ON code_files(root)")
            }
        }

        /** Stop the default assistant from implying that ordinary work requires a rooted device. */
        private val MIGRATION_40_41 = object : Migration(40, 41) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    UPDATE identities
                    SET systemPrompt = '你是 XINCODE，一个运行在 Android 上的 AI Agent。普通聊天、文件、代码搜索和 Shell 工作默认使用应用自身权限；只有明确需要系统级权限时才使用 su_exec。回答简洁、技术化，并区分已验证事实与推断。'
                    WHERE id = 1 AND systemPrompt LIKE '%Android root%'
                    """.trimIndent()
                )
            }
        }

        /** 群聊消息增加引用字段:回复要能指出「我在回谁」。 */
        private val MIGRATION_41_42 = object : Migration(41, 42) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_messages ADD COLUMN replyToId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN replyToSender TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN replyToContent TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 群聊 P0-P2 升级:
         *  - 消息支持流式写入、run 分组保序、类型(kind)、中断标记、思考与用量
         *  - 房间增加滚动总结开关/频率/模型配置
         *  - 新增 group_room_summaries 保存总结游标与状态
         */
        private val MIGRATION_42_43 = object : Migration(42, 43) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE group_rooms ADD COLUMN summaryEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE group_rooms ADD COLUMN summaryEveryTurns INTEGER NOT NULL DEFAULT 20")
                db.execSQL("ALTER TABLE group_rooms ADD COLUMN summaryModel TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE group_messages ADD COLUMN runId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN phase INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'message'")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN streaming INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN interrupted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN model TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN promptTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN completionTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN cacheHitTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN cacheMissTokens INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS group_room_summaries (
                        roomId INTEGER PRIMARY KEY NOT NULL,
                        summary TEXT NOT NULL DEFAULT '',
                        summaryThroughMessageId INTEGER NOT NULL DEFAULT 0,
                        summaryThroughMessageTimestamp INTEGER NOT NULL DEFAULT 0,
                        summarizedTurnCount INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'idle',
                        version INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        lastError TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: openOrRecover(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun builder(context: Context) = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DB_NAME
        )
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    createFts5Tables(db)
                    // Fresh databases do not replay historical migrations, so seed the default
                    // card here as well as in MIGRATION_18_19.
                    val now = System.currentTimeMillis()
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO identities (
                            id, name, systemPrompt, temperature, isStarred, createdAt,
                            description, openingStatement, marks, allowedTools,
                            providerConfigId, modelOverride, scope
                        ) VALUES (
                            1,
                            '默认助手',
                            '你是 XINCODE，一个运行在 Android 上的 AI Agent。普通聊天、文件、代码搜索和 Shell 工作默认使用应用自身权限；只有明确需要系统级权限时才使用 su_exec。回答简洁、技术化，并区分已验证事实与推断。',
                            1.0, 0, $now, '', '', '', '', 0, '', 'both'
                        )
                        """.trimIndent()
                    )
                }
            })
            .fallbackToDestructiveMigration()

        /**
         * 打开数据库;打不开就把旧库改名备份后重建一个空的。
         *
         * 为什么必须有这一层:
         * - `fallbackToDestructiveMigration()` 只管【找不到迁移路径】这一种情况。迁移【跑完之后】
         *   Room 还会拿实体声明和数据库实况逐项比对(列、类型、主键、索引……),这一步不匹配会直接
         *   抛 IllegalStateException,fallback 完全不介入 —— 它认为这是开发者写错了迁移,必须让人看见。
         * - 结果就是:开发者一个疏忽 = 所有老用户【永远打不开 App】,而且没有任何自救手段,
         *   只能卸载重装(数据同样全没,还得等下一个版本)。这个代价太大了。
         *
         * 折中做法:改名备份而不是删除。用户至少进得去,数据也还躺在私有目录里,
         * 修好之后有机会捞回来。同时把失败原因记下来,让界面能明确告诉用户发生了什么 ——
         * 数据「凭空消失」而不给说法,比崩溃更糟。
         */
        private fun openOrRecover(context: Context): AppDatabase {
            val db = builder(context).build()
            try {
                // Room 是懒打开的:不主动碰一下,迁移要等到第一次查询才执行,
                // 那时异常已经抛在某个协程深处,这里根本接不住。必须在这里逼它现在就打开。
                db.openHelper.writableDatabase
                return db
            } catch (t: Throwable) {
                runCatching { db.close() }
                val reason = "${t::class.java.simpleName}: ${t.message?.take(400) ?: ""}"

                // 先试着【修好权限再开一次】,不要一上来就把库废掉。
                //
                // 真实事故:有用户让 AI 自行安装技能,AI 动到了 App 自己的 databases/,
                // 结果是 "Permission denied ... xincode.db is not readable"。注意这时
                // 数据一个字节都没坏 —— 坏的只是文件的权限位。直接走下面的改名重建,
                // 等于为一个改一下权限就能修好的问题,把用户的会话、身份卡、供应商配置、
                // 记忆全部清空。这个代价和病因完全不成比例。
                if (tryRepairPermissions(context)) {
                    val retried = runCatching {
                        builder(context).build().also { it.openHelper.writableDatabase }
                    }.getOrNull()
                    if (retried != null) {
                        android.util.Log.w("XincodeDb", "数据库权限已修复,原样打开(未重建): $reason")
                        return retried
                    }
                }

                android.util.Log.e("XincodeDb", "打开数据库失败,改名备份后重建: $reason", t)
                lastBackupName = backupBrokenDatabase(context)
                lastRecoveredFailure = reason
                // 备份之后原路径已经没有文件了,这次 build 会走全新建库。
                return builder(context).build().also { fresh ->
                    // 同样主动打开一次:如果连空库都建不起来,那就是真的没救了,
                    // 此时让它照常抛出去 —— 掩盖一个连空库都开不了的环境毫无意义。
                    fresh.openHelper.writableDatabase
                }
            }
        }

        /**
         * 把 `databases/` 目录和三个库文件的权限位改回自己可读写。
         *
         * @return 是否真的改动了什么(没改动就别白retry一次)
         *
         * 【能修什么、修不了什么】只改 mode 位,所以修得了「读写位被抹掉」,
         * 修不了「文件被 chown 给了 root」—— 后者 App 自己的 uid 无权改,
         * 只能回落到改名重建。这里不去调 root 来抢回来:启动路径上拉起 root shell
         * 会弹授权框、会卡住冷启动,而且真到了那一步,弹一个说明清楚的对话框
         * 让用户知道发生了什么,比偷偷用 root 改回去更该做。
         *
         * 目录本身也要修:`databases/` 少了执行位,里面的文件一个都打不开,
         * 而报错长得和文件本身没权限一模一样。
         */
        private fun tryRepairPermissions(context: Context): Boolean {
            val main = context.getDatabasePath(DB_NAME)
            val dir = main.parentFile ?: return false
            var changed = false
            runCatching {
                // 目录要可读可写【可执行】—— 目录的执行位就是「能进去查里面的文件」。
                if (!dir.canRead() && dir.setReadable(true, true)) changed = true
                if (!dir.canWrite() && dir.setWritable(true, true)) changed = true
                if (!dir.canExecute() && dir.setExecutable(true, true)) changed = true
            }
            for (suffix in listOf("", "-wal", "-shm")) {
                val f = java.io.File(dir, "$DB_NAME$suffix")
                if (!f.exists()) continue
                runCatching {
                    if (!f.canRead() && f.setReadable(true, true)) changed = true
                    if (!f.canWrite() && f.setWritable(true, true)) changed = true
                }
            }
            if (changed) android.util.Log.w("XincodeDb", "尝试修复数据库文件权限")
            return changed
        }

        /**
         * 把损坏的库连同 WAL/SHM 一起改名。
         *
         * 必须三个文件一起动:只改主库而留下 -wal,SQLite 下次打开时会把那份 WAL 当成
         * 新库的日志重放,等于把坏数据又搬了回来。
         */
        private fun backupBrokenDatabase(context: Context): String? {
            val main = context.getDatabasePath(DB_NAME)
            if (!main.exists()) return null
            val stamp = System.currentTimeMillis()
            val backupName = "$DB_NAME.broken-$stamp"
            var ok = false
            for (suffix in listOf("", "-wal", "-shm")) {
                val src = java.io.File(main.parentFile, "$DB_NAME$suffix")
                if (!src.exists()) continue
                val dst = java.io.File(main.parentFile, "$backupName$suffix")
                // 改名失败(极少见,比如文件被别的进程占住)就只能删,
                // 留着损坏文件的话下次启动会重复走这条路径,永远开不起来。
                if (!src.renameTo(dst)) src.delete() else ok = true
            }
            return if (ok) backupName else null
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
