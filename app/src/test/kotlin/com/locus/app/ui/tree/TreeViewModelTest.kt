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

package com.locus.app.ui.tree

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
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TreeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeTreeNoteRepository
    private lateinit var viewModel: TreeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeTreeNoteRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun buildFolderTree_emptyPaths_returnsRootNodeWithNoChildren() {
        val root = buildFolderTree(emptyList())
        assertEquals("Notes", root.name)
        assertEquals("", root.path)
        assertTrue(root.children.isEmpty())
    }

    @Test
    fun buildFolderTree_flatPaths_groupsIntoHierarchy() {
        val paths =
            listOf(
                "work",
                "work/locus",
                "work/locus/docs",
                "personal",
                "personal/finance",
            )
        val root = buildFolderTree(paths)

        assertEquals(2, root.children.size)
        val personal = root.children[0]
        val work = root.children[1]

        assertEquals("personal", personal.name)
        assertEquals("personal", personal.path)
        assertEquals(1, personal.children.size)
        assertEquals("finance", personal.children[0].name)
        assertEquals("personal/finance", personal.children[0].path)

        assertEquals("work", work.name)
        assertEquals("work", work.path)
        assertEquals(1, work.children.size)
        assertEquals("locus", work.children[0].name)
        assertEquals("work/locus", work.children[0].path)
        assertEquals(1, work.children[0].children.size)
        assertEquals("docs", work.children[0].children[0].name)
        assertEquals("work/locus/docs", work.children[0].children[0].path)
    }

    @Test
    fun buildFolderTree_missingIntermediatePaths_stillBuildsFullHierarchy() {
        val paths = listOf("alpha/beta/gamma")
        val root = buildFolderTree(paths)

        assertEquals(1, root.children.size)
        val alpha = root.children[0]
        assertEquals("alpha", alpha.name)
        assertEquals("alpha", alpha.path)
        assertEquals(1, alpha.children.size)
        val beta = alpha.children[0]
        assertEquals("beta", beta.name)
        assertEquals("alpha/beta", beta.path)
        assertEquals(1, beta.children.size)
        val gamma = beta.children[0]
        assertEquals("gamma", gamma.name)
        assertEquals("alpha/beta/gamma", gamma.path)
    }

    @Test
    fun buildFolderTree_normalizesSlashesAndSortsAlphabetically() {
        val paths = listOf("/Zebra/", "  alpha  ", "alpha/sub")
        val root = buildFolderTree(paths)

        assertEquals(2, root.children.size)
        assertEquals("alpha", root.children[0].name)
        assertEquals("Zebra", root.children[1].name)
    }

    @Test
    fun uiState_loadsFoldersFromRepository() =
        runTest {
            fakeRepo.folders = listOf("Work", "Personal")
            viewModel = TreeViewModel(fakeRepo)

            viewModel.uiState.test {
                val initial = awaitItem()
                assertTrue(initial.loading)

                testDispatcher.scheduler.advanceUntilIdle()
                val loaded = awaitItem()
                assertFalse(loaded.loading)
                assertEquals(2, loaded.tree.children.size)
                assertEquals("Personal", loaded.tree.children[0].name)
                assertEquals("Work", loaded.tree.children[1].name)
            }
        }

    @Test
    fun selectFolder_updatesSelectedPathAndEmitsNotes() =
        runTest {
            fakeRepo.folders = listOf("Work")
            viewModel = TreeViewModel(fakeRepo)
            advanceUntilIdle()

            val workNote = createSampleNote("Work Note", "Work")
            fakeRepo.emitNotesForFolder("Work", listOf(workNote))

            viewModel.uiState.test {
                awaitItem() // initial idle state
                viewModel.selectFolder("Work")
                testDispatcher.scheduler.advanceUntilIdle()
                val selectedState = awaitItem()
                assertEquals("Work", selectedState.selectedPath)
                assertEquals(1, selectedState.notesInSelected.size)
                assertEquals("Work Note", selectedState.notesInSelected[0].title)
            }
        }

    @Test
    fun toggleFolderExpanded_addsAndRemovesPath() =
        runTest {
            viewModel = TreeViewModel(fakeRepo)
            advanceUntilIdle()

            viewModel.uiState.test {
                val initial = awaitItem()
                assertFalse(initial.expandedPaths.contains("Work"))

                viewModel.toggleFolderExpanded("Work")
                val expanded = awaitItem()
                assertTrue(expanded.expandedPaths.contains("Work"))

                viewModel.toggleFolderExpanded("Work")
                val collapsed = awaitItem()
                assertFalse(collapsed.expandedPaths.contains("Work"))
            }
        }

    @Test
    fun createFolder_delegatesToRepoAndExpandsParent() =
        runTest {
            fakeRepo.folders = listOf("Work")
            viewModel = TreeViewModel(fakeRepo)
            advanceUntilIdle()

            viewModel.uiState.test {
                awaitItem() // initial state
                viewModel.createFolder("Work", "Projects")
                testDispatcher.scheduler.advanceUntilIdle()
                val updatedState = awaitItem()
                assertEquals("Work", fakeRepo.lastCreatedParent)
                assertEquals("Projects", fakeRepo.lastCreatedName)
                assertTrue(updatedState.expandedPaths.contains("Work"))
                assertEquals(1, updatedState.tree.children.size)
                val workNode = updatedState.tree.children[0]
                assertEquals(1, workNode.children.size)
                assertEquals("Projects", workNode.children[0].name)
            }
        }

    private fun createSampleNote(
        title: String,
        folderPath: String,
    ): Note =
        Note(
            id = "0191ebc2-841e-7b28-b072-46ebc605cf52",
            title = title,
            type = NoteType.NOTE,
            folderPath = folderPath,
            pinned = false,
            color = null,
            tags = emptyList(),
            created = Instant.parse("2026-09-16T10:00:00Z"),
            modified = Instant.parse("2026-09-16T10:00:00Z"),
            checksum = "dummychecksum",
        )

    private class FakeTreeNoteRepository : NoteRepository {
        var folders: List<String> = emptyList()
        var lastCreatedParent: String? = null
        var lastCreatedName: String? = null
        private val allNotesFlow = MutableStateFlow<List<Note>>(emptyList())
        private val folderNotesFlow = MutableStateFlow<Map<String, List<Note>>>(emptyMap())

        fun emitNotesForFolder(
            folder: String,
            notes: List<Note>,
        ) {
            folderNotesFlow.value = folderNotesFlow.value + (folder to notes)
        }

        override fun observeAllNotes(): Flow<List<Note>> = allNotesFlow

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> {
            val flow = MutableStateFlow<List<Note>>(folderNotesFlow.value[folderPath] ?: emptyList())
            return flow
        }

        override suspend fun listFolders(): List<String> = folders

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) {
            lastCreatedParent = parentPath
            lastCreatedName = name
            val newPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
            folders = (folders + newPath).distinct().sorted()
        }

        override suspend fun readBody(noteId: String): String = ""

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

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }
}
