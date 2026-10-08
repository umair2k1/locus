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
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class PermissionRevocationMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val reminderDao: ReminderDao,
        private val alarmScheduler: AlarmScheduler,
    ) {
        private val _downgradeEvents = MutableSharedFlow<List<String>>(replay = 1)
        val downgradeEvents: SharedFlow<List<String>> = _downgradeEvents.asSharedFlow()

        open fun canScheduleExactAlarms(): Boolean {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager?.canScheduleExactAlarms() == true
            } else {
                alarmManager != null
            }
        }

        suspend fun checkAndDowngrade(): List<String> {
            val affected = getAffectedReminders()
            if (affected.isEmpty()) return emptyList()

            val downgradedNames = mutableListOf<String>()
            for (entity in affected) {
                val reminder =
                    Reminder(
                        id = entity.id,
                        noteId = entity.noteId,
                        checklistLineIndex = entity.checklistLineIndex,
                        label = entity.label,
                        firstTrigger = entity.firstTrigger,
                        repeat = entity.repeat,
                    )
                // Re-schedule at lower tier
                alarmScheduler.schedule(reminder, SchedulingTier.INEXACT_WINDOW)
                downgradedNames.add(entity.label)
            }

            postDowngradeNotification(downgradedNames)
            _downgradeEvents.emit(downgradedNames)
            return downgradedNames
        }

        private suspend fun getAffectedReminders(): List<ReminderEntity> {
            if (canScheduleExactAlarms()) return emptyList()
            val activeReminders = reminderDao.getActiveReminders()
            return activeReminders.filter {
                it.repeat != RepeatRule.NONE && it.scheduledTier == SchedulingTier.EXACT
            }
        }

        private fun postDowngradeNotification(downgradedNames: List<String>) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return

            createNotificationChannel(notificationManager)

            val title = "Reminder schedule downgraded"
            val text = buildNotificationText(downgradedNames)

            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        }

        private fun buildNotificationText(names: List<String>): String =
            if (names.size == 1) {
                "Exact alarm permission was revoked. \"${names.first()}\" was moved to an approximate window."
            } else {
                val listStr = names.joinToString(", ")
                "Exact alarm permission was revoked. " +
                    "${names.size} reminders were moved to approximate windows: $listStr"
            }

        private fun createNotificationChannel(notificationManager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        "Reminder Alerts",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description =
                            "Alerts about reminder status changes and permission downgrades"
                    }
                notificationManager.createNotificationChannel(channel)
            }
        }

        companion object {
            const val CHANNEL_ID = "reminder_downgrades"
            const val NOTIFICATION_ID = 9001
        }
    }
