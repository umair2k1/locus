package com.locus.core.domain.dashboard

import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.SchedulingTier
import com.locus.core.domain.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class DateTimePhraseParserTest {
    // 2026-10-08 is a Thursday
    private val baseInstant = Instant.parse("2026-10-08T08:00:00Z")
    private val utcZone = ZoneId.of("UTC")

    @Test
    fun parse_tomorrowAt3pm_returnsCorrectInstant() {
        val text = "Let's catch up tomorrow at 3pm for coffee."
        val results = DateTimePhraseParser.parse(text, baseInstant, utcZone)

        assertEquals(1, results.size)
        val match = results.first()
        assertEquals("tomorrow at 3pm", match.phrase.lowercase())

        // Tomorrow is Friday 2026-10-09 at 15:00 UTC
        val expected = ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, utcZone).toInstant()
        assertEquals(expected, match.instant)
    }

    @Test
    fun parse_nextFriday_returnsCorrectInstant() {
        val text = "Submit report next Friday before end of day."
        val results = DateTimePhraseParser.parse(text, baseInstant, utcZone)

        assertEquals(1, results.size)
        val match = results.first()
        assertEquals("next friday", match.phrase.lowercase())

        // From Thursday Oct 8, next Friday is Oct 9 at default 09:00 UTC
        val expected = ZonedDateTime.of(2026, 10, 9, 9, 0, 0, 0, utcZone).toInstant()
        assertEquals(expected, match.instant)
    }

    @Test
    fun parse_nextTuesdayAt10am_returnsCorrectInstant() {
        val text = "let's meet next Tuesday at 10am"
        val results = DateTimePhraseParser.parse(text, baseInstant, utcZone)

        assertEquals(1, results.size)
        val match = results.first()
        assertEquals("next tuesday at 10am", match.phrase.lowercase())

        // From Thursday Oct 8, next Tuesday is Oct 13 at 10:00 UTC
        val expected = ZonedDateTime.of(2026, 10, 13, 10, 0, 0, 0, utcZone).toInstant()
        assertEquals(expected, match.instant)
    }

    @Test
    fun parse_march5_returnsCorrectInstant() {
        val text = "Conference scheduled for March 5 in Boston."
        val results = DateTimePhraseParser.parse(text, baseInstant, utcZone)

        assertEquals(1, results.size)
        val match = results.first()
        assertEquals("March 5", match.phrase)

        // March 5 has passed in 2026 relative to Oct 8 -> rolls to 2027
        val expected = ZonedDateTime.of(2027, 3, 5, 9, 0, 0, 0, utcZone).toInstant()
        assertEquals(expected, match.instant)
    }

    @Test
    fun parse_negativeCase_noDatePhrase_returnsEmptyList() {
        val text = "Just reviewing standard engineering documentation without any temporal markers."
        val results = DateTimePhraseParser.parse(text, baseInstant, utcZone)

        assertTrue(results.isEmpty())
    }

    @Test
    fun parseRemindersUseCase_acceptance_dedupesCorrectly() =
        runTest {
            val fakeNoteRepo = FakeNoteRepository()
            val fakeScheduler = FakeAlarmScheduler()
            val clock =
                object : Clock {
                    override fun now(): Instant = baseInstant
                }
            val useCase = ParseRemindersUseCase(fakeNoteRepo, fakeScheduler, clock)

            val note =
                Note(
                    id = "note-1",
                    title = "Meeting notes",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = baseInstant,
                    modified = baseInstant,
                    checksum = "dummy",
                )
            fakeNoteRepo.notes["note-1"] = note
            fakeNoteRepo.bodies["note-1"] = "let's meet next Tuesday at 10am"

            // First run produces exactly one reminder linked to note-1
            val firstRunReminders = useCase.execute(listOf("note-1"))
            assertEquals(1, firstRunReminders.size)
            assertEquals("note-1", firstRunReminders.first().noteId)
            assertEquals(1, fakeScheduler.scheduled.size)

            // Re-running on unchanged note produces zero duplicate reminders
            val secondRunReminders = useCase.execute(listOf("note-1"))
            assertEquals(0, secondRunReminders.size)
            assertEquals(1, fakeScheduler.scheduled.size)
        }

    private class FakeAlarmScheduler : AlarmScheduler {
        val scheduled = mutableListOf<Reminder>()

        override suspend fun schedule(
            reminder: Reminder,
            tier: SchedulingTier,
        ) {
            scheduled.add(reminder)
        }

        override suspend fun cancel(reminderId: String) {
            scheduled.removeAll { it.id == reminderId }
        }

        override suspend fun getExistingReminderIdsForNote(noteId: String): Set<String> =
            scheduled.filter { it.noteId == noteId }.map { it.id }.toSet()
    }

    private class FakeNoteRepository : NoteRepository {
        val notes = mutableMapOf<String, Note>()
        val bodies = mutableMapOf<String, String>()

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

        override fun observeAllNotes(): Flow<List<Note>> = flowOf(notes.values.toList())

        override suspend fun getNote(id: String): Note? = notes[id]

        override suspend fun readBody(noteId: String): String = bodies[noteId].orEmpty()

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
        ): Note = error("Unused")

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
    }
}
