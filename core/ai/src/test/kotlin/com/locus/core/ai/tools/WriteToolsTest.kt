package com.locus.core.ai.tools

import com.locus.core.domain.notes.MergeNotesUseCase
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.SchedulingTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class WriteToolsTest {
    private lateinit var fakeRepo: FakeNoteRepository
    private lateinit var fakeAlarmScheduler: FakeAlarmScheduler

    @Before
    fun setUp() {
        fakeRepo = FakeNoteRepository()
        fakeAlarmScheduler = FakeAlarmScheduler()
    }

    @Test
    fun createNoteTool_createsNoteAndSetsBody() =
        runTest {
            val tool = CreateNoteTool(fakeRepo)
            val json =
                """
                {
                  "title": "Shopping List",
                  "folderPath": "Personal",
                  "type": "CHECKLIST",
                  "body": "- [ ] Milk\n- [ ] Bread"
                }
                """.trimIndent()

            val resultJson = tool.execute(json)
            val obj = Json.parseToJsonElement(resultJson).jsonObject
            val noteId = obj["noteId"]!!.jsonPrimitive.content
            assertEquals("Shopping List", obj["title"]!!.jsonPrimitive.content)

            val created = fakeRepo.getNote(noteId)
            assertNotNull(created)
            assertEquals("Shopping List", created!!.title)
            assertEquals("Personal", created.folderPath)
            assertEquals(NoteType.CHECKLIST, created.type)
            assertEquals("- [ ] Milk\n- [ ] Bread", fakeRepo.readBody(noteId))
        }

    @Test
    fun updateNoteTool_editsBodyAndTitle() =
        runTest {
            val note = fakeRepo.createNote("", "Old Title", NoteType.NOTE)
            val tool = UpdateNoteTool(fakeRepo)
            val json =
                """
                {
                  "noteId": "${note.id}",
                  "title": "New Title",
                  "body": "Updated content."
                }
                """.trimIndent()

            val resultJson = tool.execute(json)
            val obj = Json.parseToJsonElement(resultJson).jsonObject
            assertEquals(note.id, obj["noteId"]!!.jsonPrimitive.content)

            val updated = fakeRepo.getNote(note.id)
            assertNotNull(updated)
            assertEquals("New Title", updated!!.title)
            assertEquals("Updated content.", fakeRepo.readBody(note.id))
        }

    @Test
    fun appendToNoteTool_appendsToExistingBody() =
        runTest {
            val note = fakeRepo.createNote("", "Log", NoteType.NOTE)
            fakeRepo.edit(note.id, "Line 1")

            val tool = AppendToNoteTool(fakeRepo)
            val json =
                """
                {
                  "noteId": "${note.id}",
                  "text": "Line 2"
                }
                """.trimIndent()

            tool.execute(json)
            assertEquals("Line 1\n\nLine 2", fakeRepo.readBody(note.id))
        }

    @Test
    fun moveNoteTool_movesToFolder() =
        runTest {
            val note = fakeRepo.createNote("", "Note", NoteType.NOTE)
            val tool = MoveNoteTool(fakeRepo)
            val json =
                """
                {
                  "noteId": "${note.id}",
                  "targetFolderPath": "Work/Projects"
                }
                """.trimIndent()

            tool.execute(json)
            val moved = fakeRepo.getNote(note.id)
            assertEquals("Work/Projects", moved!!.folderPath)
        }

    @Test
    fun tagNoteTool_addsAndRemovesTags() =
        runTest {
            val note = fakeRepo.createNote("", "Tagged Note", NoteType.NOTE)
            fakeRepo.setTags(note.id, listOf("work"))

            val tool = TagNoteTool(fakeRepo)
            // Add tag
            val addJson =
                """
                {
                  "noteId": "${note.id}",
                  "tags": ["urgent", "p1"],
                  "action": "add"
                }
                """.trimIndent()
            tool.execute(addJson)
            assertEquals(listOf("work", "urgent", "p1"), fakeRepo.getNote(note.id)!!.tags)

            // Remove tag
            val removeJson =
                """
                {
                  "noteId": "${note.id}",
                  "tag": "work",
                  "action": "remove"
                }
                """.trimIndent()
            tool.execute(removeJson)
            assertEquals(listOf("urgent", "p1"), fakeRepo.getNote(note.id)!!.tags)
        }

    @Test
    fun trashNoteTool_deletesNote() =
        runTest {
            val note = fakeRepo.createNote("", "To Delete", NoteType.NOTE)
            val tool = TrashNoteTool(fakeRepo)
            val json = """{"noteId": "${note.id}"}"""

            tool.execute(json)
            assertTrue(fakeRepo.trashedIds.contains(note.id))
        }

    @Test
    fun createFolderTool_createsFolder() =
        runTest {
            val tool = CreateFolderTool(fakeRepo)
            val json =
                """
                {
                  "parentPath": "Personal",
                  "name": "Finance"
                }
                """.trimIndent()

            val resultJson = tool.execute(json)
            val obj = Json.parseToJsonElement(resultJson).jsonObject
            assertEquals("Personal", obj["parentPath"]!!.jsonPrimitive.content)
            assertEquals("Finance", obj["folderName"]!!.jsonPrimitive.content)
            assertTrue(fakeRepo.folders.contains("Personal/Finance"))
        }

    @Test
    fun setReminderTool_schedulesReminder() =
        runTest {
            val note = fakeRepo.createNote("", "Reminder Note", NoteType.NOTE)
            val tool = SetReminderTool(fakeAlarmScheduler)
            val json =
                """
                {
                  "noteId": "${note.id}",
                  "label": "Call accountant",
                  "firstTrigger": "2026-10-15T10:00:00Z",
                  "repeat": "WEEKLY",
                  "tier": "EXACT"
                }
                """.trimIndent()

            val resultJson = tool.execute(json)
            val obj = Json.parseToJsonElement(resultJson).jsonObject
            assertEquals(note.id, obj["noteId"]!!.jsonPrimitive.content)
            assertEquals("Call accountant", obj["label"]!!.jsonPrimitive.content)

            val scheduled = fakeAlarmScheduler.scheduled.firstOrNull()
            assertNotNull(scheduled)
            assertEquals(note.id, scheduled!!.reminder.noteId)
            assertEquals("Call accountant", scheduled.reminder.label)
            assertEquals(SchedulingTier.EXACT, scheduled.tier)
        }

    @Test
    fun mergeNotesTool_mergesViaUseCase() =
        runTest {
            val useCase = MergeNotesUseCase(fakeRepo)
            val tool = MergeNotesTool(useCase)

            val dest = fakeRepo.createNote("", "Dest", NoteType.NOTE)
            fakeRepo.edit(dest.id, "Dest body")
            val src = fakeRepo.createNote("", "Src", NoteType.NOTE)
            fakeRepo.edit(src.id, "Src body")

            val json =
                """
                {
                  "destinationNoteId": "${dest.id}",
                  "sourceNoteId": "${src.id}"
                }
                """.trimIndent()

            val resultJson = tool.execute(json)
            val obj = Json.parseToJsonElement(resultJson).jsonObject
            assertEquals(dest.id, obj["destinationNoteId"]!!.jsonPrimitive.content)

            assertTrue(fakeRepo.trashedIds.contains(src.id))
            val finalBody = fakeRepo.readBody(dest.id)
            assertTrue(finalBody.contains("# Dest\n\nDest body"))
            assertTrue(finalBody.contains("# Src\n\nSrc body"))
        }

    private class FakeAlarmScheduler : AlarmScheduler {
        data class ScheduledEntry(
            val reminder: Reminder,
            val tier: SchedulingTier,
        )

        val scheduled = mutableListOf<ScheduledEntry>()

        override suspend fun schedule(
            reminder: Reminder,
            tier: SchedulingTier,
        ) {
            scheduled += ScheduledEntry(reminder, tier)
        }

        override suspend fun cancel(reminderId: String) {
            scheduled.removeAll { it.reminder.id == reminderId }
        }
    }

    private class FakeNoteRepository : NoteRepository {
        val notes = mutableMapOf<String, Note>()
        val bodies = mutableMapOf<String, String>()
        val trashedIds = mutableSetOf<String>()
        val folders = mutableListOf<String>()

        private val notesFlow = MutableStateFlow<List<Note>>(emptyList())

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = notesFlow.asStateFlow()

        override fun observeAllNotes(): Flow<List<Note>> = notesFlow.asStateFlow()

        override suspend fun readBody(noteId: String): String = bodies[noteId].orEmpty()

        override suspend fun listFolders(): List<String> = folders.toList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) {
            val full = if (parentPath.isEmpty()) name else "$parentPath/$name"
            folders += full
        }

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note {
            val id = "note-${System.nanoTime()}"
            val note =
                Note(
                    id = id,
                    title = title,
                    type = type,
                    folderPath = folderPath,
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = Instant.now(),
                    modified = Instant.now(),
                    checksum = "",
                )
            notes[id] = note
            bodies[id] = ""
            notesFlow.value = notes.values.toList()
            return note
        }

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            bodies[noteId] = newBody
        }

        override suspend fun setTitle(
            noteId: String,
            newTitle: String,
        ) {
            val note = notes[noteId]
            if (note != null) {
                notes[noteId] = note.copy(title = newTitle)
                notesFlow.value = notes.values.toList()
            }
        }

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) = Unit

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) = Unit

        override suspend fun setTags(
            noteId: String,
            tags: List<String>,
        ) {
            val note = notes[noteId]
            if (note != null) {
                notes[noteId] = note.copy(tags = tags)
                notesFlow.value = notes.values.toList()
            }
        }

        override suspend fun moveNote(
            noteId: String,
            targetFolderPath: String,
        ) {
            val note = notes[noteId]
            if (note != null) {
                notes[noteId] = note.copy(folderPath = targetFolderPath)
                notesFlow.value = notes.values.toList()
            }
        }

        override suspend fun deleteNote(noteId: String) {
            notes.remove(noteId)
            trashedIds += noteId
            notesFlow.value = notes.values.toList()
        }

        override suspend fun getNote(noteId: String): Note? = notes[noteId]

        override suspend fun rescan(): com.locus.core.domain.notes.RescanReport =
            com.locus.core.domain.notes
                .RescanReport(0, 0, 0)
    }
}
