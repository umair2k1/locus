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
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class ReminderReceiverTest {
    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    private val fakeDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
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
        ReminderChannels.createAll(context)
        fakeDao.items.clear()
    }

    @Test
    fun doesNotPostNotificationIfReminderNotFound() =
        runTest(testDispatcher) {
            val receiver = ReminderReceiver()
            receiver.reminderDao = fakeDao
            receiver.dispatchers = fakeDispatchers

            receiver.showNotificationFor(context, "non-existent")

            val nm =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as
                    NotificationManager
            val shadowNm = shadowOf(nm)
            assertEquals(0, shadowNm.allNotifications.size)
        }

    @Test
    fun doesNotPostNotificationIfReminderInactiveOrNotFound() =
        runTest(testDispatcher) {
            val receiver = ReminderReceiver()
            receiver.reminderDao = fakeDao
            receiver.dispatchers = fakeDispatchers

            fakeDao.upsert(
                ReminderEntity(
                    id = "rem-inactive",
                    noteId = "note-1",
                    checklistLineIndex = null,
                    label = "Inactive Reminder",
                    firstTrigger = Instant.now(),
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.EXACT,
                    active = false,
                ),
            )

            receiver.showNotificationFor(context, "rem-inactive")
            val nm =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as
                    NotificationManager
            val shadowNm = shadowOf(nm)
            assertEquals(0, shadowNm.allNotifications.size)
        }

    @Test
    fun postsGroupSummaryAndChildNotificationForActiveReminder() =
        runTest(testDispatcher) {
            val receiver = ReminderReceiver()
            receiver.reminderDao = fakeDao
            receiver.dispatchers = fakeDispatchers

            val entity =
                ReminderEntity(
                    id = "rem-1",
                    noteId = "note-123",
                    checklistLineIndex = 1,
                    label = "Buy groceries",
                    firstTrigger = Instant.parse("2026-04-01T10:00:00Z"),
                    repeat = RepeatRule.NONE,
                    scheduledTier = SchedulingTier.EXACT,
                    active = true,
                )
            fakeDao.upsert(entity)

            receiver.showNotificationFor(context, "rem-1")

            val nm =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as
                    NotificationManager
            val shadowNm = shadowOf(nm)
            assertEquals(2, shadowNm.allNotifications.size)

            val summary = shadowNm.getNotification(ReminderReceiver.SUMMARY_NOTIFICATION_ID)
            assertNotNull(summary)
            assertEquals(ReminderReceiver.GROUP_KEY_REMINDERS, summary.group)
            assertTrue((summary.flags and Notification.FLAG_GROUP_SUMMARY) != 0)

            val child = shadowNm.getNotification("rem-1".hashCode())
            assertNotNull(child)
            assertEquals(ReminderReceiver.GROUP_KEY_REMINDERS, child.group)
            assertEquals("Buy groceries", child.extras.getString(Notification.EXTRA_TITLE))
            assertNotNull(child.actions)
            assertEquals(2, child.actions.size)
            assertEquals("Complete", child.actions[0].title.toString())
            assertEquals("Snooze", child.actions[1].title.toString())
        }
}
