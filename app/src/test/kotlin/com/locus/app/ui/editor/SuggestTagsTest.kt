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
import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.notes.SuggestTagsUseCase
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SuggestTagsTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
        }

    private lateinit var fakeClient: FakeChatModelClient
    private lateinit var useCase: SuggestTagsUseCase
    private lateinit var fakeRepo: FakeNoteRepository
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        fakeClient = FakeChatModelClient()
        useCase = SuggestTagsUseCase(chatModelClient = fakeClient)
        fakeRepo = FakeNoteRepository()

        val savedStateHandle =
            SavedStateHandle(mapOf(LocusDestinations.NOTE_ID_ARG to "test-note-1"))
        viewModel =
            EditorViewModel(
                repo = fakeRepo,
                dispatchers = testDispatchers,
                savedStateHandle = savedStateHandle,
                suggestTagsUseCase = useCase,
            )
    }

    @Test
    fun parseTags_cleansAndExtractsValidTags() {
        val raw = "#android, -compose, Kotlin, INVALID!TAG, architecture"
        val tags = useCase.parseTags(raw)
        assertEquals(listOf("android", "compose", "kotlin", "architecture"), tags)
    }

    @Test
    fun suggestTags_uncheckingTagBeforeApply_mergesOnlyApprovedSubset() =
        runTest(testDispatcher) {
            val noteId = "test-note-1"
            val initialNote =
                Note(
                    id = noteId,
                    title = "Title",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = listOf("pre-existing"),
                    created = Instant.EPOCH,
                    modified = Instant.EPOCH,
                    checksum = Checksum.sha256(""),
                )
            fakeRepo.notes[noteId] = initialNote
            viewModel.onBodyChange("This is an android note about compose and kotlin.")

            fakeClient.responseTokens = listOf("android, compose, kotlin")

            viewModel.suggestTags()
            assertTrue(viewModel.tagSuggestionState.value?.isLoading == true)
            advanceUntilIdle()

            val state = viewModel.tagSuggestionState.value
            assertNotNull(state)
            assertFalse(state!!.isLoading)
            assertEquals(listOf("android", "compose", "kotlin"), state.suggestedTags)

            // User unchecks "kotlin", keeping only ["android", "compose"]
            val approved = listOf("android", "compose")
            viewModel.applySuggestedTags(approved)
            advanceUntilIdle()

            assertNull(viewModel.tagSuggestionState.value)

            // Approved tags merge with (don't replace) pre-existing tags
            val updatedNote = fakeRepo.notes[noteId]
            assertNotNull(updatedNote)
            assertEquals(listOf("pre-existing", "android", "compose"), updatedNote!!.tags)
            assertFalse(updatedNote.tags.contains("kotlin"))
        }

    private class FakeChatModelClient : ChatModelClient {
        var responseTokens = listOf("android, architecture")

        override fun generate(
            prompt: String,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> =
            flow {
                for (token in responseTokens) {
                    emit(StreamEvent.TokenDelta(token))
                }
                emit(StreamEvent.Done())
            }
    }

    private class FakeNoteRepository : NoteRepository {
        val notes = mutableMapOf<String, Note>()

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

        override fun observeAllNotes(): Flow<List<Note>> = flowOf(notes.values.toList())

        override suspend fun readBody(noteId: String): String = ""

        override suspend fun getNote(noteId: String): Note? = notes[noteId]

        override suspend fun setTags(
            noteId: String,
            tags: List<String>,
        ) {
            val existing = notes[noteId]
            if (existing != null) {
                notes[noteId] = existing.copy(tags = tags)
            }
        }

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
