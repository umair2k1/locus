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
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteFileWriter
import com.locus.core.domain.notes.NoteFlushCoordinator
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.notes.SnakeYamlCodec
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryImporterTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val testDispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
        }

    private val yamlCodec = SnakeYamlCodec()

    private val sourceTreeUri = Uri.parse("content://com.locus.test/source_notes")
    private val destTreeUri = Uri.parse("content://com.locus.test/dest_notes")

    private lateinit var sourceRootDoc: TestDocumentFile
    private lateinit var destRootDoc: TestDocumentFile
    private lateinit var multiFileSource: MultiFakeFileSource
    private lateinit var treeUriStore: FakeTreeUriStore
    private lateinit var fakeRepo: FakeNoteRepository
    private lateinit var backupManager: BackupManager
    private lateinit var libraryImporter: LibraryImporter

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        sourceRootDoc = TestDocumentFile(parent = null, docName = "SourceNotes", isDir = true)
        destRootDoc = TestDocumentFile(parent = null, docName = "DestNotes", isDir = true)

        multiFileSource =
            MultiFakeFileSource(
                mapOf(
                    sourceTreeUri to sourceRootDoc,
                    destTreeUri to destRootDoc,
                ),
            )

        treeUriStore = FakeTreeUriStore(sourceTreeUri)
        fakeRepo = FakeNoteRepository()

        val coordinator =
            NoteFlushCoordinator(
                fileWriter =
                    object : NoteFileWriter {
                        override suspend fun atomicWrite(
                            noteId: String,
                            path: String,
                            content: String,
                        ): Result<FlushReceipt> =
                            Result.success(
                                FlushReceipt(
                                    noteId,
                                    "chk-$noteId",
                                    System.currentTimeMillis(),
                                ),
                            )
                    },
                indexQueue =
                    object : IndexUpdateQueue {
                        override suspend fun enqueue(receipt: FlushReceipt) {
                            // no-op
                        }
                    },
                dispatchers = testDispatchers,
                scope = testScope,
            )

        backupManager =
            BackupManager(
                context = context,
                coordinator = coordinator,
                treeUriStore = treeUriStore,
                fileSource = multiFileSource,
                dispatchers = testDispatchers,
            )

        libraryImporter =
            LibraryImporter(
                context = context,
                repo = fakeRepo,
                treeUriStore = treeUriStore,
                fileSource = multiFileSource,
                yamlCodec = yamlCodec,
                dispatchers = testDispatchers,
            )
    }

    @Test
    fun import_roundTripsFiveNoteLibrary_byteForByteOnMarkdownBodies() =
        runTest(testDispatcher) {
            val expected = populateFiveNotes(sourceRootDoc)

            val zipFile = tempFolder.newFile("locus-library-export.zip")
            val backupResult = backupManager.runBackup(Uri.fromFile(zipFile))
            assertTrue("Backup export must succeed", backupResult is BackupResult.Success)

            val importResult = libraryImporter.import(Uri.fromFile(zipFile), destTreeUri)
            assertTrue("Import must succeed", importResult is ImportResult.Success)
            val success = importResult as ImportResult.Success
            assertEquals(5, success.fileCount)
            assertTrue("Repository rescan must be triggered", fakeRepo.rescanCalled)
            assertEquals(
                "Destination root URI must be set",
                destTreeUri.toString(),
                fakeRepo.currentRootUri,
            )

            verifyImportedNotes(destRootDoc, expected)
        }

    private fun populateFiveNotes(root: TestDocumentFile): List<String> {
        val rootNotes = populateRootNotes(root)
        val subNotes = populateSubfolderNotes(root)
        return rootNotes + subNotes
    }

    private fun populateRootNotes(root: TestDocumentFile): List<String> {
        val note1Content =
            """
            ---
            id: 01920000-0000-7000-0000-000000000001
            title: Root Note
            type: note
            created: 2026-09-18T10:00:00Z
            modified: 2026-09-18T10:00:00Z
            pinned: false
            tags: []
            ---
            # Root Note
            Body 1 with chars: 🚀 & < >.
            """.trimIndent()
        val note1Doc = root.createFile("text/markdown", "Root Note.md") as TestDocumentFile
        note1Doc.content = note1Content

        val note2Content =
            """
            ---
            id: 01920000-0000-7000-0000-000000000002
            title: Grocery List
            type: checklist
            created: 2026-09-18T11:00:00Z
            modified: 2026-09-18T11:00:00Z
            pinned: true
            color: '#FFF8E1'
            tags: [shopping]
            ---
            - [ ] Milk
            - [x] Bread
            """.trimIndent()
        val note2Doc = root.createFile("text/markdown", "Grocery List.md") as TestDocumentFile
        note2Doc.content = note2Content

        return listOf(note1Content, note2Content)
    }

    private fun populateSubfolderNotes(root: TestDocumentFile): List<String> {
        val workDir = root.createDirectory("Work") as TestDocumentFile
        val note3Content =
            """
            ---
            id: 01920000-0000-7000-0000-000000000003
            title: Sprint Plan
            type: note
            created: 2026-09-18T12:00:00Z
            modified: 2026-09-18T12:00:00Z
            tags: [work, urgent]
            ---
            ## Sprint Plan
            Deliver full-library import/export byte-for-byte.
            """.trimIndent()
        val note3Doc = workDir.createFile("text/markdown", "Sprint Plan.md") as TestDocumentFile
        note3Doc.content = note3Content

        val personalDir = root.createDirectory("Personal") as TestDocumentFile
        val note4Content =
            """
            ---
            id: 01920000-0000-7000-0000-000000000004
            title: Ideas
            type: note
            created: 2026-09-18T13:00:00Z
            modified: 2026-09-18T13:00:00Z
            color: '#E1F5FE'
            ---
            - Idea 1: Local RAG
            - Idea 2: Offline wiki
            """.trimIndent()
        val note4Doc = personalDir.createFile("text/markdown", "Ideas.md") as TestDocumentFile
        note4Doc.content = note4Content

        val note5Content =
            """
            ---
            id: 01920000-0000-7000-0000-000000000005
            title: Journal
            type: note
            created: 2026-09-18T14:00:00Z
            modified: 2026-09-18T14:00:00Z
            pinned: false
            ---
            Reflections at the end of the day.
            """.trimIndent()
        val note5Doc = personalDir.createFile("text/markdown", "Journal.md") as TestDocumentFile
        note5Doc.content = note5Content

        return listOf(note3Content, note4Content, note5Content)
    }

    private fun verifyImportedNotes(
        dest: TestDocumentFile,
        expected: List<String>,
    ) {
        val note1 = dest.children.firstOrNull { it.name == "Root Note.md" } as? TestDocumentFile
        assertDocContentMatches(expected[0], note1)

        val note2 = dest.children.firstOrNull { it.name == "Grocery List.md" } as? TestDocumentFile
        assertDocContentMatches(expected[1], note2)

        val workDir =
            dest.children.firstOrNull { it.name == "Work" && it.isDirectory } as?
                TestDocumentFile
        assertNotNull("Imported Work directory must exist", workDir)
        val note3 =
            workDir!!.children.firstOrNull { it.name == "Sprint Plan.md" } as? TestDocumentFile
        assertDocContentMatches(expected[2], note3)

        val personalDir =
            dest.children.firstOrNull { it.name == "Personal" && it.isDirectory } as?
                TestDocumentFile
        assertNotNull("Imported Personal directory must exist", personalDir)
        val note4 =
            personalDir!!.children.firstOrNull { it.name == "Ideas.md" } as? TestDocumentFile
        assertDocContentMatches(expected[3], note4)

        val note5 =
            personalDir.children.firstOrNull { it.name == "Journal.md" } as? TestDocumentFile
        assertDocContentMatches(expected[4], note5)
    }

    private fun assertDocContentMatches(
        expected: String,
        doc: TestDocumentFile?,
    ) {
        assertNotNull(doc)
        assertEquals(expected, doc!!.content)
        val bytes = doc.contentBytes ?: doc.content.toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun import_withZipLackingValidFrontmatterNotes_returnsInvalidZip() =
        runTest(testDispatcher) {
            val nonLocusZip = tempFolder.newFile("plain-archive.zip")
            ZipOutputStream(FileOutputStream(nonLocusZip)).use { zipOut ->
                zipOut.putNextEntry(ZipEntry("README.md"))
                zipOut.write("# Just a readme\nNo YAML here.".toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                zipOut.putNextEntry(ZipEntry("notes.txt"))
                zipOut.write("plain text".toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
            }

            val result = libraryImporter.import(Uri.fromFile(nonLocusZip), destTreeUri)
            assertTrue(
                "Should reject zip lacking frontmatter notes",
                result is ImportResult.InvalidZip,
            )
            val invalid = result as ImportResult.InvalidZip
            assertTrue(invalid.message.contains("frontmatter", ignoreCase = true))
        }

    @Test
    fun import_withCorruptedZip_returnsInvalidZip() =
        runTest(testDispatcher) {
            val corruptZip = tempFolder.newFile("corrupt.zip")
            FileOutputStream(corruptZip).use {
                it.write("NOT A ZIP ARCHIVE AT ALL".toByteArray(Charsets.UTF_8))
            }

            val result = libraryImporter.import(Uri.fromFile(corruptZip), destTreeUri)
            assertTrue("Should reject corrupt zip archive", result is ImportResult.InvalidZip)
        }

    private class MultiFakeFileSource(
        private val roots: Map<Uri, TestDocumentFile>,
    ) : SafNoteFileSource {
        override fun getRootDocument(treeUri: Uri): DocumentFile? = roots[treeUri]

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

    private class FakeNoteRepository : NoteRepository {
        var rescanCalled = false
        var currentRootUri: String? = null

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

        override suspend fun rescan(): RescanReport {
            rescanCalled = true
            return RescanReport(added = 5, changed = 0, removed = 0)
        }

        override fun observeRootUri(): Flow<String?> = flowOf(currentRootUri)

        override suspend fun setRootUri(uriString: String) {
            currentRootUri = uriString
        }
    }
}
