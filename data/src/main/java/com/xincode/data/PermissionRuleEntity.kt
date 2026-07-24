package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * gap-12 持久化权限规则(allow/deny)。对标 grok 的权限规则 DSL:
 * - action: "allow" 命中即跳过确认;"deny" 命中恒拒(优先级 deny > allow > 模式默认)。
 * - toolFilter: 工具名过滤,支持 "*"(任意)、精确名、前缀通配(如 "file_*")。
 * - pattern: 目标过滤,shell 匹配命令、文件工具匹配路径,支持 glob(* ?);空=匹配任意。
 */
@Entity(tableName = "permission_rules")
data class PermissionRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val action: String,          // "allow" | "deny"
    val toolFilter: String,      // "*" | "shell_exec" | "file_*"
    val pattern: String = "",    // glob;空=任意
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
