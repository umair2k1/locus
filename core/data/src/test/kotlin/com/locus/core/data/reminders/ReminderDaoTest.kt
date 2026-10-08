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

import androidx.room.Room
import com.locus.core.data.db.LocusDatabase
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class ReminderDaoTest {
    private lateinit var database: LocusDatabase
    private lateinit var reminderDao: ReminderDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    RuntimeEnvironment.getApplication(),
                    LocusDatabase::class.java,
                ).allowMainThreadQueries()
                .build()
        reminderDao = database.reminderDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertAndGetById() =
        runTest {
            val entity =
                ReminderEntity(
                    id = "rem-1",
                    noteId = "note-1",
                    checklistLineIndex = 2,
                    label = "Buy milk",
                    firstTrigger = Instant.parse("2026-04-01T09:00:00Z"),
                    repeat = RepeatRule.DAILY,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )

            reminderDao.upsert(entity)

            val retrieved = reminderDao.getById("rem-1")
            assertNotNull(retrieved)
            assertEquals("rem-1", retrieved?.id)
            assertEquals("Buy milk", retrieved?.label)
            assertEquals(RepeatRule.DAILY, retrieved?.repeat)
            assertEquals(SchedulingTier.EXACT, retrieved?.scheduledTier)
            assertTrue(retrieved?.active == true)
        }

    @Test
    fun getActiveRemindersAndDeactivate() =
        runTest {
            val rem1 =
                ReminderEntity(
                    id = "rem-1",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "Rem 1",
                    firstTrigger = Instant.parse("2026-04-01T09:00:00Z"),
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.INEXACT_WINDOW,
                    active = true,
                )
            val rem2 =
                ReminderEntity(
                    id = "rem-2",
                    noteId = "note-2",
                    checklistLineIndex = null,
                    label = "Rem 2",
                    firstTrigger = Instant.parse("2026-04-02T09:00:00Z"),
                    repeat = RepeatRule.WEEKLY,
                    scheduledTier = SchedulingTier.WORK_MANAGER,
                    active = false,
                )

            reminderDao.upsert(rem1)
            reminderDao.upsert(rem2)

            val active = reminderDao.getActiveReminders()
            assertEquals(1, active.size)
            assertEquals("rem-1", active[0].id)

            reminderDao.deactivate("rem-1")
            val activeAfter = reminderDao.getActiveReminders()
            assertTrue(activeAfter.isEmpty())

            val rem1Loaded = reminderDao.getById("rem-1")
            assertEquals(false, rem1Loaded?.active)
        }

    @Test
    fun observeActiveRemindersFlow() =
        runTest {
            val rem =
                ReminderEntity(
                    id = "rem-flow",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "Flow Reminder",
                    firstTrigger = Instant.parse("2026-04-01T09:00:00Z"),
                    repeat = RepeatRule.MONTHLY,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )

            reminderDao.upsert(rem)
            val list = reminderDao.observeActiveReminders().first()
            assertEquals(1, list.size)
            assertEquals("rem-flow", list[0].id)
        }

    @Test
    fun deleteById() =
        runTest {
            val rem =
                ReminderEntity(
                    id = "rem-del",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "To Delete",
                    firstTrigger = Instant.parse("2026-04-01T09:00:00Z"),
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            reminderDao.upsert(rem)
            assertNotNull(reminderDao.getById("rem-del"))

            reminderDao.deleteById("rem-del")
            assertNull(reminderDao.getById("rem-del"))
        }
}
