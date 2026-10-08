package com.locus.core.domain.reminders

enum class SchedulingTier {
    EXACT,
    INEXACT_WINDOW,
    WORK_MANAGER,
}

interface AlarmScheduler {
    suspend fun schedule(
        reminder: Reminder,
        tier: SchedulingTier,
    )

    suspend fun cancel(reminderId: String)

    suspend fun getExistingReminderIdsForNote(noteId: String): Set<String> = emptySet()
}
