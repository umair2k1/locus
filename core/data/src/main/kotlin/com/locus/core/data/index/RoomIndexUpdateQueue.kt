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

package com.locus.core.data.index

import com.locus.core.data.db.NoteDao
import com.locus.core.data.db.NoteIndexEntity
import com.locus.core.domain.notes.FlushReceipt
import com.locus.core.domain.notes.IndexUpdateQueue
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.search.IndexingCoordinator
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Room-backed implementation of [IndexUpdateQueue].
 *
 * For Prompt 7 (Phase 0), this queue upserts a minimal [NoteIndexEntity] row keyed by
 * [FlushReceipt.noteId], updating only [NoteIndexEntity.checksum] and [NoteIndexEntity.modified].
 *
 * Dependency note: Full chunk generation, FTS indexing, and embedding vector enqueue arrive in
 * Prompt 28. Extend [enqueue] at that point to enqueue chunking and embedding background jobs after
 * the file flush receipt is received.
 */
@Singleton
class RoomIndexUpdateQueue
    @Inject
    constructor(
        private val noteDao: NoteDao,
        private val indexingCoordinatorProvider: Provider<IndexingCoordinator>,
        private val noteRepositoryProvider: Provider<NoteRepository>,
    ) : IndexUpdateQueue {
        constructor(
            noteDao: NoteDao,
            indexingCoordinator: IndexingCoordinator? = null,
            noteRepository: NoteRepository? = null,
        ) : this(
            noteDao = noteDao,
            indexingCoordinatorProvider =
                Provider { indexingCoordinator ?: error("IndexingCoordinator not provided") },
            noteRepositoryProvider =
                Provider { noteRepository ?: error("NoteRepository not provided") },
        )

        override suspend fun enqueue(receipt: FlushReceipt) {
            val modifiedInstant = Instant.ofEpochMilli(receipt.flushedAt)
            val existing = noteDao.getById(receipt.noteId)
            val entity =
                if (existing != null) {
                    existing.copy(
                        checksum = receipt.checksum,
                        modified = modifiedInstant,
                    )
                } else {
                    NoteIndexEntity(
                        id = receipt.noteId,
                        title = "",
                        type = NoteType.NOTE,
                        folderPath = "",
                        pinned = false,
                        color = null,
                        tags = emptyList(),
                        created = modifiedInstant,
                        modified = modifiedInstant,
                        checksum = receipt.checksum,
                        bodyPreview = "",
                    )
                }
            noteDao.upsert(entity)

            val coordinator = runCatching { indexingCoordinatorProvider.get() }.getOrNull()
            if (coordinator != null) {
                val saved = noteDao.getById(receipt.noteId) ?: entity
                val note =
                    Note(
                        id = saved.id,
                        title = saved.title,
                        type = saved.type,
                        folderPath = saved.folderPath,
                        pinned = saved.pinned,
                        color = saved.color,
                        tags = saved.tags,
                        created = saved.created,
                        modified = saved.modified,
                        checksum = saved.checksum,
                    )
                val body =
                    runCatching { noteRepositoryProvider.get().readBody(receipt.noteId) }
                        .getOrDefault(saved.bodyPreview)
                runCatching { coordinator.reindexIfNeeded(note, body) }
            }
        }
    }
