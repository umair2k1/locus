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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import com.locus.core.data.reminders.PermissionRevocationMonitor
import com.locus.core.data.reminders.ReminderDao
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var reminderDao: ReminderDao

    @Inject lateinit var alarmScheduler: AlarmScheduler

    @Inject lateinit var permissionRevocationMonitor: PermissionRevocationMonitor

    @Inject lateinit var dispatchers: DispatcherProvider

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            try {
                rearmAllReminders()
            } finally {
                pendingResult.finish()
            }
        }
    }

    @VisibleForTesting
    suspend fun rearmAllReminders() {
        // First, check if exact permission was revoked across boot/update
        permissionRevocationMonitor.checkAndDowngrade()

        // Re-arm all active reminders
        val active = reminderDao.getActiveReminders()
        for (entity in active) {
            val reminder =
                Reminder(
                    id = entity.id,
                    noteId = entity.noteId,
                    checklistLineIndex = entity.checklistLineIndex,
                    label = entity.label,
                    firstTrigger = entity.firstTrigger,
                    repeat = entity.repeat,
                )
            alarmScheduler.schedule(reminder, entity.scheduledTier)
        }
    }
}
