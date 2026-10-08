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

import android.content.Context
import com.locus.core.data.reminders.PermissionRevocationMonitor
import com.locus.core.data.reminders.ReminderDao
import com.locus.core.data.reminders.ReminderEntity
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    private val fakeDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
        }

    private val scheduledReminders = mutableListOf<Pair<Reminder, SchedulingTier>>()

    private val fakeAlarmScheduler =
        object : AlarmScheduler {
            override suspend fun schedule(
                reminder: Reminder,
                tier: SchedulingTier,
            ) {
                scheduledReminders.add(reminder to tier)
            }

            override suspend fun cancel(reminderId: String) {
                scheduledReminders.removeAll { it.first.id == reminderId }
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

            override fun observeActiveReminders() = throw UnsupportedOperationException()

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

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        scheduledReminders.clear()
    }

    @Test
    fun rearmAllRemindersSchedulesActiveReminders() =
        runTest(testDispatcher) {
            val monitor = PermissionRevocationMonitor(context, fakeDao, fakeAlarmScheduler)
            val receiver = BootReceiver()
            receiver.reminderDao = fakeDao
            receiver.alarmScheduler = fakeAlarmScheduler
            receiver.permissionRevocationMonitor = monitor
            receiver.dispatchers = fakeDispatchers

            val rem1 =
                ReminderEntity(
                    id = "rem-1",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "Reminder 1",
                    firstTrigger = Instant.parse("2026-04-01T10:00:00Z"),
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            val rem2 =
                ReminderEntity(
                    id = "rem-2",
                    noteId = "note-2",
                    checklistLineIndex = 1,
                    label = "Reminder 2",
                    firstTrigger = Instant.parse("2026-04-02T10:00:00Z"),
                    repeat = RepeatRule.DAILY,
                    scheduledTier = SchedulingTier.INEXACT_WINDOW,
                    active = true,
                )
            val remInactive =
                ReminderEntity(
                    id = "rem-3",
                    noteId = "note-3",
                    checklistLineIndex = null,
                    label = "Inactive",
                    firstTrigger = Instant.parse("2026-04-03T10:00:00Z"),
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.EXACT,
                    active = false,
                )

            fakeDao.upsert(rem1)
            fakeDao.upsert(rem2)
            fakeDao.upsert(remInactive)

            receiver.rearmAllReminders()

            assertEquals(2, scheduledReminders.size)
            assertEquals("rem-1", scheduledReminders[0].first.id)
            assertEquals(SchedulingTier.EXACT, scheduledReminders[0].second)
            assertEquals("rem-2", scheduledReminders[1].first.id)
            assertEquals(SchedulingTier.INEXACT_WINDOW, scheduledReminders[1].second)
        }
}
