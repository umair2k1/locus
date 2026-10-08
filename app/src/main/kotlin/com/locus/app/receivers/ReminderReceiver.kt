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
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.locus.app.MainActivity
import com.locus.app.notifications.ReminderChannels
import com.locus.core.data.reminders.AndroidAlarmScheduler
import com.locus.core.data.reminders.ReminderDao
import com.locus.core.data.reminders.ReminderEntity
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {
    @Inject lateinit var reminderDao: ReminderDao

    @Inject lateinit var dispatchers: DispatcherProvider

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != AndroidAlarmScheduler.ACTION_REMINDER_TRIGGER) return
        val reminderId = intent.getStringExtra(AndroidAlarmScheduler.EXTRA_REMINDER_ID) ?: return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            try {
                showNotificationFor(context, reminderId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    @VisibleForTesting
    suspend fun showNotificationFor(
        context: Context,
        reminderId: String,
    ) {
        val entity = reminderDao.getById(reminderId) ?: return
        if (!entity.active) return
        postReminderNotification(context, entity)
    }

    @VisibleForTesting
    fun postReminderNotification(
        context: Context,
        entity: ReminderEntity,
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

        val summaryNotification =
            NotificationCompat
                .Builder(context, ReminderChannels.CHANNEL_REMINDERS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(SUMMARY_TITLE)
                .setStyle(NotificationCompat.InboxStyle().setSummaryText(SUMMARY_TITLE))
                .setGroup(GROUP_KEY_REMINDERS)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

        val notificationId = entity.id.hashCode()

        val completeIntent =
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_COMPLETE
                putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, entity.id)
                putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
        val completePendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId * REQUEST_CODE_MULTIPLIER + COMPLETE_REQUEST_CODE_OFFSET,
                completeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val snoozeIntent =
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_SNOOZE
                putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, entity.id)
                putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            }
        val snoozePendingIntent =
            PendingIntent.getBroadcast(
                context,
                notificationId * REQUEST_CODE_MULTIPLIER + SNOOZE_REQUEST_CODE_OFFSET,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val contentIntent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NOTE_ID, entity.noteId)
            }
        val contentPendingIntent =
            PendingIntent.getActivity(
                context,
                notificationId,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val childNotification =
            NotificationCompat
                .Builder(context, ReminderChannels.CHANNEL_REMINDERS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(entity.label.ifBlank { DEFAULT_REMINDER_TITLE })
                .setContentText(entity.label)
                .setGroup(GROUP_KEY_REMINDERS)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(contentPendingIntent)
                .addAction(
                    0,
                    ACTION_LABEL_COMPLETE,
                    completePendingIntent,
                ).addAction(
                    0,
                    ACTION_LABEL_SNOOZE,
                    snoozePendingIntent,
                ).build()

        notificationManager.notify(SUMMARY_NOTIFICATION_ID, summaryNotification)
        notificationManager.notify(notificationId, childNotification)
    }

    companion object {
        const val GROUP_KEY_REMINDERS = "com.locus.app.REMINDERS"
        const val SUMMARY_NOTIFICATION_ID = 1000
        const val EXTRA_NOTE_ID = "note_id"

        private const val SUMMARY_TITLE = "Reminders"
        private const val DEFAULT_REMINDER_TITLE = "Reminder"
        private const val ACTION_LABEL_COMPLETE = "Complete"
        private const val ACTION_LABEL_SNOOZE = "Snooze"

        private const val REQUEST_CODE_MULTIPLIER = 31
        private const val COMPLETE_REQUEST_CODE_OFFSET = 1
        private const val SNOOZE_REQUEST_CODE_OFFSET = 2
    }
}
