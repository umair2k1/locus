package com.locus.core.domain.agent

import kotlinx.coroutines.flow.Flow

data class AuditEntry(
    val id: String,
    val toolName: String,
    val argumentsJson: String,
    val affectedNoteIds: List<String>,
    val timestamp: Long,
    val modelId: String,
    val diff: String,
)

interface AuditJournal {
    suspend fun record(entry: AuditEntry)

    fun observeEntries(): Flow<List<AuditEntry>>

    suspend fun revert(entryId: String)
}
