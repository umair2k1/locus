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

import androidx.lifecycle.SavedStateHandle
import com.locus.app.navigation.LocusDestinations
import com.locus.core.domain.notes.FlushTrigger
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
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
class EditorViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testDispatchers: TestDispatcherProvider
    private lateinit var fakeRepo: FakeNoteRepository
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testDispatchers = TestDispatcherProvider(testDispatcher)
        fakeRepo = FakeNoteRepository()
        viewModel = EditorViewModel(fakeRepo, testDispatchers)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadNote_readsBodyAndObservesTitleAndType() =
        runTest {
            val sampleNote =
                Note(
                    id = "note-123",
                    title = "My Shopping List",
                    type = NoteType.CHECKLIST,
                    folderPath = "Personal",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = Instant.parse("2026-09-16T10:00:00Z"),
                    modified = Instant.parse("2026-09-16T10:00:00Z"),
                    checksum = "dummy",
                )
            fakeRepo.bodies["note-123"] = "- [ ] Milk\n- [ ] Eggs"
            fakeRepo.emitNotes(listOf(sampleNote))

            viewModel.loadNote("note-123")
            testDispatcher.scheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("- [ ] Milk\n- [ ] Eggs", state.body)
            assertEquals("My Shopping List", state.title)
            assertEquals(NoteType.CHECKLIST, state.type)
        }

    @Test
    fun onBodyChange_updatesUiStateAndCallsRepositoryEdit() =
        runTest {
            viewModel.loadNote("note-123")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onBodyChange("# New Heading\nUpdated content")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("# New Heading\nUpdated content", viewModel.uiState.value.body)
            assertEquals("note-123", fakeRepo.lastEditedNoteId)
            assertEquals("# New Heading\nUpdated content", fakeRepo.lastEditedBody)
        }

    @Test
    fun togglePreview_togglesIsPreviewState() =
        runTest {
            assertFalse(viewModel.uiState.value.isPreview)

            viewModel.togglePreview()
            assertTrue(viewModel.uiState.value.isPreview)

            viewModel.togglePreview()
            assertFalse(viewModel.uiState.value.isPreview)
        }

    @Test
    fun onDispose_triggersForceFlushWithEditorClose() =
        runTest {
            viewModel.loadNote("note-123")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onDispose()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("note-123", fakeRepo.lastFlushedNoteId)
            assertEquals(FlushTrigger.EDITOR_CLOSE, fakeRepo.lastFlushTrigger)
        }

    @Test
    fun onStop_triggersForceFlushWithOnStop() =
        runTest {
            viewModel.loadNote("note-123")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onStop()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("note-123", fakeRepo.lastFlushedNoteId)
            assertEquals(FlushTrigger.ON_STOP, fakeRepo.lastFlushTrigger)
        }

    @Test
    fun init_withSavedStateHandle_loadsNoteAutomatically() =
        runTest {
            fakeRepo.bodies["saved-note-id"] = "Loaded from SavedStateHandle"
            val savedStateHandle =
                SavedStateHandle(
                    mapOf(LocusDestinations.NOTE_ID_ARG to "saved-note-id"),
                )

            val vm = EditorViewModel(fakeRepo, testDispatchers, savedStateHandle)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Loaded from SavedStateHandle", vm.uiState.value.body)
        }

    @Test
    fun onBodyChange_whenNoteIsNew_createsNoteAndPersistsBody() =
        runTest {
            viewModel.loadNote("new")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onBodyChange("# Grocery List\n- [ ] Milk")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Grocery List", fakeRepo.lastCreatedTitle)
            assertEquals(NoteType.CHECKLIST, fakeRepo.lastCreatedType)
            assertEquals("created-note-1", fakeRepo.lastEditedNoteId)
            assertEquals("# Grocery List\n- [ ] Milk", fakeRepo.lastEditedBody)
            assertEquals("# Grocery List\n- [ ] Milk", viewModel.uiState.value.body)
        }

    @Test
    fun onDispose_whenNoteIsNewAndUnchanged_doesNotFlush() =
        runTest {
            viewModel.loadNote("new")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onDispose()
            testDispatcher.scheduler.advanceUntilIdle()

            org.junit.Assert.assertNull(fakeRepo.lastFlushedNoteId)
        }

    @Test
    fun deleteNote_delegatesToRepositoryAndInvokesCallback() =
        runTest {
            viewModel.loadNote("note-to-delete")
            var callbackInvoked = false
            viewModel.deleteNote(onDeleted = { callbackInvoked = true })
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("note-to-delete", fakeRepo.lastDeletedId)
            assertTrue(callbackInvoked)
        }

    @Test
    fun onTitleChange_updatesTitleAndDelegatesToRepository() =
        runTest {
            viewModel.loadNote("existing-note")
            viewModel.onTitleChange("Manual New Title")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Manual New Title", viewModel.uiState.value.title)
            assertEquals("existing-note", fakeRepo.lastTitleNoteId)
            assertEquals("Manual New Title", fakeRepo.lastTitle)
        }

    @Test
    fun onBodyChange_whenTitleNotCustom_updatesDerivedTitle() =
        runTest {
            viewModel.loadNote("new")
            viewModel.onBodyChange("t")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("t", viewModel.uiState.value.title)

            // Continue typing title line
            viewModel.onBodyChange("this is my title\nSecond line")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals("this is my title", viewModel.uiState.value.title)
            assertEquals("created-note-1", fakeRepo.lastTitleNoteId)
            assertEquals("this is my title", fakeRepo.lastTitle)
        }

    @Test
    fun onBodyChange_whenTitleCustom_doesNotOverwriteManualTitle() =
        runTest {
            viewModel.loadNote("new")
            viewModel.onTitleChange("Custom Explicit Title")
            testDispatcher.scheduler.advanceUntilIdle()

            viewModel.onBodyChange("Something totally different in body")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Custom Explicit Title", viewModel.uiState.value.title)
        }

    @Test
    fun onTitleChange_debouncesRapidKeystrokes() =
        runTest {
            viewModel.loadNote("existing-note")

            // Rapid keystrokes within debounce window
            viewModel.onTitleChange("T")
            viewModel.onTitleChange("Ti")
            viewModel.onTitleChange("Tit")
            viewModel.onTitleChange("Title")

            // Before debounce elapses, repo hasn't been called yet
            assertEquals("Title", viewModel.uiState.value.title)

            // Advance time past 600ms debounce
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("Title", viewModel.uiState.value.title)
            assertEquals("existing-note", fakeRepo.lastTitleNoteId)
            assertEquals("Title", fakeRepo.lastTitle)
        }

    @Test
    fun onTitleChange_allowsEmptyTitleWithoutReset() =
        runTest {
            viewModel.loadNote("existing-note")
            viewModel.onTitleChange("")
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("", viewModel.uiState.value.title)
            assertEquals("existing-note", fakeRepo.lastTitleNoteId)
            assertEquals("", fakeRepo.lastTitle)
        }

    @Test
    fun onStop_flushesPendingTitleImmediately() =
        runTest {
            viewModel.loadNote("existing-note")
            viewModel.onTitleChange("Unfinished Title")
            // Call onStop before 600ms elapses
            viewModel.onStop()
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals("existing-note", fakeRepo.lastTitleNoteId)
            assertEquals("Unfinished Title", fakeRepo.lastTitle)
        }

    private class TestDispatcherProvider(
        private val dispatcher: CoroutineDispatcher,
    ) : DispatcherProvider {
        override val io: CoroutineDispatcher
            get() = dispatcher
        override val default: CoroutineDispatcher
            get() = dispatcher
        override val main: CoroutineDispatcher
            get() = dispatcher
        override val mainImmediate: CoroutineDispatcher
            get() = dispatcher
    }

    private class FakeNoteRepository : NoteRepository {
        private val notesFlow = MutableStateFlow<List<Note>>(emptyList())
        val bodies = mutableMapOf<String, String>()

        var lastEditedNoteId: String? = null
        var lastEditedBody: String? = null
        var lastFlushedNoteId: String? = null
        var lastFlushTrigger: FlushTrigger? = null

        var lastDeletedId: String? = null
        var lastTitleNoteId: String? = null
        var lastTitle: String? = null

        override suspend fun setTitle(
            noteId: String,
            newTitle: String,
        ) {
            lastTitleNoteId = noteId
            lastTitle = newTitle
        }

        fun emitNotes(notes: List<Note>) {
            notesFlow.value = notes
        }

        override fun observeAllNotes(): Flow<List<Note>> = notesFlow

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = notesFlow

        override suspend fun readBody(noteId: String): String = bodies[noteId] ?: ""

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            lastEditedNoteId = noteId
            lastEditedBody = newBody
            bodies[noteId] = newBody
        }

        override suspend fun forceFlush(
            noteId: String,
            trigger: FlushTrigger,
        ) {
            lastFlushedNoteId = noteId
            lastFlushTrigger = trigger
        }

        override suspend fun deleteNote(noteId: String) {
            lastDeletedId = noteId
        }

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) = Unit

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) = Unit

        override suspend fun listFolders(): List<String> = emptyList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) = Unit

        var lastCreatedFolder: String? = null
        var lastCreatedTitle: String? = null
        var lastCreatedType: NoteType? = null

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note {
            lastCreatedFolder = folderPath
            lastCreatedTitle = title
            lastCreatedType = type
            val created =
                Note(
                    id = "created-note-1",
                    title = title,
                    type = type,
                    folderPath = folderPath,
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = Instant.parse("2026-09-17T10:00:00Z"),
                    modified = Instant.parse("2026-09-17T10:00:00Z"),
                    checksum = "dummy",
                )
            emitNotes(notesFlow.value + created)
            return created
        }

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }
}
