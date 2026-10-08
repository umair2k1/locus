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

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TestDocumentFile
import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.FlushReceipt
import com.locus.core.domain.notes.FrontmatterParser
import com.locus.core.domain.notes.IndexUpdateQueue
import com.locus.core.domain.notes.NoteFileWriter
import com.locus.core.domain.notes.NoteFlushCoordinator
import com.locus.core.domain.notes.SnakeYamlCodec
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Suppress("LargeClass")
class SafNoteRepositoryTest {
    private lateinit var parser: FrontmatterParser
    private val treeUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANotes")

    @Before
    fun setUp() {
        parser = FrontmatterParser(SnakeYamlCodec())
    }

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

        override fun writeText(
            doc: DocumentFile,
            text: String,
        ) {
            (doc as? TestDocumentFile)?.content = text
        }

        override fun moveDocument(
            source: DocumentFile,
            targetDir: DocumentFile,
        ): DocumentFile {
            if (source is TestDocumentFile && targetDir is TestDocumentFile) {
                source.moveTo(targetDir)
                return source
            }
            return source
        }

        override fun renameDocument(
            doc: DocumentFile,
            newName: String,
        ): DocumentFile {
            doc.renameTo(newName)
            return doc
        }

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
    }

    @Test
    fun externalMove_updatesFolderPathOnNextReadWithoutFrontmatterChange() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val personalFolder = TestDocumentFile(parent = root, docName = "Personal", isDir = true)
            root.children.add(personalFolder)

            val workFolder = TestDocumentFile(parent = root, docName = "Work", isDir = true)
            root.children.add(workFolder)

            val rawContentWithoutFolder =
                """
                ---
                id: 0191ebc2-841e-7b28-b072-46ebc605cf52
                title: My Task Plan
                type: note
                created: 2026-09-16T10:00:00Z
                modified: 2026-09-16T10:00:00Z
                pinned: false
                ---
                Finish the presentation slides.
                """.trimIndent()

            val noteFile =
                TestDocumentFile(
                    parent = personalFolder,
                    docName = "My Task Plan.md",
                    isDir = false,
                    content = rawContentWithoutFolder,
                )
            personalFolder.children.add(noteFile)

            val fileSource = FakeSafNoteFileSource(root)
            val repository =
                SafNoteRepository(
                    fileSource = fileSource,
                    parser = parser,
                    initialTreeUri = treeUri,
                )

            // Read 1: Note is in Personal
            val firstRead = repository.observeAllNotes().first()
            assertEquals(1, firstRead.size)
            assertEquals("0191ebc2-841e-7b28-b072-46ebc605cf52", firstRead[0].id)
            assertEquals("Personal", firstRead[0].folderPath)

            // Simulate external move outside the app: moved to Work folder
            // The file's raw content (and frontmatter) is 100% UNCHANGED!
            noteFile.moveTo(workFolder)

            // Read 2: On next read, folderPath is recomputed from the new parent chain
            repository.refresh()
            val secondRead = repository.observeAllNotes().first()
            assertEquals(1, secondRead.size)
            assertEquals("0191ebc2-841e-7b28-b072-46ebc605cf52", secondRead[0].id)
            assertEquals("Work", secondRead[0].folderPath)
            assertEquals(rawContentWithoutFolder, noteFile.content)
        }

    @Test
    fun observeNotesInFolder_matchesRootAndSubfolders() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val ideasFolder = TestDocumentFile(parent = root, docName = "Ideas", isDir = true)
            root.children.add(ideasFolder)

            val rootNote =
                TestDocumentFile(
                    parent = root,
                    docName = "Root Note.md",
                    isDir = false,
                    content =
                        """
                        ---
                        id: 0191ebc2-0000-0000-0000-000000000001
                        title: Root Note
                        type: note
                        ---
                        Root content
                        """.trimIndent(),
                )
            root.children.add(rootNote)

            val subNote =
                TestDocumentFile(
                    parent = ideasFolder,
                    docName = "Idea Note.md",
                    isDir = false,
                    content =
                        """
                        ---
                        id: 0191ebc2-0000-0000-0000-000000000002
                        title: Idea Note
                        type: note
                        ---
                        Idea content
                        """.trimIndent(),
                )
            ideasFolder.children.add(subNote)

            val fileSource = FakeSafNoteFileSource(root)
            val repository =
                SafNoteRepository(
                    fileSource = fileSource,
                    parser = parser,
                    initialTreeUri = treeUri,
                )

            // Query root folder with ""
            val rootNotesEmpty = repository.observeNotesInFolder("").first()
            assertEquals(1, rootNotesEmpty.size)
            assertEquals("0191ebc2-0000-0000-0000-000000000001", rootNotesEmpty[0].id)
            assertEquals("", rootNotesEmpty[0].folderPath)

            // Query root folder with "/"
            val rootNotesSlash = repository.observeNotesInFolder("/").first()
            assertEquals(1, rootNotesSlash.size)
            assertEquals("0191ebc2-0000-0000-0000-000000000001", rootNotesSlash[0].id)

            // Query subfolder with "Ideas"
            val ideasNotes = repository.observeNotesInFolder("Ideas").first()
            assertEquals(1, ideasNotes.size)
            assertEquals("0191ebc2-0000-0000-0000-000000000002", ideasNotes[0].id)
            assertEquals("Ideas", ideasNotes[0].folderPath)

            // Query subfolder with "/Ideas"
            val ideasNotesSlash = repository.observeNotesInFolder("/Ideas").first()
            assertEquals(1, ideasNotesSlash.size)
            assertEquals("0191ebc2-0000-0000-0000-000000000002", ideasNotesSlash[0].id)

            // Query non-existent folder
            val emptyNotes = repository.observeNotesInFolder("NonExistent").first()
            assertTrue(emptyNotes.isEmpty())
        }

    @Test
    fun readBody_stripsFrontmatterAndReturnsRawBody() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val note =
                TestDocumentFile(
                    parent = root,
                    docName = "Doc.md",
                    isDir = false,
                    content =
                        """
                        ---
                        id: 0191ebc2-0000-0000-0000-000000000005
                        title: Title
                        type: note
                        ---
                        First line of body.
                        Second line of body.
                        """.trimIndent(),
                )
            root.children.add(note)

            val fileSource = FakeSafNoteFileSource(root)
            val repository =
                SafNoteRepository(
                    fileSource = fileSource,
                    parser = parser,
                    initialTreeUri = treeUri,
                )

            val body = repository.readBody("0191ebc2-0000-0000-0000-000000000005")
            assertEquals("First line of body.\nSecond line of body.", body.trim())
        }

    @Test
    fun listFolders_returnsSubfoldersExcludingHidden() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val dirA = TestDocumentFile(parent = root, docName = "Personal", isDir = true)
            val dirB = TestDocumentFile(parent = dirA, docName = "Finances", isDir = true)
            dirA.children.add(dirB)
            root.children.add(dirA)

            val hiddenDir = TestDocumentFile(parent = root, docName = ".locus", isDir = true)
            root.children.add(hiddenDir)

            val fileSource = FakeSafNoteFileSource(root)
            val repository =
                SafNoteRepository(
                    fileSource = fileSource,
                    parser = parser,
                    initialTreeUri = treeUri,
                )

            val folders = repository.listFolders()
            assertEquals(listOf("Personal", "Personal/Finances"), folders)
        }

    @Test
    fun missingTreeUri_returnsEmptyListsWithoutCrashing() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val fileSource = FakeSafNoteFileSource(root)
            val repository =
                SafNoteRepository(
                    fileSource = fileSource,
                    parser = parser,
                    initialTreeUri = null,
                )

            val notes = repository.observeAllNotes().first()
            assertTrue(notes.isEmpty())

            val folders = repository.listFolders()
            assertTrue(folders.isEmpty())
        }

    @Test
    fun setPinned_updatesFrontmatterAndEmitsNewState() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val rawContent =
                """
                ---
                id: 0191ebc2-841e-7b28-b072-46ebc605cf52
                title: Pin Test Note
                type: note
                created: 2026-09-16T10:00:00Z
                modified: 2026-09-16T10:00:00Z
                pinned: false
                ---
                Some content here.
                """.trimIndent()

            val noteFile =
                TestDocumentFile(
                    parent = root,
                    docName = "Pin Test Note.md",
                    isDir = false,
                    content = rawContent,
                )
            root.children.add(noteFile)

            val fileSource = FakeSafNoteFileSource(root)
            val fileWriter =
                object : NoteFileWriter {
                    override suspend fun atomicWrite(
                        noteId: String,
                        path: String,
                        content: String,
                    ): Result<FlushReceipt> {
                        noteFile.content = content
                        return Result.success(
                            FlushReceipt(
                                noteId = noteId,
                                checksum = Checksum.sha256(content),
                                flushedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            val dummyQueue =
                object : IndexUpdateQueue {
                    override suspend fun enqueue(receipt: FlushReceipt) = Unit
                }
            val testDispatchers =
                object : DispatcherProvider {
                    override val io: CoroutineDispatcher = Dispatchers.Unconfined
                    override val default: CoroutineDispatcher = Dispatchers.Unconfined
                    override val main: CoroutineDispatcher = Dispatchers.Unconfined
                    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
                }
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue = dummyQueue,
                    dispatchers = testDispatchers,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                )

            val repository =
                SafNoteRepository(
                    fileSource = fileSource,
                    parser = parser,
                    coordinator = coordinator,
                    initialTreeUri = treeUri,
                )

            // Initial state: not pinned
            val initial = repository.observeAllNotes().first()
            assertEquals(1, initial.size)
            assertEquals(false, initial[0].pinned)

            // Pin the note
            repository.setPinned("0191ebc2-841e-7b28-b072-46ebc605cf52", true)

            // Verify file content updated with frontmatter round-trip
            assertTrue(noteFile.content.contains("pinned: true"))

            // Verify repository flow emits updated state
            val afterPin = repository.observeAllNotes().first()
            assertEquals(1, afterPin.size)
            assertEquals(true, afterPin[0].pinned)

            // Unpin the note
            repository.setPinned("0191ebc2-841e-7b28-b072-46ebc605cf52", false)
            assertTrue(noteFile.content.contains("pinned: false"))
            val afterUnpin = repository.observeAllNotes().first()
            assertEquals(false, afterUnpin[0].pinned)
        }

    @Test
    fun setColor_updatesFrontmatterAndEmitsNewState() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val rawContent =
                """
                ---
                id: 0191ebc2-841e-7b28-b072-46ebc605cf52
                title: Color Test Note
                type: note
                created: 2026-09-16T10:00:00Z
                modified: 2026-09-16T10:00:00Z
                pinned: false
                ---
                Some content here.
                """.trimIndent()

            val noteFile =
                TestDocumentFile(
                    parent = root,
                    docName = "Color Test Note.md",
                    isDir = false,
                    content = rawContent,
                )
            root.children.add(noteFile)

            val fileSource = FakeSafNoteFileSource(root)
            val fileWriter =
                object : NoteFileWriter {
                    override suspend fun atomicWrite(
                        noteId: String,
                        path: String,
                        content: String,
                    ): Result<FlushReceipt> {
                        noteFile.content = content
                        return Result.success(
                            FlushReceipt(
                                noteId = noteId,
                                checksum = Checksum.sha256(content),
                                flushedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            val dummyQueue =
                object : IndexUpdateQueue {
                    override suspend fun enqueue(receipt: FlushReceipt) = Unit
                }
            val testDispatchers =
                object : DispatcherProvider {
                    override val io: CoroutineDispatcher = Dispatchers.Unconfined
                    override val default: CoroutineDispatcher = Dispatchers.Unconfined
                    override val main: CoroutineDispatcher = Dispatchers.Unconfined
                    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
                }
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue = dummyQueue,
                    dispatchers = testDispatchers,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                )

            val repository =
                SafNoteRepository(
                    fileSource = fileSource,
                    parser = parser,
                    coordinator = coordinator,
                    initialTreeUri = treeUri,
                )

            // Initial state: no color
            val initial = repository.observeAllNotes().first()
            assertEquals(1, initial.size)
            assertNull(initial[0].color)

            // Set color to Coral (#F28B82)
            repository.setColor("0191ebc2-841e-7b28-b072-46ebc605cf52", "#F28B82")

            // Verify file content updated with frontmatter round-trip
            assertTrue(noteFile.content.contains("#F28B82"))

            // Verify repository flow emits updated state
            val afterColor = repository.observeAllNotes().first()
            assertEquals(1, afterColor.size)
            assertEquals("#F28B82", afterColor[0].color)

            // Clear color (set to null)
            repository.setColor("0191ebc2-841e-7b28-b072-46ebc605cf52", null)
            val afterClear = repository.observeAllNotes().first()
            assertEquals(1, afterClear.size)
            assertNull(afterClear[0].color)
        }

    @Test
    fun setTags_updatesFrontmatterAndEmitsNewState() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val rawContent =
                """
                ---
                id: 0191ebc2-841e-7b28-b072-46ebc605cf52
                title: Tag Test Note
                type: note
                created: 2026-09-16T10:00:00Z
                modified: 2026-09-16T10:00:00Z
                tags:
                  - original
                ---
                Some content here.
                """.trimIndent()

            val noteFile =
                TestDocumentFile(
                    parent = root,
                    docName = "Tag Test Note.md",
                    isDir = false,
                    content = rawContent,
                )
            root.children.add(noteFile)

            val fileSource = FakeSafNoteFileSource(root)
            val fileWriter =
                object : NoteFileWriter {
                    override suspend fun atomicWrite(
                        noteId: String,
                        path: String,
                        content: String,
                    ): Result<FlushReceipt> {
                        noteFile.content = content
                        return Result.success(
                            FlushReceipt(
                                noteId = noteId,
                                checksum = Checksum.sha256(content),
                                flushedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            val dummyQueue =
                object : IndexUpdateQueue {
                    override suspend fun enqueue(receipt: FlushReceipt) = Unit
                }
            val testDispatchers =
                object : DispatcherProvider {
                    override val io: CoroutineDispatcher = Dispatchers.Unconfined
                    override val default: CoroutineDispatcher = Dispatchers.Unconfined
                    override val main: CoroutineDispatcher = Dispatchers.Unconfined
                    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
                }
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue = dummyQueue,
                    dispatchers = testDispatchers,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                )

            val repository =
                SafNoteRepository(
                    fileSource = fileSource,
                    parser = parser,
                    coordinator = coordinator,
                    initialTreeUri = treeUri,
                )

            // Set tags to [original, android, compose]
            repository.setTags(
                "0191ebc2-841e-7b28-b072-46ebc605cf52",
                listOf("original", "android", "compose"),
            )

            // Verify file content updated with frontmatter round-trip
            assertTrue(noteFile.content.contains("android"))
            assertTrue(noteFile.content.contains("compose"))

            // Verify repository flow emits updated state
            val afterTags = repository.observeAllNotes().first()
            assertEquals(1, afterTags.size)
            assertEquals(listOf("original", "android", "compose"), afterTags[0].tags)
        }

    private fun createRepository(
        root: TestDocumentFile,
        noteDao: com.locus.core.data.db.NoteDao? = null,
    ): SafNoteRepository {
        val fileSource = FakeSafNoteFileSource(root)
        val fileWriter =
            object : NoteFileWriter {
                override suspend fun atomicWrite(
                    noteId: String,
                    path: String,
                    content: String,
                ): Result<FlushReceipt> =
                    Result.success(
                        FlushReceipt(
                            noteId = noteId,
                            checksum = Checksum.sha256(content),
                            flushedAt = System.currentTimeMillis(),
                        ),
                    )
            }
        val dummyQueue =
            object : IndexUpdateQueue {
                override suspend fun enqueue(receipt: FlushReceipt) = Unit
            }
        val testDispatchers =
            object : DispatcherProvider {
                override val io: CoroutineDispatcher = Dispatchers.Unconfined
                override val default: CoroutineDispatcher = Dispatchers.Unconfined
                override val main: CoroutineDispatcher = Dispatchers.Unconfined
                override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
            }
        val coordinator =
            NoteFlushCoordinator(
                fileWriter = fileWriter,
                indexQueue = dummyQueue,
                dispatchers = testDispatchers,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            )
        return SafNoteRepository(
            fileSource = fileSource,
            parser = parser,
            coordinator = coordinator,
            initialTreeUri = treeUri,
            noteDao = noteDao,
        )
    }

    @Test
    fun createFolder_inRoot_createsDirectoryAndRefreshesFlow() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val repository = createRepository(root)

            repository.createFolder(parentPath = "", name = "Work")

            val folders = repository.listFolders()
            assertEquals(listOf("Work"), folders)
            val createdChild = root.listFiles().firstOrNull { it.name == "Work" }
            assertTrue(createdChild != null && createdChild.isDirectory)
        }

    @Test
    fun createFolder_inSubfolder_resolvesParentAndCreatesSubdirectory() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val repository = createRepository(root)

            repository.createFolder(parentPath = "", name = "Work")
            repository.createFolder(parentPath = "Work", name = "Projects")

            val folders = repository.listFolders()
            assertEquals(listOf("Work", "Work/Projects"), folders)
        }

    @Test(expected = IllegalArgumentException::class)
    fun createFolder_emptyName_throwsIllegalArgumentException() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val repository = createRepository(root)

            repository.createFolder(parentPath = "", name = "   ")
        }

    @Test(expected = IllegalArgumentException::class)
    fun createFolder_nameWithSeparator_throwsIllegalArgumentException() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val repository = createRepository(root)

            repository.createFolder(parentPath = "", name = "sub/dir")
        }

    @Test
    fun listFolders_reflectsExternallyCreatedFolders() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val repository = createRepository(root)

            assertEquals(emptyList<String>(), repository.listFolders())

            // Simulate external file system creation in SAF
            val externalDir = root.createDirectory("ExternalFolder")
            externalDir?.createDirectory("Nested")

            val folders = repository.listFolders()
            assertEquals(listOf("ExternalFolder", "ExternalFolder/Nested"), folders)
        }

    @Test
    fun deleteNote_and_restoreNote_acceptanceCycle() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val workDir = TestDocumentFile(parent = root, docName = "Work", isDir = true)
            val projectsDir = TestDocumentFile(parent = workDir, docName = "Projects", isDir = true)
            workDir.children.add(projectsDir)
            root.children.add(workDir)

            val rawNote =
                """
                ---
                id: 0191ebc2-0000-7000-8000-000000000099
                title: Critical Project
                type: note
                ---
                Top secret deliverables.
                """.trimIndent()
            val noteFile =
                TestDocumentFile(
                    parent = projectsDir,
                    docName = "Critical Project.md",
                    isDir = false,
                    content = rawNote,
                )
            projectsDir.children.add(noteFile)

            val noteDao =
                object : com.locus.core.data.db.NoteDao {
                    val map =
                        java.util.concurrent.ConcurrentHashMap<
                            String,
                            com.locus.core.data.db.NoteIndexEntity,
                        >()

                    override suspend fun upsert(entity: com.locus.core.data.db.NoteIndexEntity) {
                        map[entity.id] = entity
                    }

                    override suspend fun getById(id: String): com.locus.core.data.db.NoteIndexEntity? = map[id]

                    override suspend fun deleteById(id: String) {
                        map.remove(id)
                    }

                    override fun observeAll(): kotlinx.coroutines.flow.Flow<
                        List<com.locus.core.data.db.NoteIndexEntity>,
                    > =
                        kotlinx.coroutines.flow.MutableStateFlow(map.values.toList())

                    override fun observeByFolder(
                        path: String,
                    ): kotlinx.coroutines.flow.Flow<List<com.locus.core.data.db.NoteIndexEntity>> =
                        kotlinx.coroutines.flow.MutableStateFlow(
                            map.values.filter { it.folderPath == path },
                        )

                    override suspend fun ftsSearch(query: String): List<com.locus.core.data.db.NoteIndexEntity> =
                        map.values.filter { it.title.contains(query, ignoreCase = true) }

                    override suspend fun ftsSearchScoped(
                        query: androidx.sqlite.db.SupportSQLiteQuery,
                    ): List<com.locus.core.data.db.NoteIndexEntity> = emptyList()
                }

            val repository = createRepository(root, noteDao = noteDao)

            // Step 1: Initial state - note exists in "Work/Projects"
            val initialNotes = repository.observeAllNotes().first()
            assertEquals(1, initialNotes.size)
            assertEquals("0191ebc2-0000-7000-8000-000000000099", initialNotes[0].id)
            assertEquals("Work/Projects", initialNotes[0].folderPath)

            val initialFolderNotes = repository.observeNotesInFolder("Work/Projects").first()
            assertEquals(1, initialFolderNotes.size)

            val initialFolders = repository.listFolders()
            assertEquals(listOf("Work", "Work/Projects"), initialFolders)

            // Step 2: Delete note
            repository.deleteNote("0191ebc2-0000-7000-8000-000000000099")

            // AC: Deleting moves files to .locus/trash/, note no longer in active views
            val notesAfterDelete = repository.observeAllNotes().first()
            assertTrue(notesAfterDelete.isEmpty())

            val folderNotesAfterDelete = repository.observeNotesInFolder("Work/Projects").first()
            assertTrue(folderNotesAfterDelete.isEmpty())

            // AC: .locus/trash/ never appears in Tree
            val foldersAfterDelete = repository.listFolders()
            assertEquals(listOf("Work", "Work/Projects"), foldersAfterDelete)
            assertTrue(foldersAfterDelete.none { it.startsWith(".locus") })

            // AC: .locus/trash/ never appears in Search
            val searchResultsAfterDelete = noteDao.ftsSearch("Critical")
            assertTrue(searchResultsAfterDelete.isEmpty())

            // AC: Note is visible in Trash with original folderPath
            val trashNotes = repository.observeTrash().first()
            assertEquals(1, trashNotes.size)
            assertEquals("0191ebc2-0000-7000-8000-000000000099", trashNotes[0].id)
            assertEquals("Work/Projects", trashNotes[0].folderPath)
            assertEquals("Critical Project", trashNotes[0].title)

            // Step 3: Restore note
            repository.restoreNote("0191ebc2-0000-7000-8000-000000000099")

            // AC: Restoring returns note to original folder with original id unchanged
            val notesAfterRestore = repository.observeAllNotes().first()
            assertEquals(1, notesAfterRestore.size)
            assertEquals("0191ebc2-0000-7000-8000-000000000099", notesAfterRestore[0].id)
            assertEquals("Work/Projects", notesAfterRestore[0].folderPath)
            assertEquals("Critical Project", notesAfterRestore[0].title)

            val folderNotesAfterRestore = repository.observeNotesInFolder("Work/Projects").first()
            assertEquals(1, folderNotesAfterRestore.size)
            assertEquals("0191ebc2-0000-7000-8000-000000000099", folderNotesAfterRestore[0].id)

            // AC: Trash is now empty
            val trashAfterRestore = repository.observeTrash().first()
            assertTrue(trashAfterRestore.isEmpty())

            // AC: Search returns the restored note
            val searchResultsAfterRestore = noteDao.ftsSearch("Critical")
            assertEquals(1, searchResultsAfterRestore.size)
            assertEquals("0191ebc2-0000-7000-8000-000000000099", searchResultsAfterRestore[0].id)

            // AC: Tree still excludes .locus
            val foldersAfterRestore = repository.listFolders()
            assertEquals(listOf("Work", "Work/Projects"), foldersAfterRestore)
        }

    @Test
    fun restoreNote_withFilenameCollision_resolvesCollisionAndPreservesId() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val workDir = TestDocumentFile(parent = root, docName = "Work", isDir = true)
            root.children.add(workDir)

            val noteId = "0191ebc2-0000-7000-8000-000000000088"
            val rawNote =
                """
                ---
                id: $noteId
                title: Summary
                type: note
                ---
                Original summary.
                """.trimIndent()
            val noteFile =
                TestDocumentFile(
                    parent = workDir,
                    docName = "Summary.md",
                    isDir = false,
                    content = rawNote,
                )
            workDir.children.add(noteFile)

            val repository = createRepository(root)
            repository.deleteNote(noteId)

            // Create a colliding file in Work
            val collidingNote =
                TestDocumentFile(
                    parent = workDir,
                    docName = "Summary.md",
                    isDir = false,
                    content = "# Newly created summary",
                )
            workDir.children.add(collidingNote)

            // Restore the trashed note
            repository.restoreNote(noteId)

            // Verify both files exist and restored note kept its original id
            val allNotes = repository.observeAllNotes().first()
            val restoredNote = allNotes.firstOrNull { it.id == noteId }
            assertTrue(restoredNote != null)
            assertEquals(noteId, restoredNote!!.id)
            assertEquals("Work", restoredNote.folderPath)

            // Disk file was resolved with collision resolver (Summary (2).md)
            val resolvedFile = workDir.children.firstOrNull { it.name == "Summary (2).md" }
            assertTrue(resolvedFile != null)
        }

    @Test
    fun setTitle_updatesFrontmatterAndRenamesFile() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val workDir = TestDocumentFile(parent = root, docName = "Work", isDir = true)
            root.children.add(workDir)

            val noteId = "0191ebc2-0000-7000-8000-000000000077"
            val rawNote =
                """
                ---
                id: $noteId
                title: Old Title
                type: note
                ---
                Some content.
                """.trimIndent()
            val noteFile =
                TestDocumentFile(
                    parent = workDir,
                    docName = "Old Title.md",
                    isDir = false,
                    content = rawNote,
                )
            workDir.children.add(noteFile)

            val repository = createRepository(root)

            repository.setTitle(noteId, "New Title")

            val notes = repository.observeAllNotes().first()
            assertEquals(1, notes.size)
            assertEquals("New Title", notes[0].title)

            // Verify disk file was renamed
            val renamedDoc = workDir.children.firstOrNull { it.name == "New Title.md" }
            assertTrue(renamedDoc != null)

            // Verify body content still intact
            val body = repository.readBody(noteId)
            assertEquals("Some content.", body.trim())
        }

    @Test
    fun setTitle_emptyTitle_persistsEmptyTitleWithoutSnappingToUntitled() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val workDir = TestDocumentFile(parent = root, docName = "Work", isDir = true)
            root.children.add(workDir)

            val noteId = "0191ebc2-0000-7000-8000-000000000066"
            val rawNote =
                """
                ---
                id: $noteId
                title: Some Title
                type: note
                ---
                Body.
                """.trimIndent()
            val noteFile =
                TestDocumentFile(
                    parent = workDir,
                    docName = "Some Title.md",
                    isDir = false,
                    content = rawNote,
                )
            workDir.children.add(noteFile)

            val repository = createRepository(root)

            // Clear title
            repository.setTitle(noteId, "")

            val raw = noteFile.content
            val containsEmptyTitle =
                raw.contains("title: \"\"") ||
                    raw.contains("title: ''") ||
                    raw.contains("title:\n") ||
                    raw.contains("title: null") ||
                    raw.contains("title: ")
            assertTrue(containsEmptyTitle)
        }

    @Test
    fun acceptance_twentyOneSaves_andRestore_producesNewHistorySnapshot() =
        runTest {
            val root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
            val fileSource = FakeSafNoteFileSource(root)
            val treeUriStore =
                object : TreeUriStore {
                    private var uri: Uri? = treeUri
                    override val treeUriFlow = MutableStateFlow(treeUri)

                    override suspend fun getTreeUri(): Uri? = uri

                    override suspend fun setTreeUri(uri: Uri) {
                        this.uri = uri
                        treeUriFlow.value = uri
                    }
                }
            val historyStore =
                com.locus.core.data.history
                    .NoteHistoryStore(fileSource, treeUriStore)
            val fileWriter =
                object : NoteFileWriter {
                    override suspend fun atomicWrite(
                        noteId: String,
                        path: String,
                        content: String,
                    ): Result<FlushReceipt> {
                        val doc =
                            fileSource.listMarkdownFiles(treeUri).firstOrNull {
                                it.name == "$noteId.md" || it.uri.toString() == path
                            }
                        if (doc != null && (doc as TestDocumentFile).content.isNotEmpty()) {
                            historyStore.snapshot(noteId, doc.content)
                        }
                        if (doc != null) {
                            (doc as TestDocumentFile).content = content
                        } else {
                            val newDoc =
                                root.createFile("text/markdown", "$noteId.md") as
                                    TestDocumentFile
                            newDoc.content = content
                        }
                        return Result.success(
                            FlushReceipt(
                                noteId = noteId,
                                checksum = Checksum.sha256(content),
                                flushedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                }

            val testDispatchers =
                object : DispatcherProvider {
                    override val io: CoroutineDispatcher = Dispatchers.Unconfined
                    override val default: CoroutineDispatcher = Dispatchers.Unconfined
                    override val main: CoroutineDispatcher = Dispatchers.Unconfined
                    override val mainImmediate: CoroutineDispatcher = Dispatchers.Unconfined
                }
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue =
                        object : IndexUpdateQueue {
                            override suspend fun enqueue(receipt: FlushReceipt) = Unit
                        },
                    dispatchers = testDispatchers,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                )
            val repository =
                SafNoteRepository(
                    fileSource = fileSource,
                    parser = parser,
                    initialTreeUri = treeUri,
                    coordinator = coordinator,
                    historyStore = historyStore,
                )

            val note =
                repository.createNote(
                    folderPath = "",
                    title = "My Note",
                    type = com.locus.core.domain.notes.NoteType.NOTE,
                )
            val noteId = note.id

            // Initial create doesn't snapshot (no prior content)
            assertEquals(0, repository.listRevisions(noteId).size)

            // Perform 21 sequential edits + forceFlush
            for (i in 1..21) {
                repository.edit(noteId, "Version $i body")
                repository.forceFlush(noteId, com.locus.core.domain.notes.FlushTrigger.EDITOR_CLOSE)
            }

            // Exactly 20 revisions retained (cap 20 revisions/note FIFO: versions 20 down to 1)
            val revisions = repository.listRevisions(noteId)
            assertEquals(20, revisions.size)
            assertEquals("Version 20 body", revisions.first().body)
            assertEquals("Version 1 body", revisions.last().body)
            assertEquals("Version 21 body", repository.readBody(noteId))
            // Now restore Version 5
            val version5 = revisions.first { it.body == "Version 5 body" }
            repository.edit(noteId, version5.body)
            repository.forceFlush(noteId, com.locus.core.domain.notes.FlushTrigger.EDITOR_CLOSE)

            // Restoring produces a NEW snapshot of the version it replaced (Version 21 body)
            val updatedRevisions = repository.listRevisions(noteId)
            assertEquals(20, updatedRevisions.size)
            assertEquals("Version 21 body", updatedRevisions.first().body)

            // Note body is now restored to Version 5
            assertEquals("Version 5 body", repository.readBody(noteId))
        }
}
