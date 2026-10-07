package com.locus.core.data.audit

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.locus.core.domain.agent.AuditEntry

@Entity(
    tableName = "audit_entries",
    indices = [
        Index("timestamp"),
    ],
)
data class AuditEntryEntity(
    @PrimaryKey
    val id: String,
    val toolName: String,
    val argumentsJson: String,
    val affectedNoteIds: String,
    val timestamp: Long,
    val modelId: String,
    val diff: String,
) {
    fun toDomain(): AuditEntry =
        AuditEntry(
            id = id,
            toolName = toolName,
            argumentsJson = argumentsJson,
            affectedNoteIds =
                if (affectedNoteIds.isBlank()) {
                    emptyList()
                } else {
                    affectedNoteIds.split(",")
                },
            timestamp = timestamp,
            modelId = modelId,
            diff = diff,
        )

    companion object {
        fun fromDomain(entry: AuditEntry): AuditEntryEntity =
            AuditEntryEntity(
                id = entry.id,
                toolName = entry.toolName,
                argumentsJson = entry.argumentsJson,
                affectedNoteIds = entry.affectedNoteIds.joinToString(","),
                timestamp = entry.timestamp,
                modelId = entry.modelId,
                diff = entry.diff,
            )
    }
}
