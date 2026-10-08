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

package com.locus.core.data.files

import android.content.Context
import android.net.Uri
import com.locus.core.data.history.NoteHistoryStore
import com.locus.core.domain.notes.Checksum
import kotlinx.coroutines.flow.MutableStateFlow
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

@RunWith(RobolectricTestRunner::class)
class SafNoteFileWriterTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var fileSource: AndroidSafNoteFileSource
    private lateinit var treeUriStore: TreeUriStore
    private lateinit var historyStore: NoteHistoryStore
    private lateinit var writer: SafNoteFileWriter

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        fileSource = AndroidSafNoteFileSource(context)
        treeUriStore =
            object : TreeUriStore {
                private var uri: Uri? = null
                override val treeUriFlow = MutableStateFlow<Uri?>(null)

                override suspend fun getTreeUri(): Uri? = uri

                override suspend fun setTreeUri(uri: Uri) {
                    this.uri = uri
                    treeUriFlow.value = uri
                }
            }
        historyStore = NoteHistoryStore(context, fileSource, treeUriStore)
        writer = SafNoteFileWriter(context, fileSource, treeUriStore, historyStore)
    }

    @Test
    fun atomicWrite_toDirectFile_writesContentAndReturnsChecksum() =
        runTest {
            val targetFile = File(tempFolder.root, "test-note.md")
            val content = "# Test Note\n\nThis is content."
            val expectedChecksum = Checksum.sha256(content)

            val result = writer.atomicWrite("note-1", targetFile.absolutePath, content)

            assertTrue(result.isSuccess)
            val receipt = result.getOrNull()!!
            assertEquals("note-1", receipt.noteId)
            assertEquals(expectedChecksum, receipt.checksum)
            assertEquals(content, targetFile.readText(Charsets.UTF_8))

            // Ensure no leftover .tmp files
            val tmpFiles = tempFolder.root.listFiles { _, name -> name.endsWith(".tmp") }.orEmpty()
            assertTrue(tmpFiles.isEmpty())
        }

    @Test
    fun atomicWrite_overwritesExistingFile_atomically() =
        runTest {
            val targetFile = File(tempFolder.root, "overwrite-note.md")
            targetFile.writeText("initial content", Charsets.UTF_8)

            val newContent = "updated content with new checksum"
            val expectedChecksum = Checksum.sha256(newContent)

            val result = writer.atomicWrite("note-overwrite", targetFile.absolutePath, newContent)

            assertTrue(result.isSuccess)
            val receipt = result.getOrNull()!!
            assertEquals("note-overwrite", receipt.noteId)
            assertEquals(expectedChecksum, receipt.checksum)
            assertEquals(newContent, targetFile.readText(Charsets.UTF_8))
        }

    @Test
    fun atomicWrite_overwritesExistingFile_snapshotsPreFlushContentFirst() =
        runTest {
            val targetFile = File(tempFolder.root, "history-note.md")
            targetFile.writeText("version 1 body", Charsets.UTF_8)

            val result = writer.atomicWrite("note-hist", targetFile.absolutePath, "version 2 body")
            assertTrue(result.isSuccess)

            // The pre-flush content (version 1 body) must have been snapshotted
            val revisions = historyStore.listRevisions("note-hist")
            assertEquals(1, revisions.size)
            assertEquals("version 1 body", revisions[0].body)
        }

    @Test
    fun atomicWrite_whenHistorySnapshotFails_abortsWriteAndLeavesOriginalFileIntact() =
        runTest {
            val targetFile = File(tempFolder.root, "failing-snapshot-note.md")
            val originalContent = "critical original content"
            targetFile.writeText(originalContent, Charsets.UTF_8)

            val failingHistoryStore =
                object : NoteHistoryStore(context, fileSource, treeUriStore) {
                    override suspend fun snapshot(
                        noteId: String,
                        previousBody: String,
                        rootHint: File?,
                    ): Unit = throw java.io.IOException("Disk full / snapshot failed")
                }
            val failingWriter =
                SafNoteFileWriter(context, fileSource, treeUriStore, failingHistoryStore)

            val result =
                failingWriter.atomicWrite(
                    "note-fail",
                    targetFile.absolutePath,
                    "malicious/corrupted body",
                )
            assertTrue(result.isFailure)

            // Original file content must be completely untouched
            assertEquals(originalContent, targetFile.readText(Charsets.UTF_8))
        }

    @Test
    fun atomicWrite_creation_doesNotSnapshotHistory() =
        runTest {
            val targetFile = File(tempFolder.root, "new-created-note.md")
            val result = writer.atomicWrite("note-new", targetFile.absolutePath, "initial body")
            assertTrue(result.isSuccess)

            val revisions = historyStore.listRevisions("note-new")
            assertTrue(revisions.isEmpty())
        }
}
