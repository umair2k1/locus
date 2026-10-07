package com.locus.core.data.audit

import android.net.Uri
import com.locus.core.data.files.AndroidSafNoteFileSource
import com.locus.core.data.files.TreeUriStore
import com.locus.core.data.history.NoteHistoryStore
import com.locus.core.domain.agent.AuditEntry
import com.locus.core.domain.notes.HistoryRevision
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RoomAuditJournalTest {
    private lateinit var fakeDao: FakeAuditDao
    private lateinit var fakeHistoryStore: FakeNoteHistoryStore
    private lateinit var fakeNoteRepository: FakeNoteRepository
    private lateinit var journal: RoomAuditJournal

    @Before
    fun setUp() {
        fakeDao = FakeAuditDao()
        fakeHistoryStore = FakeNoteHistoryStore()
        fakeNoteRepository = FakeNoteRepository()
        journal = RoomAuditJournal(fakeDao, fakeHistoryStore, fakeNoteRepository)
    }

    @Test
    fun recordAndObserve_recordsAuditEntry() =
        runTest {
            val entry =
                AuditEntry(
                    id = "entry-1",
                    toolName = "update_note",
                    argumentsJson = """{"noteId":"note-1","content":"new"}""",
                    affectedNoteIds = listOf("note-1"),
                    timestamp = 1000L,
                    modelId = "local-llama",
                    diff = "-old\n+new",
                )

            journal.record(entry)

            val entries = journal.observeEntries().first()
            assertEquals(1, entries.size)
            assertEquals("entry-1", entries[0].id)
            assertEquals("update_note", entries[0].toolName)
            assertEquals(listOf("note-1"), entries[0].affectedNoteIds)
            assertEquals("-old\n+new", entries[0].diff)
        }

    @Test
    fun revert_restoresPreWriteBodyFromHistoryStore() =
        runTest {
            val noteId = "note-revert-1"
            val preWriteBody = "Initial pristine note content"
            val postWriteBody = "Overwritten content by agent"

            // Setup note in repository
            fakeNoteRepository.notes[noteId] = postWriteBody

            // Setup revision in history store
            fakeHistoryStore.revisions[noteId] =
                listOf(
                    HistoryRevision(timestamp = 900L, body = preWriteBody),
                )

            val entry =
                AuditEntry(
                    id = "audit-revert-1",
                    toolName = "update_note",
                    argumentsJson = """{"noteId":"$noteId"}""",
                    affectedNoteIds = listOf(noteId),
                    timestamp = 1000L,
                    modelId = "test-model",
                    diff = "-$preWriteBody\n+$postWriteBody",
                )
            journal.record(entry)

            // Revert
            journal.revert("audit-revert-1")

            // Note body should be restored exactly
            assertEquals(preWriteBody, fakeNoteRepository.notes[noteId])
        }

    private class FakeAuditDao : AuditDao {
        private val entries = MutableStateFlow<List<AuditEntryEntity>>(emptyList())

        override suspend fun insert(entry: AuditEntryEntity) {
            entries.value = listOf(entry) + entries.value.filterNot { it.id == entry.id }
        }

        override fun observeAll(): Flow<List<AuditEntryEntity>> = entries

        override suspend fun getById(id: String): AuditEntryEntity? = entries.value.find { it.id == id }

        override suspend fun getAll(): List<AuditEntryEntity> = entries.value

        override suspend fun deleteById(id: String) {
            entries.value = entries.value.filterNot { it.id == id }
        }
    }

    private class FakeNoteHistoryStore :
        NoteHistoryStore(
            context = RuntimeEnvironment.getApplication(),
            fileSource = AndroidSafNoteFileSource(RuntimeEnvironment.getApplication()),
            treeUriStore =
                object : TreeUriStore {
                    override suspend fun getTreeUri(): Uri? = null

                    override suspend fun setTreeUri(uri: Uri) {
                        // no-op
                    }

                    override val treeUriFlow: Flow<Uri?> = flowOf(null)
                },
        ) {
        val revisions = mutableMapOf<String, List<HistoryRevision>>()

        override suspend fun listRevisions(noteId: String): List<HistoryRevision> = revisions[noteId].orEmpty()
    }

    private class FakeNoteRepository : NoteRepository {
        val notes = mutableMapOf<String, String>()

        override suspend fun readBody(noteId: String): String = notes[noteId].orEmpty()

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            notes[noteId] = newBody
        }

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = flowOf(emptyList())

        override fun observeAllNotes(): Flow<List<Note>> = flowOf(emptyList())

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
        ): Note = error("Not implemented")

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

        override suspend fun rescan(): RescanReport = error("Not implemented")
    }
}
