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

package com.locus.app.ui.editor

import com.locus.core.domain.notes.FlushTrigger
import com.locus.core.domain.notes.HistoryRevision
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeHistoryNoteRepository
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeHistoryNoteRepository()
        viewModel = HistoryViewModel(fakeRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadRevisions_loadsFromRepository_andSelectsFirst() =
        runTest {
            val noteId = "test-note-1"
            val rev1 = HistoryRevision(timestamp = 2000L, body = "Newer version")
            val rev2 = HistoryRevision(timestamp = 1000L, body = "Older version")
            fakeRepo.revisionsMap[noteId] = listOf(rev1, rev2)

            viewModel.loadRevisions(noteId)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals(2, state.revisions.size)
            assertEquals(rev1, state.selectedRevision)
        }

    @Test
    fun loadRevisions_emptyNoteId_resetsState() =
        runTest {
            viewModel.loadRevisions("")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertTrue(state.revisions.isEmpty())
            assertNull(state.selectedRevision)
        }

    @Test
    fun selectRevision_updatesSelectedRevision() =
        runTest {
            val rev1 = HistoryRevision(timestamp = 2000L, body = "Newer")
            val rev2 = HistoryRevision(timestamp = 1000L, body = "Older")

            viewModel.selectRevision(rev2)
            assertEquals(rev2, viewModel.uiState.value.selectedRevision)

            viewModel.selectRevision(rev1)
            assertEquals(rev1, viewModel.uiState.value.selectedRevision)
        }

    @Test
    fun restoreRevision_callsRepoEditAndForceFlush_andSetsTitleWhenPresent() =
        runTest {
            val noteId = "note-to-restore"
            val revisionToRestore =
                HistoryRevision(timestamp = 1500L, body = "Restored content", title = "Restored Title")
            var restoredCallbackInvoked = false

            viewModel.restoreRevision(noteId, revisionToRestore) { restoredCallbackInvoked = true }
            advanceUntilIdle()

            assertTrue(restoredCallbackInvoked)
            assertEquals(noteId, fakeRepo.lastEditedNoteId)
            assertEquals("Restored content", fakeRepo.lastEditedBody)
            assertEquals("Restored Title", fakeRepo.lastSetTitle)
            assertEquals(noteId, fakeRepo.lastFlushedNoteId)
            assertEquals(FlushTrigger.EDITOR_CLOSE, fakeRepo.lastFlushTrigger)
        }

    private class FakeHistoryNoteRepository : NoteRepository {
        val revisionsMap = mutableMapOf<String, List<HistoryRevision>>()
        var lastEditedNoteId: String? = null
        var lastEditedBody: String? = null
        var lastSetTitle: String? = null
        var lastFlushedNoteId: String? = null
        var lastFlushTrigger: FlushTrigger? = null

        override suspend fun listRevisions(noteId: String): List<HistoryRevision> = revisionsMap[noteId] ?: emptyList()

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            lastEditedNoteId = noteId
            lastEditedBody = newBody
        }

        override suspend fun setTitle(
            noteId: String,
            title: String,
        ) {
            lastSetTitle = title
        }

        override suspend fun forceFlush(
            noteId: String,
            trigger: FlushTrigger,
        ) {
            lastFlushedNoteId = noteId
            lastFlushTrigger = trigger
        }

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = MutableStateFlow(emptyList())

        override fun observeAllNotes(): Flow<List<Note>> = MutableStateFlow(emptyList())

        override suspend fun readBody(noteId: String): String = ""

        override suspend fun listFolders(): List<String> = emptyList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) {
            // no-op for testing
        }

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note = error("Not needed")

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) {
            // no-op for testing
        }

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) {
            // no-op for testing
        }

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }
}
