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

package com.locus.app.ui.trash

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeTrashRepository
    private lateinit var viewModel: TrashViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeTrashRepository()
        viewModel = TrashViewModel(fakeRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_emitsTrashedNotesFromRepository() =
        runTest {
            val sampleNote =
                Note(
                    id = "trash-1",
                    title = "Trashed Note",
                    type = NoteType.NOTE,
                    folderPath = "Work/Archive",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = Instant.now(),
                    modified = Instant.now(),
                    checksum = "hash",
                )

            viewModel.uiState.test {
                val initial = awaitItem()
                assertTrue(initial.loading)
                assertTrue(initial.trashedNotes.isEmpty())

                fakeRepo.emitTrash(listOf(sampleNote))
                val loaded = awaitItem()
                assertFalse(loaded.loading)
                assertEquals(1, loaded.trashedNotes.size)
                assertEquals("trash-1", loaded.trashedNotes[0].id)
                assertEquals("Work/Archive", loaded.trashedNotes[0].folderPath)
            }
        }

    @Test
    fun restoreNote_delegatesToRepository() =
        runTest {
            viewModel.restoreNote("trash-99")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("trash-99", fakeRepo.lastRestoredId)
        }

    private class FakeTrashRepository : NoteRepository {
        private val trashFlow = MutableStateFlow<List<Note>>(emptyList())
        var lastRestoredId: String? = null
        var lastDeletedId: String? = null

        fun emitTrash(notes: List<Note>) {
            trashFlow.value = notes
        }

        override fun observeTrash(): Flow<List<Note>> = trashFlow

        override suspend fun restoreNote(noteId: String) {
            lastRestoredId = noteId
        }

        override suspend fun deleteNote(noteId: String) {
            lastDeletedId = noteId
        }

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = MutableStateFlow(emptyList())

        override fun observeAllNotes(): Flow<List<Note>> = MutableStateFlow(emptyList())

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
        ): Note = error("Not needed")

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
}
