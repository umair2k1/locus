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

package com.locus.app.ui.search

import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.search.ChunkMetadata
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.EmbeddedChunk
import com.locus.core.domain.search.EmbeddingGateway
import com.locus.core.domain.search.HybridSearchUseCase
import com.locus.core.domain.search.KeywordSearch
import com.locus.core.domain.search.RankedChunk
import com.locus.core.domain.search.SearchResult
import com.locus.core.domain.search.SearchScope
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testDispatchers: TestDispatcherProvider
    private lateinit var fakeKeywordSearch: FakeKeywordSearch
    private lateinit var fakeChunkRepo: FakeChunkRepository
    private lateinit var fakeEmbeddingGateway: FakeEmbeddingGateway
    private lateinit var hybridSearchUseCase: HybridSearchUseCase
    private lateinit var fakeRepo: FakeNoteRepository
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testDispatchers = TestDispatcherProvider(testDispatcher)
        fakeKeywordSearch = FakeKeywordSearch()
        fakeChunkRepo = FakeChunkRepository(isAvailable = true)
        fakeEmbeddingGateway = FakeEmbeddingGateway()
        hybridSearchUseCase =
            HybridSearchUseCase(
                keywordSearch = fakeKeywordSearch,
                chunkRepository = fakeChunkRepo,
                embeddingGateway = fakeEmbeddingGateway,
            )
        fakeRepo = FakeNoteRepository()
        viewModel = SearchViewModel(hybridSearchUseCase, fakeRepo, testDispatchers)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_hasEmptyQueryAndResults() {
        val state = viewModel.uiState.value
        assertEquals("", state.query)
        assertTrue(state.results.isEmpty())
        assertFalse(state.isSearching)
        assertFalse(state.degraded)
        assertTrue(state.scope.isUnconstrained())
        assertFalse(state.isScopePickerVisible)
    }

    @Test
    fun onQueryChange_updatesQueryImmediatelyAndSearchesAfterDebounce() =
        runTest {
            viewModel.onQueryChange("meeting")
            assertEquals("meeting", viewModel.uiState.value.query)

            // Before debounce elapses (at 200ms)
            testDispatcher.scheduler.advanceTimeBy(200)
            assertEquals(0, fakeKeywordSearch.searchCount)

            // After debounce elapses (at 300ms total)
            testDispatcher.scheduler.advanceTimeBy(150)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, fakeKeywordSearch.searchCount)
            assertEquals(1, viewModel.uiState.value.results.size)
            assertEquals(
                "meeting-note",
                viewModel.uiState.value.results[0]
                    .noteId,
            )
            assertFalse(viewModel.uiState.value.isSearching)
            assertFalse(viewModel.uiState.value.degraded)
        }

    @Test
    fun onQueryChange_setsDegradedTrueWhenChunkRepositoryUnavailable() =
        runTest {
            fakeChunkRepo.isAvailable = false
            viewModel.onQueryChange("project")
            testDispatcher.scheduler.advanceTimeBy(350)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, fakeKeywordSearch.searchCount)
            assertTrue(viewModel.uiState.value.degraded)
            assertEquals(
                "meeting-note",
                viewModel.uiState.value.results[0]
                    .noteId,
            )
        }

    @Test
    fun onQueryChange_debouncesRapidKeystrokes() =
        runTest {
            viewModel.onQueryChange("m")
            testDispatcher.scheduler.advanceTimeBy(100)
            viewModel.onQueryChange("me")
            testDispatcher.scheduler.advanceTimeBy(100)
            viewModel.onQueryChange("mee")
            testDispatcher.scheduler.advanceTimeBy(100)
            viewModel.onQueryChange("meet")

            // Only the final query should execute once debounce finishes
            testDispatcher.scheduler.advanceTimeBy(350)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(1, fakeKeywordSearch.searchCount)
            assertEquals("meet", fakeKeywordSearch.lastQuery)
        }

    @Test
    fun onScopeChange_reTriggersSearchWithUpdatedScope() =
        runTest {
            viewModel.onQueryChange("search-term")
            testDispatcher.scheduler.advanceTimeBy(350)
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, fakeKeywordSearch.searchCount)

            val newScope = SearchScope(folderPaths = setOf("Work"))
            viewModel.onScopeChange(newScope)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(2, fakeKeywordSearch.searchCount)
            assertEquals(newScope, fakeKeywordSearch.lastScope)
            assertEquals(newScope, viewModel.uiState.value.scope)
        }

    @Test
    fun setScopePickerVisible_updatesUiState() {
        assertFalse(viewModel.uiState.value.isScopePickerVisible)
        viewModel.setScopePickerVisible(true)
        assertTrue(viewModel.uiState.value.isScopePickerVisible)
        viewModel.setScopePickerVisible(false)
        assertFalse(viewModel.uiState.value.isScopePickerVisible)
    }

    @Test
    fun clearScope_resetsScopeToDefault() =
        runTest {
            viewModel.onScopeChange(SearchScope(noteIds = setOf("id-1")))
            assertFalse(
                viewModel.uiState.value.scope
                    .isUnconstrained(),
            )

            viewModel.clearScope()
            assertTrue(
                viewModel.uiState.value.scope
                    .isUnconstrained(),
            )
        }

    @Test
    fun onQueryChange_blankQueryClearsResultsImmediately() =
        runTest {
            // First run a valid search
            viewModel.onQueryChange("idea")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.results.size)

            // Now enter blank query
            viewModel.onQueryChange("")
            assertEquals("", viewModel.uiState.value.query)
            assertTrue(
                viewModel.uiState.value.results
                    .isEmpty(),
            )
            assertFalse(viewModel.uiState.value.isSearching)
        }

    @Test
    fun clearQuery_clearsQueryAndResults() =
        runTest {
            viewModel.onQueryChange("draft")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, viewModel.uiState.value.results.size)

            viewModel.clearQuery()
            assertEquals("", viewModel.uiState.value.query)
            assertTrue(
                viewModel.uiState.value.results
                    .isEmpty(),
            )
            assertFalse(viewModel.uiState.value.isSearching)
        }

    @Test
    fun rescan_delegatesToRepositoryAndRefreshesSearch() =
        runTest {
            viewModel.onQueryChange("project")
            testDispatcher.scheduler.advanceUntilIdle()
            assertEquals(1, fakeKeywordSearch.searchCount)

            fakeKeywordSearch.resultsToReturn =
                listOf(
                    SearchResult("note-1", "Project A", "Snippet 1"),
                    SearchResult("note-2", "Project B", "Snippet 2"),
                )

            viewModel.rescan()
            testDispatcher.scheduler.advanceUntilIdle()

            assertTrue(fakeRepo.rescanCalled)
            assertEquals(2, fakeKeywordSearch.searchCount)
            assertEquals(2, viewModel.uiState.value.results.size)
            assertFalse(viewModel.uiState.value.isSearching)
        }

    private class FakeKeywordSearch : KeywordSearch {
        var searchCount = 0
        var lastQuery: String? = null
        var lastScope: SearchScope? = null
        var resultsToReturn: List<SearchResult> =
            listOf(
                SearchResult(
                    noteId = "meeting-note",
                    title = "Meeting",
                    snippet = "Snippet",
                ),
            )

        override suspend fun search(
            query: String,
            scope: SearchScope,
        ): List<SearchResult> {
            searchCount++
            lastQuery = query
            lastScope = scope
            return resultsToReturn
        }
    }

    private class FakeChunkRepository(
        var isAvailable: Boolean = true,
    ) : ChunkRepository {
        override suspend fun isAvailable(): Boolean = isAvailable

        override suspend fun getMetadata(noteId: String): ChunkMetadata? = null

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) {
            // No-op in test fake
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> = emptyList()

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            scope: SearchScope,
        ): List<RankedChunk> = emptyList()

        override suspend fun getChunksForNote(noteId: String): List<EmbeddedChunk> = emptyList()

        override suspend fun getChunk(chunkId: String): EmbeddedChunk? = null

        override suspend fun deleteAll() {
            // No-op in test fake
        }
    }

    private class FakeEmbeddingGateway : EmbeddingGateway {
        override suspend fun embed(text: String): FloatArray = floatArrayOf(0.1f, 0.2f)
    }

    private class FakeNoteRepository : NoteRepository {
        var rescanCalled = false

        override suspend fun rescan(): RescanReport {
            rescanCalled = true
            return RescanReport(added = 1, changed = 0, removed = 0)
        }

        override fun observeNotesInFolder(folderPath: String) = emptyFlow<List<Note>>()

        override fun observeAllNotes() = emptyFlow<List<Note>>()

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

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) = Unit

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) = Unit
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
}
