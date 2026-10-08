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

package com.locus.core.domain.dashboard

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import com.locus.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ComputeDigestUseCaseTest {
    private val fixedNow = Instant.parse("2026-10-01T12:00:00Z")
    private lateinit var fakeRepo: FakeNoteRepository
    private lateinit var fakeClient: FakeChatModelClient
    private lateinit var useCase: ComputeDigestUseCase

    @Before
    fun setUp() {
        fakeRepo = FakeNoteRepository()
        fakeClient = FakeChatModelClient()
        useCase =
            ComputeDigestUseCase(
                noteRepository = fakeRepo,
                chatModelClient = fakeClient,
                clock = Clock { fixedNow },
            )
    }

    @Test
    fun execute_dailyPeriod_includesOnlyInPeriodNotes_andLinksToRealNoteIds() =
        runTest {
            // Note A: modified 2 hours ago (within 24 hours)
            val noteA =
                createNote(
                    id = "note-uuid-a",
                    title = "Recent Meeting Notes",
                    modified = fixedNow.minusSeconds(2 * 3600L),
                    body = "Discussed architecture roadmap.",
                )
            // Note B: modified 25 hours ago (outside daily, within weekly)
            val noteB =
                createNote(
                    id = "note-uuid-b",
                    title = "Yesterday Task List",
                    modified = fixedNow.minusSeconds(25 * 3600L),
                    body = "Completed initial setup.",
                )
            // Note C: modified 40 days ago (outside daily, weekly, and monthly)
            val noteC =
                createNote(
                    id = "note-uuid-c",
                    title = "Ancient Archive",
                    modified = fixedNow.minusSeconds(40 * 86_400L),
                    body = "Old history.",
                )

            fakeRepo.notes[noteA.id] = noteA
            fakeRepo.notes[noteB.id] = noteB
            fakeRepo.notes[noteC.id] = noteC

            // Daily digest
            val dailyDigest = useCase.execute(DigestPeriod.DAILY)
            assertNotNull(dailyDigest)
            assertEquals(1, dailyDigest!!.items.size)
            assertEquals(noteA.id, dailyDigest.items[0].noteId)
            assertEquals("Recent Meeting Notes", dailyDigest.items[0].noteTitle)

            // Weekly digest includes A and B, but excludes C
            val weeklyDigest = useCase.execute(DigestPeriod.WEEKLY)
            assertNotNull(weeklyDigest)
            assertEquals(2, weeklyDigest!!.items.size)
            val weeklyIds = weeklyDigest.items.map { it.noteId }
            assertTrue(weeklyIds.contains(noteA.id))
            assertTrue(weeklyIds.contains(noteB.id))
            assertFalse(weeklyIds.contains(noteC.id))

            // Verify each item links to a real note id
            for (item in weeklyDigest.items) {
                assertTrue(fakeRepo.notes.containsKey(item.noteId))
            }
        }

    private fun createNote(
        id: String,
        title: String,
        modified: Instant,
        body: String,
    ): Note {
        fakeRepo.bodies[id] = body
        return Note(
            id = id,
            title = title,
            type = NoteType.NOTE,
            folderPath = "",
            pinned = false,
            color = null,
            tags = emptyList(),
            created = modified.minusSeconds(3600L),
            modified = modified,
            checksum = Checksum.sha256(body),
        )
    }

    private class FakeChatModelClient : ChatModelClient {
        override fun generate(
            prompt: String,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> =
            flow {
                emit(StreamEvent.TokenDelta("Digest Summary Generated"))
                emit(StreamEvent.Done())
            }
    }

    private class FakeNoteRepository : NoteRepository {
        val notes = mutableMapOf<String, Note>()
        val bodies = mutableMapOf<String, String>()

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

        override fun observeAllNotes(): Flow<List<Note>> = flowOf(notes.values.toList())

        override suspend fun readBody(noteId: String): String = bodies[noteId].orEmpty()

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
