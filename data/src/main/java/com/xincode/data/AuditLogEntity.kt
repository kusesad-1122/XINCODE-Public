package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Immutable audit record persisted to Room.
 * One row per security gate decision (Allow/Deny/NeedConfirm).
 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val toolName: String,
    val toolArgs: String,
    val capability: String,
    val reversibility: String,
    val decision: String,
    val result: String?,
    /** 上一条记录的哈希(首条为空串),用于防篡改哈希链。MIGRATION_51_52 新增。 */
    val prevHash: String = "",
    /** 本条记录的防篡改哈希 = sha256(prevHash|timestamp|toolName|toolArgs|decision|result)。 */
    val hash: String = ""
)