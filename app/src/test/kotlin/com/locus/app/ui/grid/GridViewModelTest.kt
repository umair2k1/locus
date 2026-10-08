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

package com.locus.app.ui.grid

import app.cash.turbine.test
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GridViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeNoteRepository
    private lateinit var viewModel: GridViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeNoteRepository()
        viewModel = GridViewModel(fakeRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_emitsNotesFromRepository() =
        runTest {
            val sampleNote =
                Note(
                    id = "0191ebc2-841e-7b28-b072-46ebc605cf52",
                    title = "Test Note",
                    type = NoteType.NOTE,
                    folderPath = "Personal",
                    pinned = true,
                    color = "#F28B82",
                    tags = listOf("ideas"),
                    created = Instant.parse("2026-09-16T10:00:00Z"),
                    modified = Instant.parse("2026-09-16T10:00:00Z"),
                    checksum = "dummychecksum",
                )

            viewModel.uiState.test {
                // Initial state
                val initial = awaitItem()
                assertEquals(true, initial.loading)
                assertEquals(emptyList<Note>(), initial.notes)

                // Emit new notes from repo
                fakeRepo.emitNotes(listOf(sampleNote))
                testDispatcher.scheduler.advanceUntilIdle()

                val loaded = awaitItem()
                assertEquals(false, loaded.loading)
                assertEquals(1, loaded.notes.size)
                assertEquals("Test Note", loaded.notes[0].title)
                assertEquals(true, loaded.notes[0].pinned)
                assertEquals("#F28B82", loaded.notes[0].color)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun setPinned_delegatesToRepository() =
        runTest {
            viewModel.setPinned("note-123", true)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("note-123", fakeRepo.lastPinnedId)
            assertEquals(true, fakeRepo.lastPinnedValue)

            viewModel.setPinned("note-123", false)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("note-123", fakeRepo.lastPinnedId)
            assertEquals(false, fakeRepo.lastPinnedValue)
        }

    @Test
    fun setColor_delegatesToRepository() =
        runTest {
            viewModel.setColor("note-456", "#CCFF90")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("note-456", fakeRepo.lastColorId)
            assertEquals("#CCFF90", fakeRepo.lastColorValue)

            viewModel.setColor("note-456", null)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("note-456", fakeRepo.lastColorId)
            assertNull(fakeRepo.lastColorValue)
        }

    private class FakeNoteRepository : NoteRepository {
        private val notesFlow = MutableStateFlow<List<Note>>(emptyList())

        var lastPinnedId: String? = null
        var lastPinnedValue: Boolean? = null
        var lastColorId: String? = null
        var lastColorValue: String? = null

        fun emitNotes(notes: List<Note>) {
            notesFlow.value = notes
        }

        override fun observeAllNotes(): Flow<List<Note>> = notesFlow

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = notesFlow

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) {
            lastPinnedId = noteId
            lastPinnedValue = pinned
        }

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) {
            lastColorId = noteId
            lastColorValue = color
        }

        override suspend fun readBody(noteId: String): String = ""

        override suspend fun listFolders(): List<String> = emptyList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) = Unit

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note = throw NotImplementedError()

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) = Unit

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }
}
