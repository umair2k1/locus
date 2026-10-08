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

import android.content.Context
import androidx.room.Room
import com.locus.core.data.db.LocusDatabase
import com.locus.core.data.db.NoteDao
import com.locus.core.data.db.NoteIndexEntity
import com.locus.core.domain.notes.FlushReceipt
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.search.ChunkMetadata
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.EmbeddedChunk
import com.locus.core.domain.search.EmbeddingGateway
import com.locus.core.domain.search.IndexingCoordinator
import com.locus.core.domain.search.RankedChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RoomIndexUpdateQueueTest {
    private lateinit var db: LocusDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var queue: RoomIndexUpdateQueue

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db =
            Room
                .inMemoryDatabaseBuilder(context, LocusDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        noteDao = db.noteDao()
        queue = RoomIndexUpdateQueue(noteDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun enqueue_whenNoteDoesNotExist_createsMinimalEntity() =
        runTest {
            val receipt =
                FlushReceipt(
                    noteId = "note-new",
                    checksum = "abc123hash",
                    flushedAt = 1726488000000L,
                )

            queue.enqueue(receipt)

            val entity = noteDao.getById("note-new")
            assertNotNull(entity)
            assertEquals("note-new", entity!!.id)
            assertEquals("abc123hash", entity.checksum)
            assertEquals(Instant.ofEpochMilli(1726488000000L), entity.modified)
            assertEquals(Instant.ofEpochMilli(1726488000000L), entity.created)
            assertEquals("", entity.title)
            assertEquals("", entity.bodyPreview)
        }

    @Test
    fun enqueue_whenNoteExists_updatesOnlyChecksumAndModified() =
        runTest {
            val existing =
                NoteIndexEntity(
                    id = "note-exist",
                    title = "Existing Title",
                    type = NoteType.CHECKLIST,
                    folderPath = "projects/locus",
                    pinned = true,
                    color = "#FF0000",
                    tags = listOf("todo", "urgent"),
                    created = Instant.ofEpochMilli(1700000000000L),
                    modified = Instant.ofEpochMilli(1700000000000L),
                    checksum = "oldhash",
                    bodyPreview = "- [ ] buy milk",
                )
            noteDao.upsert(existing)

            val receipt =
                FlushReceipt(
                    noteId = "note-exist",
                    checksum = "newhash456",
                    flushedAt = 1726500000000L,
                )

            queue.enqueue(receipt)

            val updated = noteDao.getById("note-exist")
            assertNotNull(updated)
            assertEquals("note-exist", updated!!.id)
            assertEquals("Existing Title", updated.title)
            assertEquals(NoteType.CHECKLIST, updated.type)
            assertEquals("projects/locus", updated.folderPath)
            assertEquals(true, updated.pinned)
            assertEquals("#FF0000", updated.color)
            assertEquals(listOf("todo", "urgent"), updated.tags)
            assertEquals(Instant.ofEpochMilli(1700000000000L), updated.created)
            assertEquals(Instant.ofEpochMilli(1726500000000L), updated.modified)
            assertEquals("newhash456", updated.checksum)
            assertEquals("- [ ] buy milk", updated.bodyPreview)
        }

    @Test
    fun enqueue_whenCoordinatorProvided_callsReindexIfNeeded() =
        runTest {
            val fakeEmbeddingGateway =
                object : EmbeddingGateway {
                    var callCount = 0

                    override suspend fun embed(text: String): FloatArray {
                        callCount++
                        return FloatArray(4) { 0.1f }
                    }
                }
            val fakeChunkRepository =
                object : ChunkRepository {
                    val stored = mutableListOf<EmbeddedChunk>()

                    override suspend fun getMetadata(noteId: String): ChunkMetadata? = null

                    override suspend fun replaceChunksForNote(
                        noteId: String,
                        chunks: List<EmbeddedChunk>,
                    ) {
                        stored.addAll(chunks)
                    }

                    override suspend fun search(
                        queryVector: FloatArray,
                        topK: Int,
                        noteIds: Set<String>?,
                    ): List<RankedChunk> = emptyList()

                    override suspend fun deleteAll() {
                        stored.clear()
                    }
                }
            val fakeNoteRepository =
                object : NoteRepository {
                    override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

                    override fun observeAllNotes(): Flow<List<Note>> = emptyFlow()

                    override suspend fun readBody(noteId: String): String = "Note body content"

                    override suspend fun listFolders(): List<String> = emptyList()

                    override suspend fun createFolder(
                        parentPath: String,
                        name: String,
                    ) = Unit

                    override suspend fun createNote(
                        folderPath: String,
                        title: String,
                        type: NoteType,
                    ): Note = error("Unused")

                    override suspend fun edit(
                        noteId: String,
                        newBody: String,
                    ) = Unit

                    override suspend fun setPinned(
                        noteId: String,
                        pinned: Boolean,
                    ) = Unit

                    override suspend fun setColor(
                        noteId: String,
                        color: String?,
                    ) = Unit

                    override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
                }

            val coordinator =
                IndexingCoordinator(
                    embeddingGateway = fakeEmbeddingGateway,
                    chunkRepository = fakeChunkRepository,
                    noteRepository = fakeNoteRepository,
                )

            val queueWithCoordinator =
                RoomIndexUpdateQueue(
                    noteDao = noteDao,
                    indexingCoordinator = coordinator,
                    noteRepository = fakeNoteRepository,
                )

            val receipt =
                FlushReceipt(
                    noteId = "note-reindex",
                    checksum = "hash-reindex-123",
                    flushedAt = 1726500000000L,
                )

            queueWithCoordinator.enqueue(receipt)

            // Check that noteDao was updated
            val savedEntity = noteDao.getById("note-reindex")
            assertNotNull(savedEntity)
            assertEquals("hash-reindex-123", savedEntity!!.checksum)

            // Check that coordinator reindexed the note (chunks stored and embedding called)
            assertEquals(1, fakeEmbeddingGateway.callCount)
            assertEquals(1, fakeChunkRepository.stored.size)
            assertEquals("note-reindex_0", fakeChunkRepository.stored[0].chunkId)
            assertEquals("hash-reindex-123", fakeChunkRepository.stored[0].sourceChecksum)
        }
}
