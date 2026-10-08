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

package com.locus.core.domain.notes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class MergeNotesUseCaseTest {
    private lateinit var fakeRepository: FakeNoteRepository
    private lateinit var useCase: MergeNotesUseCase

    @Before
    fun setUp() {
        fakeRepository = FakeNoteRepository()
        useCase = MergeNotesUseCase(fakeRepository)
    }

    @Test
    fun mergeTwoSourceNotesIntoDestination_concatenatesBodiesUnderHeadingsAndTrashesBothSources() =
        runTest {
            val dest = fakeRepository.createNote("", "Trip Summary", NoteType.NOTE)
            fakeRepository.edit(dest.id, "Summary of our summer trip.")

            val src1 = fakeRepository.createNote("", "Flight Details", NoteType.NOTE)
            fakeRepository.edit(src1.id, "Flight AA100 departs at 10:00 AM.")

            val src2 = fakeRepository.createNote("", "Hotel Booking", NoteType.NOTE)
            fakeRepository.edit(src2.id, "Reservation #12345 at Grand Hotel.")

            val result =
                useCase(
                    destinationNoteId = dest.id,
                    sourceNoteIds = listOf(src1.id, src2.id),
                )

            // Acceptance: keeps the destination note's id
            assertEquals(dest.id, result.id)

            // Acceptance: one note containing both bodies under their original titles as
            // headings
            val finalBody = fakeRepository.readBody(dest.id)
            val expectedBody =
                """
                # Trip Summary

                Summary of our summer trip.

                # Flight Details

                Flight AA100 departs at 10:00 AM.

                # Hotel Booking

                Reservation #12345 at Grand Hotel.
                """.trimIndent() +
                    "\n"
            assertEquals(expectedBody, finalBody)

            // Acceptance: both sources land in Trash (not hard-deleted, consistent with N-8)
            val trashed = fakeRepository.trashList
            assertEquals(2, trashed.size)
            assertTrue(trashed.any { it.id == src1.id })
            assertTrue(trashed.any { it.id == src2.id })

            // The source notes are no longer in active notes
            val activeNotes = fakeRepository.activeList
            assertEquals(1, activeNotes.size)
            assertEquals(dest.id, activeNotes.first().id)
        }

    @Test
    fun mergeSingleSourceNoteIntoDestination_concatenatesBodiesAndTrashesSource() =
        runTest {
            val dest = fakeRepository.createNote("", "Meeting Notes", NoteType.NOTE)
            fakeRepository.edit(dest.id, "Discussed Q3 roadmaps.")

            val src = fakeRepository.createNote("", "Action Items", NoteType.NOTE)
            fakeRepository.edit(src.id, "- [ ] Implement write tools\n- [ ] Write tests")

            val result = useCase(destinationNoteId = dest.id, sourceNoteId = src.id)

            assertEquals(dest.id, result.id)
            val finalBody = fakeRepository.readBody(dest.id)
            val expectedBody =
                """
                # Meeting Notes

                Discussed Q3 roadmaps.

                # Action Items

                - [ ] Implement write tools
                - [ ] Write tests
                """.trimIndent() +
                    "\n"
            assertEquals(expectedBody, finalBody)

            assertEquals(1, fakeRepository.trashList.size)
            assertEquals(src.id, fakeRepository.trashList.first().id)
        }

    @Test
    fun mergeWithBlankBody_rendersHeadingOnly() =
        runTest {
            val dest = fakeRepository.createNote("", "Main Note", NoteType.NOTE)
            val src = fakeRepository.createNote("", "Empty Checklist", NoteType.CHECKLIST)

            useCase(destinationNoteId = dest.id, sourceNoteId = src.id)

            val finalBody = fakeRepository.readBody(dest.id)
            val expectedBody =
                """
                # Main Note

                # Empty Checklist
                """.trimIndent() +
                    "\n"
            assertEquals(expectedBody, finalBody)
        }

    @Test(expected = IllegalArgumentException::class)
    fun mergeWithBlankDestination_throwsException() =
        runTest {
            useCase(destinationNoteId = "  ", sourceNoteId = "src-1")
        }

    @Test(expected = IllegalArgumentException::class)
    fun mergeWithDestinationAsOnlySource_throwsException() =
        runTest {
            useCase(destinationNoteId = "note-1", sourceNoteIds = listOf("note-1"))
        }

    private class FakeNoteRepository : NoteRepository {
        private val notes = mutableMapOf<String, Note>()
        private val bodies = mutableMapOf<String, String>()
        private val trashedNotes = mutableMapOf<String, Note>()

        private val notesFlow = MutableStateFlow<List<Note>>(emptyList())
        private val trashFlow = MutableStateFlow<List<Note>>(emptyList())

        val activeList: List<Note>
            get() = notes.values.toList()
        val trashList: List<Note>
            get() = trashedNotes.values.toList()

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = notesFlow.asStateFlow()

        override fun observeAllNotes(): Flow<List<Note>> = notesFlow.asStateFlow()

        override suspend fun readBody(noteId: String): String = bodies[noteId].orEmpty()

        override suspend fun listFolders(): List<String> = emptyList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) = Unit

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note {
            val id = "note-${System.nanoTime()}"
            val note =
                Note(
                    id = id,
                    title = title,
                    type = type,
                    folderPath = folderPath,
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = Instant.now(),
                    modified = Instant.now(),
                    checksum = "empty",
                )
            notes[id] = note
            bodies[id] = ""
            notesFlow.value = notes.values.toList()
            return note
        }

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            bodies[noteId] = newBody
            val existing = notes[noteId]
            if (existing != null) {
                notes[noteId] = existing.copy(modified = Instant.now())
                notesFlow.value = notes.values.toList()
            }
        }

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) = Unit

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) = Unit

        override suspend fun deleteNote(noteId: String) {
            val note = notes.remove(noteId)
            if (note != null) {
                trashedNotes[noteId] = note
                notesFlow.value = notes.values.toList()
                trashFlow.value = trashedNotes.values.toList()
            }
        }

        override fun observeTrash(): Flow<List<Note>> = trashFlow.asStateFlow()

        override suspend fun getNote(noteId: String): Note? = notes[noteId] ?: trashedNotes[noteId]

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }
}
