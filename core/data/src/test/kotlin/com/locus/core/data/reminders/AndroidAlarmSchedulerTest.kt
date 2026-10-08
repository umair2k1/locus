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

package com.locus.core.data.reminders

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import com.locus.core.data.db.LocusDatabase
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AndroidAlarmSchedulerTest {
    private lateinit var context: Context
    private lateinit var database: LocusDatabase
    private lateinit var reminderDao: ReminderDao
    private lateinit var scheduler: AndroidAlarmScheduler
    private lateinit var shadowAlarmManager: ShadowAlarmManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        shadowAlarmManager = Shadows.shadowOf(alarmManager)

        database =
            Room
                .inMemoryDatabaseBuilder(
                    context,
                    LocusDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        reminderDao = database.reminderDao()

        scheduler =
            object : AndroidAlarmScheduler(context, reminderDao) {
                override fun canScheduleExact(alarmManager: AlarmManager): Boolean = true
            }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun scheduleExactWhenPermitted() =
        runTest {
            val reminder =
                Reminder(
                    id = "exact-1",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "Exact reminder",
                    firstTrigger = Instant.now().plusSeconds(3600),
                    repeat = RepeatRule.NONE,
                )

            scheduler.schedule(reminder, SchedulingTier.EXACT)

            val entity = reminderDao.getById("exact-1")
            assertNotNull(entity)
            assertEquals(SchedulingTier.EXACT, entity?.scheduledTier)
            assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())
        }

    @Test
    fun scheduleExactWhenNotPermittedDowngradesToInexact() =
        runTest {
            val inexactScheduler =
                object : AndroidAlarmScheduler(context, reminderDao) {
                    override fun canScheduleExact(alarmManager: AlarmManager): Boolean = false
                }

            val reminder =
                Reminder(
                    id = "exact-downgrade",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "Downgraded reminder",
                    firstTrigger = Instant.now().plusSeconds(3600),
                    repeat = RepeatRule.DAILY,
                )

            inexactScheduler.schedule(reminder, SchedulingTier.EXACT)

            val entity = reminderDao.getById("exact-downgrade")
            assertNotNull(entity)
            assertEquals(SchedulingTier.INEXACT_WINDOW, entity?.scheduledTier)
            assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())
        }

    @Test
    fun scheduleInexactWindowUsesWindow() =
        runTest {
            val reminder =
                Reminder(
                    id = "inexact-1",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "Inexact reminder",
                    firstTrigger = Instant.now().plusSeconds(3600),
                    repeat = RepeatRule.DAILY,
                )

            scheduler.schedule(reminder, SchedulingTier.INEXACT_WINDOW)

            val entity = reminderDao.getById("inexact-1")
            assertNotNull(entity)
            assertEquals(SchedulingTier.INEXACT_WINDOW, entity?.scheduledTier)
            assertTrue(shadowAlarmManager.scheduledAlarms.isNotEmpty())
        }

    @Test
    fun cancelRemovesAlarmAndDeactivates() =
        runTest {
            val reminder =
                Reminder(
                    id = "cancel-1",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "To cancel",
                    firstTrigger = Instant.now().plusSeconds(3600),
                    repeat = RepeatRule.NONE,
                )

            scheduler.schedule(reminder, SchedulingTier.EXACT)
            assertEquals(1, reminderDao.getActiveReminders().size)

            scheduler.cancel("cancel-1")

            val entity = reminderDao.getById("cancel-1")
            assertEquals(false, entity?.active)
            assertTrue(reminderDao.getActiveReminders().isEmpty())
        }
}
