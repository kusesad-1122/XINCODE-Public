package com.xincode.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted agent trajectory for Workflow replay.
 * One row per session. [eventsJson] stores the full timeline as JSON array.
 */
@Entity(tableName = "trajectories")
data class TrajectoryEntity(
    @PrimaryKey
    val sessionId: Long,
    /** JSON array of trajectory events. */
    val eventsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)