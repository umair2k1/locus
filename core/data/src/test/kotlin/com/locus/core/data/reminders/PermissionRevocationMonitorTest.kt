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

import android.content.Context
import androidx.room.Room
import com.locus.core.data.db.LocusDatabase
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PermissionRevocationMonitorTest {
    private lateinit var context: Context
    private lateinit var database: LocusDatabase
    private lateinit var reminderDao: ReminderDao
    private lateinit var scheduler: AndroidAlarmScheduler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()

        database =
            Room
                .inMemoryDatabaseBuilder(
                    context,
                    LocusDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        reminderDao = database.reminderDao()

        scheduler = AndroidAlarmScheduler(context, reminderDao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactPermissionRevokedDowngradesRepeatingReminders() =
        runTest {
            val monitor =
                object : PermissionRevocationMonitor(context, reminderDao, scheduler) {
                    override fun canScheduleExactAlarms(): Boolean = false
                }

            val repeatingExact =
                ReminderEntity(
                    id = "rep-1",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "Water plants",
                    firstTrigger = Instant.now().plusSeconds(3600),
                    repeat = RepeatRule.WEEKLY,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            val nonRepeatingExact =
                ReminderEntity(
                    id = "oneoff-1",
                    noteId = "note-2",
                    checklistLineIndex = null,
                    label = "Doctor appointment",
                    firstTrigger = Instant.now().plusSeconds(7200),
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )

            reminderDao.upsert(repeatingExact)
            reminderDao.upsert(nonRepeatingExact)

            val downgraded = monitor.checkAndDowngrade()
            assertEquals(listOf("Water plants"), downgraded)

            // Repeating reminder should now be INEXACT_WINDOW
            val reloadedRepeating = reminderDao.getById("rep-1")
            assertEquals(SchedulingTier.INEXACT_WINDOW, reloadedRepeating?.scheduledTier)

            // One-off reminder should remain untouched
            val reloadedOneOff = reminderDao.getById("oneoff-1")
            assertEquals(SchedulingTier.EXACT, reloadedOneOff?.scheduledTier)
        }

    @Test
    fun exactPermissionAllowedDoesNotDowngrade() =
        runTest {
            val monitor =
                object : PermissionRevocationMonitor(context, reminderDao, scheduler) {
                    override fun canScheduleExactAlarms(): Boolean = true
                }

            val repeatingExact =
                ReminderEntity(
                    id = "rep-2",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "Workout",
                    firstTrigger = Instant.now().plusSeconds(3600),
                    repeat = RepeatRule.DAILY,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            reminderDao.upsert(repeatingExact)

            val downgraded = monitor.checkAndDowngrade()
            assertTrue(downgraded.isEmpty())

            val reloaded = reminderDao.getById("rep-2")
            assertEquals(SchedulingTier.EXACT, reloaded?.scheduledTier)
        }
}
