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
import androidx.sqlite.db.SupportSQLiteQuery
import com.locus.core.data.db.NoteDao
import com.locus.core.data.db.NoteIndexEntity
import com.locus.core.domain.notes.FrontmatterParser
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.SnakeYamlCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
class TrashManagerTest {
    private val treeUri: Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ANotes")
    private lateinit var parser: FrontmatterParser
    private lateinit var root: TestDocumentFile
    private lateinit var fileSource: TestSafFileSource
    private lateinit var noteDao: InMemoryNoteDao
    private lateinit var trashManager: TrashManager

    @Before
    fun setUp() {
        parser = FrontmatterParser(SnakeYamlCodec())
        root = TestDocumentFile(parent = null, docName = "Notes", isDir = true)
        fileSource = TestSafFileSource(root)
        noteDao = InMemoryNoteDao()
        trashManager =
            TrashManager(
                fileSource = fileSource,
                parser = parser,
                noteDao = noteDao,
            )
    }

    @Test
    fun moveToTrash_movesFileAndCreatesOriginSidecar() =
        runTest {
            val workFolder = TestDocumentFile(parent = root, docName = "Work", isDir = true)
            root.children.add(workFolder)

            val rawNote =
                """
                ---
                id: note-uuid-001
                title: Project Plan
                type: note
                ---
                Content of plan.
                """.trimIndent()
            val noteFile =
                TestDocumentFile(
                    parent = workFolder,
                    docName = "Project Plan.md",
                    isDir = false,
                    content = rawNote,
                )
            workFolder.children.add(noteFile)

            noteDao.upsert(
                NoteIndexEntity(
                    id = "note-uuid-001",
                    title = "Project Plan",
                    type = NoteType.NOTE,
                    folderPath = "Work",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = java.time.Instant.now(),
                    modified = java.time.Instant.now(),
                    checksum = "dummy",
                    bodyPreview = "Content of plan.",
                ),
            )

            trashManager.moveToTrash(
                root = root,
                noteId = "note-uuid-001",
                noteDoc = noteFile,
                originalFolderPath = "Work",
            )

            // Original work folder no longer has the note
            assertEquals(0, workFolder.children.size)

            // .locus/trash exists under root
            val locus = root.children.firstOrNull { it.name == ".locus" } as? TestDocumentFile
            assertNotNull(locus)
            val trash = locus!!.children.firstOrNull { it.name == "trash" } as? TestDocumentFile
            assertNotNull(trash)

            // Note is now in trash
            val trashedNote = trash!!.children.firstOrNull { it.name == "Project Plan.md" }
            assertNotNull(trashedNote)

            // Origin sidecar exists and contains "Work"
            val sidecar =
                trash.children.firstOrNull { it.name == "note-uuid-001.origin" } as?
                    TestDocumentFile
            assertNotNull(sidecar)
            assertEquals("Work", sidecar!!.content.trim())

            // NoteDao no longer contains the note
            assertNull(noteDao.getById("note-uuid-001"))
        }

    @Test
    fun restoreFromTrash_restoresToOriginalFolderAndDeletesSidecar() =
        runTest {
            val workFolder = TestDocumentFile(parent = root, docName = "Work", isDir = true)
            root.children.add(workFolder)

            val rawNote =
                """
                ---
                id: note-uuid-002
                title: Meeting Notes
                type: note
                ---
                Meeting discussion.
                """.trimIndent()
            val noteFile =
                TestDocumentFile(
                    parent = workFolder,
                    docName = "Meeting Notes.md",
                    isDir = false,
                    content = rawNote,
                )
            workFolder.children.add(noteFile)

            trashManager.moveToTrash(
                root = root,
                noteId = "note-uuid-002",
                noteDoc = noteFile,
                originalFolderPath = "Work",
            )

            // Now restore it
            val restored = trashManager.restoreFromTrash(root, "note-uuid-002")

            assertEquals("note-uuid-002", restored.id)
            assertEquals("Work", restored.folderPath)
            assertEquals("Meeting Notes", restored.title)

            // File is back in Work
            val restoredFile = workFolder.children.firstOrNull { it.name == "Meeting Notes.md" }
            assertNotNull(restoredFile)

            // Trash is empty of this note and its sidecar
            val locus = root.children.firstOrNull { it.name == ".locus" } as? TestDocumentFile
            val trash = locus!!.children.firstOrNull { it.name == "trash" } as? TestDocumentFile
            assertEquals(0, trash!!.children.size)

            // NoteDao is re-indexed with Work
            val indexed = noteDao.getById("note-uuid-002")
            assertNotNull(indexed)
            assertEquals("Work", indexed!!.folderPath)
        }

