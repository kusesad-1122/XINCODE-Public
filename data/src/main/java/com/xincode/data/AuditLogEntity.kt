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
    val result: String?
)