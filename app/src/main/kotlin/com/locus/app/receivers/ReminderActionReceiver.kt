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

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.locus.core.data.reminders.ReminderDao
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.ReminderRecurrence
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.time.Clock
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {
    @Inject lateinit var reminderDao: ReminderDao

    @Inject lateinit var alarmScheduler: AlarmScheduler

    @Inject lateinit var noteRepository: NoteRepository

    @Inject lateinit var dispatchers: DispatcherProvider

    @Inject lateinit var clock: Clock

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, reminderId.hashCode())

        dismissNotification(context, notificationId)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            try {
                when (action) {
                    ACTION_COMPLETE -> handleComplete(reminderId)
                    ACTION_SNOOZE -> handleSnooze(reminderId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    @VisibleForTesting
    fun dismissNotification(
        context: Context,
        notificationId: Int,
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

        notificationManager.cancel(notificationId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val active = notificationManager.activeNotifications
            val remainingChildren =
                active.filter {
                    it.id != ReminderReceiver.SUMMARY_NOTIFICATION_ID &&
                        it.notification.group == ReminderReceiver.GROUP_KEY_REMINDERS
                }
            if (remainingChildren.isEmpty()) {
                notificationManager.cancel(ReminderReceiver.SUMMARY_NOTIFICATION_ID)
            }
        }
    }

    @VisibleForTesting
    suspend fun handleComplete(reminderId: String) {
        val entity = reminderDao.getById(reminderId) ?: return

        entity.checklistLineIndex?.let { targetIndex ->
            flipChecklistLine(entity.noteId, targetIndex)
        }

        if (entity.repeat == RepeatRule.NONE) {
            alarmScheduler.cancel(entity.id)
        } else {
            val nextTrigger = ReminderRecurrence.nextTrigger(entity.firstTrigger, entity.repeat)
            if (nextTrigger != null) {
                val nextReminder =
                    Reminder(
                        id = entity.id,
                        noteId = entity.noteId,
                        checklistLineIndex = entity.checklistLineIndex,
                        label = entity.label,
                        firstTrigger = nextTrigger,
                        repeat = entity.repeat,
                    )
                alarmScheduler.schedule(nextReminder, entity.scheduledTier)
            } else {
                alarmScheduler.cancel(entity.id)
            }
        }
    }

    @VisibleForTesting
    suspend fun handleSnooze(reminderId: String) {
        val entity = reminderDao.getById(reminderId) ?: return
        val snoozedTrigger = clock.now().plusSeconds(SNOOZE_MINUTES * SECONDS_PER_MINUTE)
        val snoozedReminder =
            Reminder(
                id = entity.id,
                noteId = entity.noteId,
                checklistLineIndex = entity.checklistLineIndex,
                label = entity.label,
                firstTrigger = snoozedTrigger,
                repeat = entity.repeat,
            )
        alarmScheduler.schedule(snoozedReminder, entity.scheduledTier)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun flipChecklistLine(
        noteId: String,
        targetIndex: Int,
    ) {
        try {
            val body = noteRepository.readBody(noteId)
            val lines = body.lines().toMutableList()
            if (targetIndex in lines.indices) {
                val line = lines[targetIndex]
                if (line.contains(UNCHECKED_BOX)) {
                    lines[targetIndex] = line.replaceFirst(UNCHECKED_BOX, CHECKED_BOX)
                    val updatedBody = lines.joinToString("\n")
                    noteRepository.edit(noteId, updatedBody)
                    noteRepository.forceFlush(noteId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to flip checklist line for note $noteId", e)
        }
    }

    companion object {
        const val ACTION_COMPLETE = "com.locus.app.ACTION_REMINDER_COMPLETE"
        const val ACTION_SNOOZE = "com.locus.app.ACTION_REMINDER_SNOOZE"
        const val EXTRA_REMINDER_ID = "reminder_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        const val SNOOZE_MINUTES = 15L
        private const val SECONDS_PER_MINUTE = 60L
        private const val UNCHECKED_BOX = "- [ ]"
        private const val CHECKED_BOX = "- [x]"
        private const val TAG = "ReminderActionReceiver"
    }
}