    @Test
    fun restoreFromTrash_withCollision_usesFilenameCollisionResolver() =
        runTest {
            val workFolder = TestDocumentFile(parent = root, docName = "Work", isDir = true)
            root.children.add(workFolder)

            val rawNote =
                """
                ---
                id: note-uuid-003
                title: Report
                type: note
                ---
                Original report.
                """.trimIndent()
            val noteFile =
                TestDocumentFile(
                    parent = workFolder,
                    docName = "Report.md",
                    isDir = false,
                    content = rawNote,
                )
            workFolder.children.add(noteFile)

            // Trashed
            trashManager.moveToTrash(
                root = root,
                noteId = "note-uuid-003",
                noteDoc = noteFile,
                originalFolderPath = "Work",
            )

            // In the meantime, another note named Report.md was created in Work
            val newReport =
                TestDocumentFile(
                    parent = workFolder,
                    docName = "Report.md",
                    isDir = false,
                    content = "# Brand new report",
                )
            workFolder.children.add(newReport)

            // Restore the original note
            val restored = trashManager.restoreFromTrash(root, "note-uuid-003")

            // Restored note still has its original id and folder
            assertEquals("note-uuid-003", restored.id)
            assertEquals("Work", restored.folderPath)

            // Collision resolved to Report (2).md
            val restoredFile = workFolder.children.firstOrNull { it.name == "Report (2).md" }
            assertNotNull(restoredFile)

            // Both files exist in Work
            assertEquals(2, workFolder.children.size)
        }

    @Test
    fun restoreFromTrash_recreatesMissingOriginFolder() =
        runTest {
            val personalFolder = TestDocumentFile(parent = root, docName = "Personal", isDir = true)
            root.children.add(personalFolder)

            val rawNote =
                """
                ---
                id: note-uuid-004
                title: Diary
                type: note
                ---
                Secret entry.
                """.trimIndent()
            val noteFile =
                TestDocumentFile(
                    parent = personalFolder,
                    docName = "Diary.md",
                    isDir = false,
                    content = rawNote,
                )
            personalFolder.children.add(noteFile)

            trashManager.moveToTrash(
                root = root,
                noteId = "note-uuid-004",
                noteDoc = noteFile,
                originalFolderPath = "Personal",
            )

            // Personal folder was deleted while note was in trash
            personalFolder.delete()
            assertNull(root.children.firstOrNull { it.name == "Personal" })

            // Restore recreates the folder
            val restored = trashManager.restoreFromTrash(root, "note-uuid-004")
            assertEquals("note-uuid-004", restored.id)
            assertEquals("Personal", restored.folderPath)

            val recreatedFolder =
                root.children.firstOrNull { it.name == "Personal" } as? TestDocumentFile
            assertNotNull(recreatedFolder)
            assertNotNull(recreatedFolder!!.children.firstOrNull { it.name == "Diary.md" })
        }

    @Test
    fun observeTrash_listsTrashNotesWithOriginFolder() =
        runTest {
            val workFolder = TestDocumentFile(parent = root, docName = "Work", isDir = true)
            root.children.add(workFolder)

            val note1 =
                TestDocumentFile(
                    parent = workFolder,
                    docName = "N1.md",
                    isDir = false,
                    content = "---\nid: id-1\ntitle: Note One\ntype: note\n---\nBody 1",
                )
            workFolder.children.add(note1)

            val note2 =
                TestDocumentFile(
                    parent = root,
                    docName = "N2.md",
                    isDir = false,
                    content = "---\nid: id-2\ntitle: Note Two\ntype: note\n---\nBody 2",
                )
            root.children.add(note2)

            trashManager.moveToTrash(root, "id-1", note1, "Work")
            trashManager.moveToTrash(root, "id-2", note2, "")

            val trashNotes = trashManager.loadTrashNotes(treeUri)
            assertEquals(2, trashNotes.size)

            val n1 = trashNotes.firstOrNull { it.id == "id-1" }
            assertNotNull(n1)
            assertEquals("Work", n1!!.folderPath)
            assertEquals("Note One", n1.title)

            val n2 = trashNotes.firstOrNull { it.id == "id-2" }
            assertNotNull(n2)
            assertEquals("", n2!!.folderPath)
            assertEquals("Note Two", n2.title)
        }

    private class TestSafFileSource(
        val rootDoc: TestDocumentFile,
    ) : SafNoteFileSource {
        override fun getRootDocument(treeUri: Uri): DocumentFile = rootDoc

        override fun listMarkdownFiles(treeUri: Uri): List<DocumentFile> {
            val result = mutableListOf<DocumentFile>()

            fun collect(dir: DocumentFile) {
                for (child in dir.listFiles()) {
                    if (child.isDirectory && !child.name.orEmpty().startsWith(".")) {
                        collect(child)
                    } else if (child.isFile &&
                        child.name.orEmpty().endsWith(".md", ignoreCase = true)
                    ) {
                        result.add(child)
                    }
                }
            }
            collect(rootDoc)
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

        override fun listFolders(treeUri: Uri): List<String> = emptyList()
    }

    private class InMemoryNoteDao : NoteDao {
        private val map = ConcurrentHashMap<String, NoteIndexEntity>()

        override suspend fun upsert(entity: NoteIndexEntity) {
            map[entity.id] = entity
        }

        override suspend fun getById(id: String): NoteIndexEntity? = map[id]

        override suspend fun deleteById(id: String) {
            map.remove(id)
        }

        override fun observeAll(): Flow<List<NoteIndexEntity>> = MutableStateFlow(map.values.toList())

        override fun observeByFolder(path: String): Flow<List<NoteIndexEntity>> =
            MutableStateFlow(map.values.filter { it.folderPath == path })

        override suspend fun ftsSearch(query: String): List<NoteIndexEntity> = emptyList()

        override suspend fun ftsSearchScoped(query: SupportSQLiteQuery): List<NoteIndexEntity> = emptyList()
    }
}
