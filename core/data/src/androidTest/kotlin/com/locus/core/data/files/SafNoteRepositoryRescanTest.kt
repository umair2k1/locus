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
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TestDocumentFile
import androidx.room.Room
import com.locus.core.data.db.LocusDatabase
import com.locus.core.data.db.NoteDao
import com.locus.core.data.index.RoomIndexUpdateQueue
import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.FlushReceipt
import com.locus.core.domain.notes.FrontmatterParser
import com.locus.core.domain.notes.NoteFileWriter
import com.locus.core.domain.notes.NoteFlushCoordinator
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.ParsedNote
import com.locus.core.domain.notes.SnakeYamlCodec
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class SafNoteRepositoryRescanTest {
    private lateinit var db: LocusDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var parser: FrontmatterParser
    private lateinit var root: TestDocumentFile
    private lateinit var fileSource: FakeSafNoteFileSource
    private lateinit var fileWriter: FakeNoteFileWriter
    private lateinit var coordinator: NoteFlushCoordinator
    private lateinit var repository: SafNoteRepository
    private val treeUri = Uri.parse("content://com.locus.test/tree/notes")

    private class FakeSafNoteFileSource(
        val root: TestDocumentFile,
    ) : SafNoteFileSource {
        override fun getRootDocument(treeUri: Uri): DocumentFile = root

        override fun listMarkdownFiles(treeUri: Uri): List<DocumentFile> {
            val result = mutableListOf<DocumentFile>()

            fun collect(dir: DocumentFile) {
                for (child in dir.listFiles()) {
                    val name = child.name ?: continue
                    if (child.isDirectory && !name.startsWith(".")) {
                        collect(child)
                    } else if (child.isFile && name.endsWith(".md", ignoreCase = true)) {
                        result.add(child)
                    }
                }
            }
            collect(root)
            return result
        }

        override fun readText(doc: DocumentFile): String = (doc as? TestDocumentFile)?.content ?: ""

        override fun listFolders(treeUri: Uri): List<String> {
            val result = mutableListOf<String>()

            fun collect(
                dir: DocumentFile,
                path: String,
            ) {
                for (child in dir.listFiles()) {
                    val name = child.name ?: continue
                    if (child.isDirectory && !name.startsWith(".")) {
                        val childPath = if (path.isEmpty()) name else "$path/$name"
                        result.add(childPath)
                        collect(child, childPath)
                    }
                }
            }
            collect(root, "")
            return result.sorted()
        }

        fun resolveFolder(folderPath: String): TestDocumentFile {
            if (folderPath.isBlank()) return root
            val segments = folderPath.trim('/').split('/')
            var current = root
            for (segment in segments) {
                var child = current.children.firstOrNull { it.name == segment && it.isDirectory } as? TestDocumentFile
                if (child == null) {
                    child = TestDocumentFile(parent = current, docName = segment, isDir = true)
                    current.children.add(child)
                }
                current = child
            }
            return current
        }
    }

    private class FakeNoteFileWriter(
        private val fileSource: FakeSafNoteFileSource,
    ) : NoteFileWriter {
        override suspend fun atomicWrite(
            noteId: String,
            path: String,
            content: String,
        ): Result<FlushReceipt> {
            val normalizedPath = path.removePrefix("content://").removePrefix("file://")
            val fileName = normalizedPath.substringAfterLast('/')
            val folder = if (normalizedPath.contains('/')) normalizedPath.substringBeforeLast('/').trim('/') else ""
            val parent = fileSource.resolveFolder(folder)

            var doc = parent.children.firstOrNull { it.name == fileName && !it.isDirectory } as? TestDocumentFile
            if (doc == null) {
                doc =
                    fileSource.listMarkdownFiles(Uri.EMPTY).firstOrNull {
                        it.name == fileName || it.uri.toString() == path
                    } as? TestDocumentFile
            }
            if (doc == null) {
                doc = TestDocumentFile(parent = parent, docName = fileName, isDir = false)
                parent.children.add(doc)
            }
            doc.content = content
            return Result.success(
                FlushReceipt(
                    noteId = noteId,
                    checksum = Checksum.sha256(content),
                    flushedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db =
            Room
                .inMemoryDatabaseBuilder(context, LocusDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        noteDao = db.noteDao()
        parser = FrontmatterParser(SnakeYamlCodec())
        root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
        fileSource = FakeSafNoteFileSource(root)
        fileWriter = FakeNoteFileWriter(fileSource)

        val indexQueue = RoomIndexUpdateQueue(noteDao)
        val dispatchers =
            object : DispatcherProvider {
                override val io: CoroutineDispatcher = Dispatchers.IO
                override val default: CoroutineDispatcher = Dispatchers.Default
                override val main: CoroutineDispatcher = Dispatchers.Main
                override val mainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate
            }
        coordinator =
            NoteFlushCoordinator(
                fileWriter = fileWriter,
                indexQueue = indexQueue,
                dispatchers = dispatchers,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

        val treeUriStore =
            object : TreeUriStore {
                override val treeUriFlow = MutableStateFlow<Uri?>(treeUri)

                override suspend fun getTreeUri(): Uri = treeUri

                override suspend fun setTreeUri(uri: Uri) {}
            }

        repository =
            SafNoteRepository(
                fileSource = fileSource,
                parser = parser,
                treeUriStore = treeUriStore,
                coordinator = coordinator,
                noteDao = noteDao,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun acceptance_threeNotesWithSameTitle_andExternalEdit_detectedAsChanged() =
        runTest {
            // 1. Create three notes titled "Ideas" in the same folder
            val note1 = repository.createNote("", "Ideas", NoteType.NOTE)
            val note2 = repository.createNote("", "Ideas", NoteType.NOTE)
            val note3 = repository.createNote("", "Ideas", NoteType.NOTE)

            // Verify filenames in the folder
            val markdownFiles = fileSource.listMarkdownFiles(treeUri).map { it.name }
            assertEquals(listOf("Ideas.md", "Ideas (2).md", "Ideas (3).md"), markdownFiles)
            assertEquals("Ideas", note1.title)
            assertEquals("Ideas (2)", note2.title)
            assertEquals("Ideas (3)", note3.title)

            // Initial rescan reports 0 added, 0 changed, 0 removed (indexed upon creation)
            val initialReport = repository.rescan()
            assertEquals(0, initialReport.added)
            assertEquals(0, initialReport.changed)
            assertEquals(0, initialReport.removed)

            // 2. Externally modify Ideas (2).md's bytes
            val ideas2Doc =
                fileSource.listMarkdownFiles(treeUri).first { it.name == "Ideas (2).md" } as TestDocumentFile
            ideas2Doc.content = ideas2Doc.content + "\n## Added externally\nExternal edit from desktop!"

            // 3. Call rescan() and verify changed=1
            val rescanReport = repository.rescan()
            assertEquals(0, rescanReport.added)
            assertEquals(1, rescanReport.changed)
            assertEquals(0, rescanReport.removed)

            // Verify that the updated checksum is stored in NoteDao
            val updatedEntity = noteDao.getById(note2.id)
            assertNotNull(updatedEntity)
            assertEquals(Checksum.sha256(ideas2Doc.content), updatedEntity?.checksum)
        }

    @Test
    fun rescan_detectsExternallyAddedFile() =
        runTest {
            val externalParsed =
                ParsedNote(
                    id = "0191ebc2-841e-7b28-b072-46ebc605cf52",
                    title = "External Note",
                    type = NoteType.NOTE,
                    created = Instant.parse("2026-09-10T10:00:00Z"),
                    modified = Instant.parse("2026-09-10T10:00:00Z"),
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    history = 0,
                    checksum = Checksum.sha256("Hello external"),
                    app = "Desktop Obsidian",
                    unknownFields = emptyMap(),
                    body = "Hello external",
                    wasRepaired = false,
                    repairNotes = emptyList(),
                )
            val externalDoc =
                TestDocumentFile(
                    parent = root,
                    docName = "External Note.md",
                    isDir = false,
                    content = parser.render(externalParsed),
                )
            root.children.add(externalDoc)

            val report = repository.rescan()
            assertEquals(1, report.added)
            assertEquals(0, report.changed)
            assertEquals(0, report.removed)

            val entity = noteDao.getById("0191ebc2-841e-7b28-b072-46ebc605cf52")
            assertNotNull(entity)
            assertEquals("External Note", entity?.title)
        }

    @Test
    fun rescan_detectsExternallyRemovedFile() =
        runTest {
            val note = repository.createNote("", "To Be Removed", NoteType.NOTE)
            assertNotNull(noteDao.getById(note.id))

            // Physically remove the file from fake SAF tree
            root.children.removeIf { it.name == "To Be Removed.md" }

            val report = repository.rescan()
            assertEquals(0, report.added)
            assertEquals(0, report.changed)
            assertEquals(1, report.removed)

            assertNull(noteDao.getById(note.id))
        }

    @Test
    fun collisionSuffixing_inSubfolder_resolvesCorrectly() =
        runTest {
            val note1 = repository.createNote("Personal/Tasks", "Chores", NoteType.NOTE)
            val note2 = repository.createNote("Personal/Tasks", "Chores", NoteType.NOTE)
            val note3 = repository.createNote("Personal/Tasks", "Chores", NoteType.NOTE)

            val files =
                fileSource
                    .listMarkdownFiles(treeUri)
                    .filter { repository.computeFolderPath(it, root) == "Personal/Tasks" }
                    .map { it.name }
            assertEquals(listOf("Chores.md", "Chores (2).md", "Chores (3).md"), files)
            assertEquals("Chores", note1.title)
            assertEquals("Chores (2)", note2.title)
            assertEquals("Chores (3)", note3.title)
        }
}
