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

package com.locus.core.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TestDocumentFile
import com.locus.core.data.files.SafNoteFileSource
import com.locus.core.data.files.TreeUriStore
import com.locus.core.domain.notes.FlushReceipt
import com.locus.core.domain.notes.IndexUpdateQueue
import com.locus.core.domain.notes.NoteFileWriter
import com.locus.core.domain.notes.NoteFlushCoordinator
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val testDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
        }

    private lateinit var rootDoc: TestDocumentFile
    private lateinit var fakeFileSource: FakeFileSource
    private lateinit var fakeTreeUriStore: FakeTreeUriStore
    private lateinit var fileWriter: FakeNoteFileWriter
    private lateinit var coordinator: NoteFlushCoordinator
    private lateinit var backupManager: BackupManager

    private val treeUri = Uri.parse("content://com.locus.test/tree/notes")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rootDoc = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
        fakeFileSource = FakeFileSource(rootDoc)
        fakeTreeUriStore = FakeTreeUriStore(treeUri)

        fileWriter = FakeNoteFileWriter(rootDoc)
        coordinator =
            NoteFlushCoordinator(
                fileWriter = fileWriter,
                indexQueue =
                    object : IndexUpdateQueue {
                        override suspend fun enqueue(receipt: FlushReceipt) {
                            // no-op for tests
                        }
                    },
                dispatchers = testDispatchers,
                scope = testScope,
            )

        backupManager =
            BackupManager(
                context = context,
                coordinator = coordinator,
                treeUriStore = fakeTreeUriStore,
                fileSource = fakeFileSource,
                dispatchers = testDispatchers,
            )
    }

    @Test
    fun runBackup_flushesDirtyEditsBeforeZipping() =
        runTest(testDispatcher) {
            // Setup an initial file on disk
            val noteDoc =
                rootDoc.createFile("text/markdown", "QuickNote.md") as TestDocumentFile
            noteDoc.content = "Initial content"

            // Simulate in-memory edit that has NOT debounced yet
            coordinator.onEdit(
                noteId = "note-001",
                path = noteDoc.uri.toString(),
                content = "Freshly typed content right before backup!",
            )

            // Destination directory
            val destDir = tempFolder.newFolder("backups")
            val destUri = Uri.fromFile(destDir)

            val result = backupManager.runBackup(destUri)

            assertTrue("Expected success but got $result", result is BackupResult.Success)
            val success = result as BackupResult.Success
            assertEquals(1, success.fileCount)

            // Inspect the zip file created in destDir
            val zipFiles = destDir.listFiles { _, name -> name.endsWith(".zip") }.orEmpty()
            assertEquals(1, zipFiles.size)
            val zipFile = zipFiles.first()

            val entries = readZipEntries(zipFile)
            assertTrue("Expected QuickNote.md in zip", entries.containsKey("QuickNote.md"))
            assertEquals("Freshly typed content right before backup!", entries["QuickNote.md"])
        }

    @Test
    fun runBackup_zipsEntireTree_includingTrashAndHistory() =
        runTest(testDispatcher) {
            // 1. Root note
            val rootNote = rootDoc.createFile("text/markdown", "Root.md") as TestDocumentFile
            rootNote.content = "# Root Note"

            // 2. Subfolder with note
            val subfolder = rootDoc.createDirectory("Personal") as TestDocumentFile
            val subNote = subfolder.createFile("text/markdown", "Tasks.md") as TestDocumentFile
            subNote.content = "- [ ] Task 1"

            // 3. .locus/trash with deleted note
            val locusDir = rootDoc.createDirectory(".locus") as TestDocumentFile
            val trashDir = locusDir.createDirectory("trash") as TestDocumentFile
            val trashNote =
                trashDir.createFile("text/markdown", "Deleted.md") as TestDocumentFile
            trashNote.content = "# Deleted Note in Trash"

            // 4. .locus/history with note revision
            val historyDir = locusDir.createDirectory("history") as TestDocumentFile
            val noteHistoryDir = historyDir.createDirectory("note-001") as TestDocumentFile
            val revFile =
                noteHistoryDir.createFile("text/markdown", "1726000000000.md") as
                    TestDocumentFile
            revFile.content = "Historical snapshot v1"

            // Destination zip file directly
            val destZip = File(tempFolder.root, "my-backup.zip")
            val destUri = Uri.fromFile(destZip)

            val result = backupManager.runBackup(destUri)

            assertTrue(result is BackupResult.Success)
            val success = result as BackupResult.Success
            assertEquals(4, success.fileCount)

            val entries = readZipEntries(destZip)
            assertEquals("# Root Note", entries["Root.md"])
            assertEquals("- [ ] Task 1", entries["Personal/Tasks.md"])
            assertEquals("# Deleted Note in Trash", entries[".locus/trash/Deleted.md"])
            assertEquals(
                "Historical snapshot v1",
                entries[".locus/history/note-001/1726000000000.md"],
            )
        }

    @Test
    fun runBackup_whenTreeUriNotConfigured_returnsFailure() =
        runTest(testDispatcher) {
            fakeTreeUriStore.uri = null

            val destZip = File(tempFolder.root, "backup.zip")
            val result = backupManager.runBackup(Uri.fromFile(destZip))

            assertTrue(result is BackupResult.Failure)
            val failure = result as BackupResult.Failure
            assertTrue(failure.cause is IllegalStateException)
        }

    private fun readZipEntries(file: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        ZipInputStream(FileInputStream(file)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val bytes = zipIn.readBytes()
                    map[entry.name] = String(bytes, Charsets.UTF_8)
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        return map
    }

    private class FakeTreeUriStore(
        var uri: Uri?,
    ) : TreeUriStore {
        override val treeUriFlow = MutableStateFlow(uri)

        override suspend fun getTreeUri(): Uri? = uri

        override suspend fun setTreeUri(uri: Uri) {
            this.uri = uri
            treeUriFlow.value = uri
        }
    }

    private class FakeFileSource(
        val root: TestDocumentFile,
    ) : SafNoteFileSource {
        override fun getRootDocument(treeUri: Uri): DocumentFile = root

        override fun listMarkdownFiles(treeUri: Uri): List<DocumentFile> = emptyList()

        override fun readText(doc: DocumentFile): String = (doc as? TestDocumentFile)?.content ?: ""

        override fun writeText(
            doc: DocumentFile,
            text: String,
        ) {
            (doc as? TestDocumentFile)?.content = text
        }

        override fun listFolders(treeUri: Uri): List<String> = emptyList()
    }

    private class FakeNoteFileWriter(
        val root: TestDocumentFile,
    ) : NoteFileWriter {
        override suspend fun atomicWrite(
            noteId: String,
            path: String,
            content: String,
        ): Result<FlushReceipt> {
            val doc =
                findFile(root, path)
                    ?: (root.createFile("text/markdown", "$noteId.md") as TestDocumentFile)
            doc.content = content
            return Result.success(FlushReceipt(noteId, "chk-$noteId", System.currentTimeMillis()))
        }

        private fun findFile(
            dir: TestDocumentFile,
            nameOrUri: String,
        ): TestDocumentFile? {
            val match =
                dir.children.filterIsInstance<TestDocumentFile>().firstOrNull {
                    it.name == nameOrUri || it.uri.toString() == nameOrUri
                }
            if (match != null) return match

            return dir.children
                .filterIsInstance<TestDocumentFile>()
                .filter { it.isDirectory }
                .firstNotNullOfOrNull { findFile(it, nameOrUri) }
        }
    }
}
