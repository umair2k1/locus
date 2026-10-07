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
