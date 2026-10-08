package com.locus.app.receivers

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.locus.app.notifications.ReminderChannels
import com.locus.core.data.reminders.ReminderDao
import com.locus.core.data.reminders.ReminderEntity
import com.locus.core.domain.notes.FlushTrigger
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.ReminderRecurrence
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import com.locus.core.domain.time.Clock
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ReminderActionReceiverTest {
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val baseTime = Instant.parse("2026-04-01T10:00:00Z")

    private val fakeClock = Clock { baseTime }

    private val fakeDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
        }

    private val scheduledReminders = mutableListOf<Pair<Reminder, SchedulingTier>>()
    private val canceledReminderIds = mutableListOf<String>()

    private val fakeAlarmScheduler =
        object : AlarmScheduler {
            override suspend fun schedule(
                reminder: Reminder,
                tier: SchedulingTier,
            ) {
                scheduledReminders.add(reminder to tier)
            }

            override suspend fun cancel(reminderId: String) {
                canceledReminderIds.add(reminderId)
            }
        }

    private val fakeDao =
        object : ReminderDao {
            val items = mutableListOf<ReminderEntity>()

            override suspend fun upsert(entity: ReminderEntity) {
                items.removeAll { it.id == entity.id }
                items.add(entity)
            }

            override suspend fun getById(id: String): ReminderEntity? = items.find { it.id == id }

            override suspend fun getRemindersByNoteId(noteId: String): List<ReminderEntity> {
                val matched = items.filter { it.noteId == noteId }
                return matched
            }

            override suspend fun getActiveReminders(): List<ReminderEntity> = items.filter { it.active }

            override fun observeActiveReminders(): Flow<List<ReminderEntity>> = throw UnsupportedOperationException()

            override suspend fun deactivate(id: String) {
                val idx = items.indexOfFirst { it.id == id }
                if (idx != -1) {
                    items[idx] = items[idx].copy(active = false)
                }
            }

            override suspend fun deleteById(id: String) {
                items.removeAll { it.id == id }
            }
        }

    private val fakeNoteRepo =
        object : NoteRepository {
            val bodies = mutableMapOf<String, String>()
            val flushedNotes = mutableListOf<String>()

            override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> {
                error("Not supported in test")
            }

            override fun observeAllNotes(): Flow<List<Note>> = throw UnsupportedOperationException()

            override suspend fun readBody(noteId: String): String = bodies[noteId] ?: ""

            override suspend fun listFolders(): List<String> = emptyList()

            override suspend fun createFolder(
                parentPath: String,
                name: String,
            ) {
                // No-op for test
            }

            override suspend fun createNote(
                folderPath: String,
                title: String,
                type: NoteType,
            ): Note = throw UnsupportedOperationException()

            override suspend fun edit(
                noteId: String,
                newBody: String,
            ) {
                bodies[noteId] = newBody
            }

            override suspend fun setPinned(
                noteId: String,
                pinned: Boolean,
            ) {
                // No-op for test
            }

            override suspend fun setColor(
                noteId: String,
                color: String?,
            ) {
                // No-op for test
            }

            override suspend fun rescan() = throw UnsupportedOperationException()

            override suspend fun forceFlush(
                noteId: String,
                trigger: FlushTrigger,
            ) {
                flushedNotes.add(noteId)
            }
        }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ReminderChannels.createAll(context)
        fakeDao.items.clear()
        scheduledReminders.clear()
        canceledReminderIds.clear()
        fakeNoteRepo.bodies.clear()
        fakeNoteRepo.flushedNotes.clear()
    }

    private fun createReceiver(): ReminderActionReceiver =
        ReminderActionReceiver().apply {
            reminderDao = fakeDao
            alarmScheduler = fakeAlarmScheduler
            noteRepository = fakeNoteRepo
            dispatchers = fakeDispatchers
            clock = fakeClock
        }

    @Test
    fun handleCompleteFlipsChecklistLineAndCancelsNoneReminder() =
        runTest(testDispatcher) {
            val receiver = createReceiver()

            val initialBody =
                """
                # Morning Tasks
                - [ ] Buy milk
                - [ ] Walk dog
                """.trimIndent()
            fakeNoteRepo.bodies["note-1"] = initialBody

            val entity =
                ReminderEntity(
                    id = "rem-1",
                    noteId = "note-1",
                    checklistLineIndex = 1,
                    label = "Buy milk",
                    firstTrigger = baseTime,
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            fakeDao.upsert(entity)

            receiver.handleComplete("rem-1")

            val updatedBody = fakeNoteRepo.bodies["note-1"]
            val expectedBody =
                """
                # Morning Tasks
                - [x] Buy milk
                - [ ] Walk dog
                """.trimIndent()
            assertEquals(expectedBody, updatedBody)
            assertTrue(fakeNoteRepo.flushedNotes.contains("note-1"))
            assertTrue(canceledReminderIds.contains("rem-1"))
            assertEquals(0, scheduledReminders.size)
        }

    @Test
    fun handleCompleteReschedulesRecurringReminder() =
        runTest(testDispatcher) {
            val receiver = createReceiver()

            val entity =
                ReminderEntity(
                    id = "rem-daily",
                    noteId = "note-2",
                    checklistLineIndex = null,
                    label = "Daily Standup",
                    firstTrigger = baseTime,
                    repeat = RepeatRule.DAILY,
                    scheduledTier = SchedulingTier.INEXACT_WINDOW,
                    active = true,
                )
            fakeDao.upsert(entity)

            receiver.handleComplete("rem-daily")

            assertEquals(0, canceledReminderIds.size)
            assertEquals(1, scheduledReminders.size)

            val (rescheduled, tier) = scheduledReminders.first()
            assertEquals("rem-daily", rescheduled.id)
            val expectedNext = ReminderRecurrence.nextTrigger(baseTime, RepeatRule.DAILY)
            assertEquals(expectedNext, rescheduled.firstTrigger)
            assertEquals(RepeatRule.DAILY, rescheduled.repeat)
            assertEquals(SchedulingTier.INEXACT_WINDOW, tier)
        }

    @Test
    fun handleCompleteLeavesNoteUntouchedWhenChecklistLineIndexNull() =
        runTest(testDispatcher) {
            val receiver = createReceiver()

            fakeNoteRepo.bodies["note-plain"] = "Plain note body without checklist"

            val entity =
                ReminderEntity(
                    id = "rem-plain",
                    noteId = "note-plain",
                    checklistLineIndex = null,
                    label = "General reminder",
                    firstTrigger = baseTime,
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            fakeDao.upsert(entity)

            receiver.handleComplete("rem-plain")

            assertEquals("Plain note body without checklist", fakeNoteRepo.bodies["note-plain"])
            assertEquals(0, fakeNoteRepo.flushedNotes.size)
            assertTrue(canceledReminderIds.contains("rem-plain"))
        }

    @Test
    fun handleSnoozeReschedulesPlus15MinutesWithoutAlteringRepeatRule() =
        runTest(testDispatcher) {
            val receiver = createReceiver()

            val entity =
                ReminderEntity(
                    id = "rem-snooze",
                    noteId = "note-3",
                    checklistLineIndex = 2,
                    label = "Pay bills",
                    firstTrigger = baseTime,
                    repeat = RepeatRule.WEEKLY,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            fakeDao.upsert(entity)

            receiver.handleSnooze("rem-snooze")

            assertEquals(1, scheduledReminders.size)
            val (snoozed, tier) = scheduledReminders.first()
            assertEquals("rem-snooze", snoozed.id)
            val expectedSnoozeTime = baseTime.plusSeconds(15 * 60)
            assertEquals(expectedSnoozeTime, snoozed.firstTrigger)
            assertEquals(RepeatRule.WEEKLY, snoozed.repeat)
            assertEquals(2, snoozed.checklistLineIndex)
            assertEquals(SchedulingTier.EXACT, tier)
        }

    @Test
    fun dismissNotificationCancelsChildAndCancelsSummaryIfNoChildrenRemain() {
        val receiver = createReceiver()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNm = shadowOf(nm)

        val summaryNotification =
            NotificationCompat
                .Builder(context, ReminderChannels.CHANNEL_REMINDERS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Reminders")
                .setGroup(ReminderReceiver.GROUP_KEY_REMINDERS)
                .setGroupSummary(true)
                .build()
        val childNotification =
            NotificationCompat
                .Builder(context, ReminderChannels.CHANNEL_REMINDERS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Task")
                .setGroup(ReminderReceiver.GROUP_KEY_REMINDERS)
                .build()

        nm.notify(ReminderReceiver.SUMMARY_NOTIFICATION_ID, summaryNotification)
        nm.notify(101, childNotification)

        assertEquals(2, shadowNm.allNotifications.size)

        receiver.dismissNotification(context, 101)

        assertNull(shadowNm.getNotification(101))
        assertNull(shadowNm.getNotification(ReminderReceiver.SUMMARY_NOTIFICATION_ID))
    }
}
