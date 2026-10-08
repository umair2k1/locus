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

package com.locus.core.data.history

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TestDocumentFile
import com.locus.core.data.files.SafNoteFileSource
import com.locus.core.data.files.TreeUriStore
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
class NoteHistoryStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var rootDoc: TestDocumentFile
    private lateinit var fileSource: TestSafFileSource
    private lateinit var treeUriStore: TestTreeUriStore
    private lateinit var historyStore: NoteHistoryStore
    private val testTreeUri = Uri.parse("content://com.locus.test/tree/notes")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rootDoc = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
        fileSource = TestSafFileSource(rootDoc)
        treeUriStore = TestTreeUriStore(testTreeUri)
        historyStore = NoteHistoryStore(context, fileSource, treeUriStore)
    }

    @Test
    fun snapshot_createsFileInLocusHistory_saf() =
        runTest {
            val noteId = "note-saf-1"
            val content = "# Heading\nThis is content."

            historyStore.snapshot(noteId, content)

            val revisions = historyStore.listRevisions(noteId)
            assertEquals(1, revisions.size)
            assertEquals(content, revisions[0].body)
            assertTrue(revisions[0].timestamp > 0)
        }

    @Test
    fun snapshot_stripsFrontmatterFromBody() =
        runTest {
            val noteId = "note-frontmatter-1"
            val rawFile =
                """
                ---
                id: note-frontmatter-1
                title: Frontmatter Note
                type: note
                ---
                # Pure Body
                Here is the actual note content.
                """.trimIndent()

            historyStore.snapshot(noteId, rawFile)

            val revisions = historyStore.listRevisions(noteId)
            assertEquals(1, revisions.size)
            val expectedBody = "# Pure Body\nHere is the actual note content."
            assertEquals(expectedBody, revisions[0].body)
            assertEquals("Frontmatter Note", revisions[0].title)
        }

    @Test
    fun snapshot_extractsTitleFromHeadingFallback() =
        runTest {
            val noteId = "note-heading-title"
            val rawFile = "# Note Heading Title\nJust body without frontmatter"
            historyStore.snapshot(noteId, rawFile)

            val revisions = historyStore.listRevisions(noteId)
            assertEquals(1, revisions.size)
            assertEquals("Note Heading Title", revisions[0].title)
            assertEquals(rawFile, revisions[0].body)
        }

    @Test
    fun snapshot_twentyOneSaves_leavesExactlyTwentyFilesFIFO_saf() =
        runTest {
            val noteId = "note-fifo-saf"

            for (i in 1..21) {
                historyStore.snapshot(noteId, "Revision $i body content")
            }

            val revisions = historyStore.listRevisions(noteId)
            assertEquals(20, revisions.size)

            // Newest first in listRevisions: revision 21 down to 2
            assertEquals("Revision 21 body content", revisions.first().body)
            assertEquals("Revision 2 body content", revisions.last().body)
        }

    @Test
    fun snapshot_twentyOneSaves_leavesExactlyTwentyFilesFIFO_directFile() =
        runTest {
            // Null treeUri -> falls back to direct file with rootHint
            val localTreeUriStore = TestTreeUriStore(null)
            val localStore = NoteHistoryStore(context, fileSource, localTreeUriStore)
            val rootDir = tempFolder.root
            val noteId = "note-fifo-file"

            for (i in 1..21) {
                localStore.snapshot(noteId, "File revision $i body", rootHint = rootDir)
            }

            val historyDir = File(rootDir, ".locus/history/$noteId")
            val filesOnDisk = historyDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
            assertEquals(20, filesOnDisk.size)

            val revisions = localStore.listRevisions(noteId)
            assertEquals(20, revisions.size)
            assertEquals("File revision 21 body", revisions.first().body)
            assertEquals("File revision 2 body", revisions.last().body)
        }

    @Test
    fun listRevisions_returnsSortedDescending() =
        runTest {
            val noteId = "note-sort-test"
            historyStore.snapshot(noteId, "First")
            historyStore.snapshot(noteId, "Second")
            historyStore.snapshot(noteId, "Third")

            val revisions = historyStore.listRevisions(noteId)
            assertEquals(3, revisions.size)
            assertEquals("Third", revisions[0].body)
            assertEquals("Second", revisions[1].body)
            assertEquals("First", revisions[2].body)
            assertTrue(revisions[0].timestamp >= revisions[1].timestamp)
            assertTrue(revisions[1].timestamp >= revisions[2].timestamp)
        }

    @Test
    fun listRevisions_nonExistentNote_returnsEmptyList() =
        runTest {
            val revisions = historyStore.listRevisions("non-existent-id")
            assertTrue(revisions.isEmpty())
        }

    private class TestTreeUriStore(
        initialUri: Uri?,
    ) : TreeUriStore {
        private var uri: Uri? = initialUri
        override val treeUriFlow = MutableStateFlow(initialUri)

        override suspend fun getTreeUri(): Uri? = uri

        override suspend fun setTreeUri(uri: Uri) {
            this.uri = uri
            treeUriFlow.value = uri
        }
    }

    private class TestSafFileSource(
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
}
