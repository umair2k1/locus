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

package com.locus.core.domain.search

import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class IndexingCoordinatorTest {
    private class FakeEmbeddingGateway : EmbeddingGateway {
        var callCount = 0
        val embeddedTexts = mutableListOf<String>()

        override suspend fun embed(text: String): FloatArray {
            callCount++
            embeddedTexts.add(text)
            return floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        }
    }

    private class FakeChunkRepository : ChunkRepository {
        val storedChunks = mutableMapOf<String, MutableList<EmbeddedChunk>>()
        val metadata = mutableMapOf<String, ChunkMetadata>()

        override suspend fun getMetadata(noteId: String): ChunkMetadata? = metadata[noteId]

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) {
            if (chunks.isEmpty()) {
                storedChunks.remove(noteId)
                metadata.remove(noteId)
            } else {
                storedChunks[noteId] = chunks.toMutableList()
                metadata[noteId] =
                    ChunkMetadata(
                        sourceChecksum = chunks.first().sourceChecksum,
                        embeddingModelId = chunks.first().embeddingModelId,
                    )
            }
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> = emptyList()

        override suspend fun deleteAll() {
            storedChunks.clear()
            metadata.clear()
        }

        override suspend fun countChunks(): Int = storedChunks.values.sumOf { it.size }
    }

    @Suppress("EmptyFunctionBlock")
    private class FakeNoteRepository : NoteRepository {
        val notesFlow = MutableStateFlow<List<Note>>(emptyList())
        val noteBodies = mutableMapOf<String, String>()

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = notesFlow

        override fun observeAllNotes(): Flow<List<Note>> = notesFlow

        override suspend fun readBody(noteId: String): String = noteBodies[noteId] ?: ""

        override suspend fun listFolders(): List<String> = emptyList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) {}

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note = throw UnsupportedOperationException()

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {}

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) {}

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) {}

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }

    private fun sampleNote(
        id: String = "note-1",
        title: String = "Test Note",
        checksum: String = "chk-1",
    ) = Note(
        id = id,
        title = title,
        type = NoteType.NOTE,
        folderPath = "/",
        pinned = false,
        color = null,
        tags = emptyList(),
        created = Instant.EPOCH,
        modified = Instant.EPOCH,
        checksum = checksum,
    )

    @Test
    fun reindexIfNeeded_embedsNewNote() =
        runTest {
            val embeddingGateway = FakeEmbeddingGateway()
            val chunkRepository = FakeChunkRepository()
            val noteRepository = FakeNoteRepository()
            val coordinator =
                IndexingCoordinator(
                    embeddingGateway = embeddingGateway,
                    chunkRepository = chunkRepository,
                    noteRepository = noteRepository,
                )

            val note = sampleNote(id = "note-1", checksum = "chk-1")
            val body = "# Intro\nThis is a test body with some content to embed."

            coordinator.reindexIfNeeded(note, body)

            assertTrue("Embedding calls should be > 0", embeddingGateway.callCount > 0)
            assertEquals(1, chunkRepository.storedChunks["note-1"]?.size)
            assertEquals("chk-1", chunkRepository.getMetadata("note-1")?.sourceChecksum)
            assertEquals(
                IndexingCoordinator.DEFAULT_MODEL_ID,
                chunkRepository.getMetadata("note-1")?.embeddingModelId,
            )
        }

    @Test
    fun reindexIfNeeded_skipsUnchangedNoteWithZeroEmbeddingCalls() =
        runTest {
            val embeddingGateway = FakeEmbeddingGateway()
            val chunkRepository = FakeChunkRepository()
            val noteRepository = FakeNoteRepository()
            val coordinator =
                IndexingCoordinator(
                    embeddingGateway = embeddingGateway,
                    chunkRepository = chunkRepository,
                    noteRepository = noteRepository,
                )

            val note = sampleNote(id = "note-1", checksum = "chk-1")
            val body = "# Heading\nSome meaningful text to chunk."

            // First index
            coordinator.reindexIfNeeded(note, body)
            val initialCalls = embeddingGateway.callCount
            assertTrue(initialCalls > 0)

            // Re-index with identical note and body
            coordinator.reindexIfNeeded(note, body)

            // Must perform ZERO additional embedding calls
            assertEquals(initialCalls, embeddingGateway.callCount)
        }

    @Test
    fun reindexIfNeeded_reindexesOnChecksumChange() =
        runTest {
            val embeddingGateway = FakeEmbeddingGateway()
            val chunkRepository = FakeChunkRepository()
            val noteRepository = FakeNoteRepository()
            val coordinator =
                IndexingCoordinator(
                    embeddingGateway = embeddingGateway,
                    chunkRepository = chunkRepository,
                    noteRepository = noteRepository,
                )

            val note = sampleNote(id = "note-1", checksum = "chk-1")
            val body = "# Heading\nInitial text."
            coordinator.reindexIfNeeded(note, body)
            val firstCallCount = embeddingGateway.callCount

            // Note modified: checksum changed
            val modifiedNote = note.copy(checksum = "chk-2")
            val newBody = "# Heading\nModified text with updated content."
            coordinator.reindexIfNeeded(modifiedNote, newBody)

            assertTrue(embeddingGateway.callCount > firstCallCount)
            assertEquals("chk-2", chunkRepository.getMetadata("note-1")?.sourceChecksum)
        }

    @Test
    fun reindexIfNeeded_reindexesWhenModelIdDiffers() =
        runTest {
            val embeddingGateway = FakeEmbeddingGateway()
            val chunkRepository = FakeChunkRepository()
            val noteRepository = FakeNoteRepository()
            val coordinator =
                IndexingCoordinator(
                    embeddingGateway = embeddingGateway,
                    chunkRepository = chunkRepository,
                    noteRepository = noteRepository,
                    initialModelId = "model-v1",
                )

            val note = sampleNote(id = "note-1", checksum = "chk-1")
            val body = "# Heading\nText to index."
            coordinator.reindexIfNeeded(note, body)
            val firstCallCount = embeddingGateway.callCount

            // New coordinator instance or updated model
            val newModelCoordinator =
                IndexingCoordinator(
                    embeddingGateway = embeddingGateway,
                    chunkRepository = chunkRepository,
                    noteRepository = noteRepository,
                    initialModelId = "model-v2",
                )

            newModelCoordinator.reindexIfNeeded(note, body)

            assertTrue(embeddingGateway.callCount > firstCallCount)
            assertEquals("model-v2", chunkRepository.getMetadata("note-1")?.embeddingModelId)
        }

    @Test
    fun reindexAllForModelChange_reindexesAllNotes() =
        runTest {
            val embeddingGateway = FakeEmbeddingGateway()
            val chunkRepository = FakeChunkRepository()
            val noteRepository = FakeNoteRepository()
            val coordinator =
                IndexingCoordinator(
                    embeddingGateway = embeddingGateway,
                    chunkRepository = chunkRepository,
                    noteRepository = noteRepository,
                    initialModelId = "old-model",
                )

            val noteA = sampleNote(id = "note-a", checksum = "chk-a")
            val noteB = sampleNote(id = "note-b", checksum = "chk-b")
            noteRepository.notesFlow.value = listOf(noteA, noteB)
            noteRepository.noteBodies["note-a"] = "Body of note A"
            noteRepository.noteBodies["note-b"] = "Body of note B"

            coordinator.reindexIfNeeded(noteA, noteRepository.noteBodies["note-a"]!!)
            coordinator.reindexIfNeeded(noteB, noteRepository.noteBodies["note-b"]!!)
            val initialCalls = embeddingGateway.callCount
            assertEquals("old-model", chunkRepository.getMetadata("note-a")?.embeddingModelId)
            assertEquals("old-model", chunkRepository.getMetadata("note-b")?.embeddingModelId)

            coordinator.reindexAllForModelChange("new-model")

            assertEquals(initialCalls * 2, embeddingGateway.callCount)
            assertEquals("new-model", coordinator.currentModelId)
            assertEquals("new-model", chunkRepository.getMetadata("note-a")?.embeddingModelId)
            assertEquals("new-model", chunkRepository.getMetadata("note-b")?.embeddingModelId)
        }

    @Test
    fun estimateReindexTime_scalesLinearlyWithChunkCount() =
        runTest {
            val embeddingGateway = FakeEmbeddingGateway()
            val chunkRepository = FakeChunkRepository()
            val noteRepository = FakeNoteRepository()
            val coordinator =
                IndexingCoordinator(
                    embeddingGateway = embeddingGateway,
                    chunkRepository = chunkRepository,
                    noteRepository = noteRepository,
                )

            val chunks50 =
                (1..50).map { i ->
                    EmbeddedChunk(
                        chunkId = "chunk_$i",
                        noteId = "note_1",
                        headingPath = emptyList(),
                        text = "Chunk text $i",
                        embedding = floatArrayOf(0.1f),
                        embeddingModelId = "model",
                        sourceChecksum = "checksum",
                    )
                }
            chunkRepository.replaceChunksForNote("note_1", chunks50)
            assertEquals(50, coordinator.getTotalChunkCount())

            val estimate50 = coordinator.estimateReindexTime(tokPerSecond = 25.0)
            assertEquals(2.0, estimate50, 0.001)

            val chunks100 =
                (1..100).map { i ->
                    EmbeddedChunk(
                        chunkId = "chunk2_$i",
                        noteId = "note_2",
                        headingPath = emptyList(),
                        text = "Chunk text $i",
                        embedding = floatArrayOf(0.1f),
                        embeddingModelId = "model",
                        sourceChecksum = "checksum",
                    )
                }
            chunkRepository.replaceChunksForNote("note_2", chunks100)
            assertEquals(150, coordinator.getTotalChunkCount())

            val estimate150 = coordinator.estimateReindexTime(tokPerSecond = 25.0)
            assertEquals(6.0, estimate150, 0.001)
            assertEquals(estimate50 * 3.0, estimate150, 0.001)
        }
}
