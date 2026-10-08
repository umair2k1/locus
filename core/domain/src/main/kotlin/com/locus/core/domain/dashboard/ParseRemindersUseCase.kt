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

package com.locus.core.domain.dashboard

import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.reminders.Reminder
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import com.locus.core.domain.time.Clock
import kotlinx.coroutines.flow.first

class ParseRemindersUseCase(
    private val noteRepository: NoteRepository,
    private val alarmScheduler: AlarmScheduler,
    private val clock: Clock,
) {
    suspend fun execute(noteIds: List<String>? = null): List<Reminder> {
        val notes =
            if (noteIds != null) {
                noteIds.mapNotNull { noteRepository.getNote(it) }
            } else {
                noteRepository.observeAllNotes().first()
            }

        val baseInstant = clock.now()
        val createdReminders = mutableListOf<Reminder>()

        for (note in notes) {
            parseNoteReminders(note, baseInstant, createdReminders)
        }

        return createdReminders
    }

    private suspend fun parseNoteReminders(
        note: com.locus.core.domain.notes.Note,
        baseInstant: java.time.Instant,
        createdReminders: MutableList<Reminder>,
    ) {
        val body = noteRepository.readBody(note.id)
        if (body.isBlank()) return

        val parsedPhrases = DateTimePhraseParser.parse(body, baseInstant = baseInstant)
        if (parsedPhrases.isEmpty()) return

        val existingIds = alarmScheduler.getExistingReminderIdsForNote(note.id)
        for (parsed in parsedPhrases) {
            val reminderId = generateReminderId(note.id, parsed.startIndex)
            if (reminderId !in existingIds) {
                val reminder =
                    Reminder(
                        id = reminderId,
                        noteId = note.id,
                        checklistLineIndex = null,
                        label = parsed.phrase,
                        firstTrigger = parsed.instant,
                        repeat = RepeatRule.NONE,
                    )
                alarmScheduler.schedule(reminder, SchedulingTier.EXACT)
                createdReminders.add(reminder)
            }
        }
    }

    internal fun generateReminderId(
        noteId: String,
        startIndex: Int,
    ): String = "smart_${noteId}_$startIndex"
}
