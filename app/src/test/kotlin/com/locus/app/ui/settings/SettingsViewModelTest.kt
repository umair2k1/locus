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

package com.locus.app.ui.settings

import android.content.Context
import app.cash.turbine.test
import com.locus.core.domain.backup.BackupInterval
import com.locus.core.domain.backup.BackupSettingsRepository
import com.locus.core.domain.backup.ImportExportRepository
import com.locus.core.domain.backup.LibraryImportOutcome
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.settings.AgentSettingsStore
import com.locus.core.domain.settings.NetworkSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var fakeRepo: FakeNoteRepository
    private lateinit var fakeBackupRepo: FakeBackupSettingsRepository
    private lateinit var fakeImportExportRepo: FakeImportExportRepository
    private lateinit var fakeAgentSettingsStore: FakeAgentSettingsStore
    private lateinit var fakeNetworkSettingsStore: FakeNetworkSettingsStore
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = RuntimeEnvironment.getApplication()
        fakeRepo = FakeNoteRepository()
        fakeBackupRepo = FakeBackupSettingsRepository()
        fakeImportExportRepo = FakeImportExportRepository()
        fakeAgentSettingsStore = FakeAgentSettingsStore()
        fakeNetworkSettingsStore = FakeNetworkSettingsStore()
        viewModel =
            SettingsViewModel(
                repo = fakeRepo,
                backupSettingsRepo = fakeBackupRepo,
                importExportRepo = fakeImportExportRepo,
                agentSettingsStore = fakeAgentSettingsStore,
                networkSettingsStore = fakeNetworkSettingsStore,
                context = context,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun includeApiKeys_defaultsToFalse() = runTest { assertFalse(viewModel.includeApiKeys.value) }

    @Test
    fun bulkCap_defaultsTo50() =
        runTest {
            viewModel.bulkCap.test { assertEquals(50, awaitItem()) }
        }

    @Test
    fun isCloudDisabled_defaultsToFalse() =
        runTest {
            viewModel.isCloudDisabled.test { assertFalse(awaitItem()) }
        }

    @Test
    fun setCloudDisabled_updatesStateFlow() =
        runTest {
            viewModel.isCloudDisabled.test {
                assertFalse(awaitItem())
                viewModel.setCloudDisabled(true)
                assertTrue(awaitItem())
                viewModel.setCloudDisabled(false)
                assertFalse(awaitItem())
            }
        }

    @Test
    fun setBulkCap_updatesState() =
        runTest {
            viewModel.bulkCap.test {
                assertEquals(50, awaitItem())
                viewModel.setBulkCap(75)
                assertEquals(75, awaitItem())
            }
        }

    @Test
    fun setIncludeApiKeys_togglesState() =
        runTest {
            viewModel.setIncludeApiKeys(true)
            assertTrue(viewModel.includeApiKeys.value)

            viewModel.setIncludeApiKeys(false)
            assertFalse(viewModel.includeApiKeys.value)
        }

    @Test
    fun exportLibrary_dispatchesToRepository() =
        runTest {
            viewModel.exportLibrary("content://dest/export.zip")
            testScheduler.advanceUntilIdle()

            assertTrue(fakeImportExportRepo.exportLibraryCalled)
            assertEquals("content://dest/export.zip", fakeImportExportRepo.lastExportDestination)
            assertNotNull(viewModel.statusMessage.value)
        }

    @Test
    fun importLibrary_dispatchesToRepository() =
        runTest {
            viewModel.importLibrary("content://source/backup.zip", "content://locus/tree")
            testScheduler.advanceUntilIdle()

            assertTrue(fakeImportExportRepo.importLibraryCalled)
            assertEquals("content://source/backup.zip", fakeImportExportRepo.lastImportZip)
            assertEquals("content://locus/tree", fakeImportExportRepo.lastImportDest)
            assertNotNull(viewModel.statusMessage.value)
        }

    @Test
    fun clearStatusMessage_resetsToNull() =
        runTest {
            viewModel.exportLibrary("content://dest/export.zip")
            testScheduler.advanceUntilIdle()
            assertNotNull(viewModel.statusMessage.value)

            viewModel.clearStatusMessage()
            assertEquals(null, viewModel.statusMessage.value)
        }

    private class FakeNoteRepository : NoteRepository {
        var rootUriFlow = MutableStateFlow<String?>(null)

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

        override fun observeAllNotes(): Flow<List<Note>> = emptyFlow()

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
        ): Note = error("Not needed")

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

        override fun observeRootUri(): Flow<String?> = rootUriFlow

        override suspend fun setRootUri(uriString: String) {
            rootUriFlow.value = uriString
        }
    }

    private class FakeBackupSettingsRepository : BackupSettingsRepository {
        var destinationUri: String? = null
        var interval: BackupInterval = BackupInterval.WEEKLY
        var lastBackup: Long? = null

        override fun observeBackupDestinationUri(): Flow<String?> = flowOf(destinationUri)

        override suspend fun getBackupDestinationUri(): String? = destinationUri

        override suspend fun setBackupDestinationUri(uriString: String) {
            destinationUri = uriString
        }

        override fun observeBackupInterval(): Flow<BackupInterval> = flowOf(interval)

        override suspend fun getBackupInterval(): BackupInterval = interval

        override suspend fun setBackupInterval(interval: BackupInterval) {
            this.interval = interval
        }

        override fun observeLastBackupTime(): Flow<Long?> = flowOf(lastBackup)

        override suspend fun setLastBackupTime(timestamp: Long) {
            lastBackup = timestamp
        }
    }

    private class FakeImportExportRepository : ImportExportRepository {
        var exportLibraryCalled = false
        var lastExportDestination: String? = null
        var importLibraryCalled = false
        var lastImportZip: String? = null
        var lastImportDest: String? = null

        override suspend fun exportLibrary(destinationUriString: String): Boolean {
            exportLibraryCalled = true
            lastExportDestination = destinationUriString
            return true
        }

        override suspend fun importLibrary(
            zipUriString: String,
            destinationTreeUriString: String,
        ): LibraryImportOutcome {
            importLibraryCalled = true
            lastImportZip = zipUriString
            lastImportDest = destinationTreeUriString
            return LibraryImportOutcome.Success(5)
        }

        override suspend fun exportSettings(includeApiKeys: Boolean): String = "{}"

        override suspend fun importSettings(json: String): Boolean = true
    }

    private class FakeAgentSettingsStore(
        initialCap: Int = 50,
    ) : AgentSettingsStore {
        private val _bulkCap = MutableStateFlow(initialCap)
        override val bulkCap: Flow<Int> = _bulkCap

        override suspend fun setBulkCap(value: Int) {
            _bulkCap.value = value
        }
    }

    private class FakeNetworkSettingsStore(
        initialCloudDisabled: Boolean = false,
    ) : NetworkSettingsStore {
        private val _isCloudDisabled = MutableStateFlow(initialCloudDisabled)
        override val isCloudDisabled: Flow<Boolean> = _isCloudDisabled

        override suspend fun setCloudDisabled(disabled: Boolean) {
            _isCloudDisabled.value = disabled
        }
    }
}
