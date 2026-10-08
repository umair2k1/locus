/*
 * Copyright 2026 Locus Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
