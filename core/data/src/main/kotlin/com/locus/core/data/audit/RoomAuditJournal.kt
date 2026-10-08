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

import com.locus.core.data.history.NoteHistoryStore
import com.locus.core.domain.agent.AuditEntry
import com.locus.core.domain.agent.AuditJournal
import com.locus.core.domain.notes.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomAuditJournal
    @Inject
    constructor(
        private val auditDao: AuditDao,
        private val historyStore: NoteHistoryStore,
        private val noteRepository: NoteRepository,
    ) : AuditJournal {
        override suspend fun record(entry: AuditEntry) {
            auditDao.insert(AuditEntryEntity.fromDomain(entry))
        }

        override fun observeEntries(): Flow<List<AuditEntry>> =
            auditDao.observeAll().map { entities ->
                entities.map { it.toDomain() }
            }

        override suspend fun revert(entryId: String) {
            val entity = auditDao.getById(entryId) ?: return
            val domain = entity.toDomain()
            for (noteId in domain.affectedNoteIds) {
                val revisions = historyStore.listRevisions(noteId)
                val targetRevision =
                    revisions.firstOrNull { it.timestamp <= domain.timestamp }
                        ?: revisions.firstOrNull()
                if (targetRevision != null) {
                    noteRepository.edit(noteId, targetRevision.body)
                }
            }
        }
    }
