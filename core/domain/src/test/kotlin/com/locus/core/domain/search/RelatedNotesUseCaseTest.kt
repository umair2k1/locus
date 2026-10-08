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

import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class RelatedNotesUseCaseTest {
    private lateinit var fakeChunkRepository: FakeChunkRepository
    private lateinit var fakeNoteRepository: FakeNoteRepository
    private lateinit var useCase: RelatedNotesUseCase

    @Before
    fun setUp() {
        fakeChunkRepository = FakeChunkRepository()
        fakeNoteRepository = FakeNoteRepository()
        useCase = RelatedNotesUseCase(fakeChunkRepository, fakeNoteRepository)
    }

    @Test
    fun relatedNotes_surfaceHighOverlapNotes_andExcludeUnrelatedNotes() =
        runTest {
            val note1 = createNote("note-1", "Android Compose Architecture")
            val note2 = createNote("note-2", "Android Compose State Management")
            val note3 = createNote("note-3", "Baking Chocolate Chip Cookies")

            fakeNoteRepository.notes[note1.id] = note1
            fakeNoteRepository.notes[note2.id] = note2
            fakeNoteRepository.notes[note3.id] = note3

            // Note 1 and Note 2 have very close vectors (cosine sim ~ 0.96)
            val vec1 = floatArrayOf(0.8f, 0.6f, 0.0f, 0.0f)
            val vec2 = floatArrayOf(0.7f, 0.7f, 0.05f, 0.0f)
            // Note 3 is almost orthogonal to Note 1 (cosine sim ~ 0.0)
            val vec3 = floatArrayOf(0.0f, 0.0f, 0.9f, 0.4f)

            fakeChunkRepository.meanEmbeddings["note-1"] = vec1
            fakeChunkRepository.meanEmbeddings["note-2"] = vec2
            fakeChunkRepository.meanEmbeddings["note-3"] = vec3

            val relatedToNote1 = useCase.execute(noteId = "note-1", threshold = 0.65f)

            // Note 2 is surfaced
            assertEquals(1, relatedToNote1.size)
            assertEquals("note-2", relatedToNote1.first().noteId)
            assertEquals("Android Compose State Management", relatedToNote1.first().title)
            assertTrue(relatedToNote1.first().similarity > 0.9f)

            // Note 3 (unrelated) and Note 1 (self) are not present
            assertFalse(relatedToNote1.any { it.noteId == "note-3" })
            assertFalse(relatedToNote1.any { it.noteId == "note-1" })
        }

    private fun createNote(
        id: String,
        title: String,
    ): Note =
        Note(
            id = id,
            title = title,
            type = NoteType.NOTE,
            folderPath = "",
            pinned = false,
            color = null,
            tags = emptyList(),
            created = Instant.EPOCH,
            modified = Instant.EPOCH,
            checksum = Checksum.sha256(""),
        )

    private class FakeChunkRepository : ChunkRepository {
        val meanEmbeddings = mutableMapOf<String, FloatArray>()

        override suspend fun meanEmbeddingForNote(noteId: String): FloatArray? = meanEmbeddings[noteId]

        override suspend fun allNoteMeanEmbeddings(): Map<String, FloatArray> = meanEmbeddings

        override suspend fun getMetadata(noteId: String): ChunkMetadata? = null

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) {
            // no-op
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> = emptyList()

        override suspend fun deleteAll() {
            // no-op
        }
    }

    private class FakeNoteRepository : NoteRepository {
        val notes = mutableMapOf<String, Note>()

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

        override fun observeAllNotes(): Flow<List<Note>> = flowOf(notes.values.toList())

        override suspend fun readBody(noteId: String): String = ""

        override suspend fun listFolders(): List<String> = emptyList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) {
            // no-op
        }

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note = error("")

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            // no-op
        }

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) {
            // no-op
        }

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) {
            // no-op
        }

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }
}
