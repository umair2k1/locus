package com.locus.core.data.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.SchedulingTier
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class AndroidAlarmScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val reminderDao: ReminderDao,
    ) : AlarmScheduler {
        override suspend fun schedule(
            reminder: Reminder,
            tier: SchedulingTier,
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val effectiveTier = determineEffectiveTier(tier, alarmManager)
            val triggerMillis = reminder.firstTrigger.toEpochMilli()

            val entity =
                ReminderEntity(
                    id = reminder.id,
                    noteId = reminder.noteId,
                    checklistLineIndex = reminder.checklistLineIndex,
                    label = reminder.label,
                    firstTrigger = reminder.firstTrigger,
                    repeat = reminder.repeat,
                    scheduledTier = effectiveTier,
                    active = true,
                )
            reminderDao.upsert(entity)

            when (effectiveTier) {
                SchedulingTier.EXACT -> {
                    scheduleExactAlarm(alarmManager, reminder, triggerMillis)
                }
                SchedulingTier.INEXACT_WINDOW -> {
                    scheduleInexactWindowAlarm(alarmManager, reminder, triggerMillis)
                }
                SchedulingTier.WORK_MANAGER -> {
                    scheduleWorkManager(reminder, triggerMillis)
                }
            }
        }

        override suspend fun cancel(reminderId: String) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            val pendingIntent = createPendingIntent(reminderId)
            if (alarmManager != null && pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }

            try {
                WorkManager.getInstance(context).cancelUniqueWork(workName(reminderId))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized during cancel", e)
            }
            reminderDao.deactivate(reminderId)
        }

        override suspend fun getExistingReminderIdsForNote(noteId: String): Set<String> =
            reminderDao.getRemindersByNoteId(noteId).map { it.id }.toSet()

        private fun determineEffectiveTier(
            requestedTier: SchedulingTier,
            alarmManager: AlarmManager?,
        ): SchedulingTier {
            if (alarmManager == null) return SchedulingTier.WORK_MANAGER

            return when (requestedTier) {
                SchedulingTier.EXACT -> {
                    if (canScheduleExact(alarmManager)) {
                        SchedulingTier.EXACT
                    } else {
                        SchedulingTier.INEXACT_WINDOW
                    }
                }
                SchedulingTier.INEXACT_WINDOW -> SchedulingTier.INEXACT_WINDOW
                SchedulingTier.WORK_MANAGER -> SchedulingTier.WORK_MANAGER
            }
        }

        open fun canScheduleExact(alarmManager: AlarmManager): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }

        private fun scheduleExactAlarm(
            alarmManager: AlarmManager?,
            reminder: Reminder,
            triggerMillis: Long,
        ) {
            val pendingIntent = createPendingIntent(reminder.id) ?: return
            try {
                alarmManager?.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerMillis,
                    pendingIntent,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm permission revoked; falling back to window", e)
                scheduleInexactWindowAlarm(alarmManager, reminder, triggerMillis)
            }
        }

        private fun scheduleInexactWindowAlarm(
            alarmManager: AlarmManager?,
            reminder: Reminder,
            triggerMillis: Long,
        ) {
            val pendingIntent = createPendingIntent(reminder.id) ?: return
            val windowLengthMillis = WINDOW_LENGTH_MILLIS
            val windowStartMillis = (triggerMillis - windowLengthMillis / 2).coerceAtLeast(0L)

            try {
                alarmManager?.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    windowStartMillis,
                    windowLengthMillis,
                    pendingIntent,
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "AlarmManager setWindow failed; falling back to WorkManager", e)
                scheduleWorkManager(reminder, triggerMillis)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "AlarmManager setWindow illegal argument; falling back to WorkManager", e)
                scheduleWorkManager(reminder, triggerMillis)
            }
        }

        private fun scheduleWorkManager(
            reminder: Reminder,
            triggerMillis: Long,
        ) {
            val now = System.currentTimeMillis()
            val initialDelayMillis = (triggerMillis - now).coerceAtLeast(0L)

            val inputData =
                Data.Builder().putString(ReminderWorker.KEY_REMINDER_ID, reminder.id).build()

            val request =
                OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
                    .setInputData(inputData)
                    .addTag(workTag(reminder.id))
                    .build()

            try {
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        workName(reminder.id),
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "WorkManager not initialized", e)
            }
        }

        private fun createPendingIntent(reminderId: String): PendingIntent? {
            val intent =
                Intent(ACTION_REMINDER_TRIGGER).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_REMINDER_ID, reminderId)
                }

            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }

            return PendingIntent.getBroadcast(
                context,
                reminderId.hashCode(),
                intent,
                flags,
            )
        }

        companion object {
            private const val TAG = "AndroidAlarmScheduler"
            const val ACTION_REMINDER_TRIGGER = "com.locus.app.ACTION_REMINDER_TRIGGER"
            const val EXTRA_REMINDER_ID = "reminder_id"
            const val WINDOW_LENGTH_MILLIS = 10 * 60 * 1000L

            fun workName(reminderId: String): String = "reminder_work_$reminderId"

            fun workTag(reminderId: String): String = "reminder_$reminderId"
        }
    }
