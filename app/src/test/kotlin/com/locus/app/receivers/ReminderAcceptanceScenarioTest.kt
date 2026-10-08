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

package com.locus.app.receivers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.locus.app.notifications.ReminderChannels
import com.locus.core.data.reminders.ReminderDao
import com.locus.core.data.reminders.ReminderEntity
import com.locus.core.domain.notes.FlushTrigger
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
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
import org.junit.Assert.assertNotNull
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

/**
 * Verifies Acceptance criteria: "firing a reminder tied to a checklist line, tapping Complete,
 * reopening the note shows the line ticked; tapping Snooze on a second reminder re-fires it ~15
 * minutes later without duplicating the original recurring schedule."
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ReminderAcceptanceScenarioTest {
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val baseTime = Instant.parse("2026-04-01T09:00:00Z")

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
                // Replace any existing schedule for this id without duplicating
                scheduledReminders.removeAll { it.first.id == reminder.id }
                scheduledReminders.add(reminder to tier)
            }

            override suspend fun cancel(reminderId: String) {
                scheduledReminders.removeAll { it.first.id == reminderId }
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
                error("Not needed in test")
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

    private fun createReminderReceiver(): ReminderReceiver =
        ReminderReceiver().apply {
            reminderDao = fakeDao
            dispatchers = fakeDispatchers
        }

    private fun createActionReceiver(): ReminderActionReceiver =
        ReminderActionReceiver().apply {
            reminderDao = fakeDao
            alarmScheduler = fakeAlarmScheduler
            noteRepository = fakeNoteRepo
            dispatchers = fakeDispatchers
            clock = fakeClock
        }

    @Test
    fun completeFiredReminderTiedToChecklistLine_ticksLineWhenNoteReopened() =
        runTest(testDispatcher) {
            val reminderReceiver = createReminderReceiver()
            val actionReceiver = createActionReceiver()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val shadowNm = shadowOf(nm)

            // Given a source note with a checklist line at index 1
            val initialNoteContent =
                """
                # Morning Groceries
                - [ ] 2L Oat Milk
                - [ ] Sourdough Bread
                """.trimIndent()
            val noteId = "groceries-note-1"
            fakeNoteRepo.bodies[noteId] = initialNoteContent

            // And a scheduled reminder tied to checklistLineIndex = 1
            val reminderId = "rem-milk"
            val reminder =
                ReminderEntity(
                    id = reminderId,
                    noteId = noteId,
                    checklistLineIndex = 1,
                    label = "2L Oat Milk",
                    firstTrigger = baseTime,
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            fakeDao.upsert(reminder)

            // When: 1. Firing the reminder (alarm manager / worker trigger)
            reminderReceiver.showNotificationFor(context, reminderId)

            // Then: Grouped notification is posted (summary + child with actions)
            val summaryNotification =
                shadowNm.getNotification(ReminderReceiver.SUMMARY_NOTIFICATION_ID)
            assertNotNull(summaryNotification)
            val childNotification = shadowNm.getNotification(reminderId.hashCode())
            assertNotNull(childNotification)
            assertEquals("2L Oat Milk", childNotification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Complete", childNotification.actions[0].title.toString())
            assertEquals("Snooze", childNotification.actions[1].title.toString())

            // When: 2. Tapping Complete
            actionReceiver.dismissNotification(context, reminderId.hashCode())
            actionReceiver.handleComplete(reminderId)

            // Then: 3. Reopening the note shows the line ticked (`- [x]`)
            val reopenedNote = fakeNoteRepo.readBody(noteId)
            val expectedNoteContent =
                """
                # Morning Groceries
                - [x] 2L Oat Milk
                - [ ] Sourdough Bread
                """.trimIndent()
            assertEquals(expectedNoteContent, reopenedNote)
            assertTrue(fakeNoteRepo.flushedNotes.contains(noteId))

            // And the one-off reminder is canceled, child and summary notifications dismissed
            assertTrue(canceledReminderIds.contains(reminderId))
            assertNull(shadowNm.getNotification(reminderId.hashCode()))
            assertNull(shadowNm.getNotification(ReminderReceiver.SUMMARY_NOTIFICATION_ID))
        }

    @Test
    fun snoozeSecondReminder_refires15MinutesLaterWithoutDuplicatingRecurringSchedule() =
        runTest(testDispatcher) {
            val reminderReceiver = createReminderReceiver()
            val actionReceiver = createActionReceiver()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val shadowNm = shadowOf(nm)

            // Given a second recurring reminder (e.g. DAILY)
            val reminderId = "rem-daily-workout"
            val reminder =
                ReminderEntity(
                    id = reminderId,
                    noteId = "workout-note",
                    checklistLineIndex = null,
                    label = "Daily Workout",
                    firstTrigger = baseTime,
                    repeat = RepeatRule.DAILY,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            fakeDao.upsert(reminder)

            // When: 1. Firing the reminder
            reminderReceiver.showNotificationFor(context, reminderId)
            assertNotNull(shadowNm.getNotification(reminderId.hashCode()))

            // When: 2. Tapping Snooze on the notification
            actionReceiver.dismissNotification(context, reminderId.hashCode())
            actionReceiver.handleSnooze(reminderId)

            // Then: Re-fires ~15 minutes later
            val expectedSnoozeTrigger = baseTime.plusSeconds(15 * 60)
            assertEquals(1, scheduledReminders.size)
            val (snoozedSchedule, tier) = scheduledReminders.first()

            assertEquals(reminderId, snoozedSchedule.id)
            assertEquals(expectedSnoozeTrigger, snoozedSchedule.firstTrigger)

            // And without duplicating the original recurring schedule:
            // The underlying RepeatRule is preserved as DAILY and exact same ID
            assertEquals(RepeatRule.DAILY, snoozedSchedule.repeat)
            assertEquals(SchedulingTier.EXACT, tier)

            // And notification is dismissed
            assertNull(shadowNm.getNotification(reminderId.hashCode()))
            assertNull(shadowNm.getNotification(ReminderReceiver.SUMMARY_NOTIFICATION_ID))
        }
}
