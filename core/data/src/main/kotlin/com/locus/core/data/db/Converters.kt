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

package com.locus.core.data.db

import androidx.room.TypeConverter
import com.locus.core.domain.chat.ChatRole
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.reminders.RepeatRule
import com.locus.core.domain.reminders.SchedulingTier
import java.time.Instant

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter fun toTimestamp(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter fun fromNoteType(value: String?): NoteType? = value?.let { NoteType.valueOf(it) }

    @TypeConverter fun toNoteType(noteType: NoteType?): String? = noteType?.name

    @TypeConverter
    fun fromStringList(value: String?): List<String> {
        if (value.isNullOrEmpty()) return emptyList()
        return value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    @TypeConverter fun toStringList(list: List<String>?): String = list?.joinToString(",") ?: ""

    @TypeConverter
    fun fromRepeatRule(value: String?): RepeatRule? = value?.let { RepeatRule.valueOf(it) }

    @TypeConverter fun toRepeatRule(rule: RepeatRule?): String? = rule?.name

    @TypeConverter
    fun fromSchedulingTier(value: String?): SchedulingTier? = value?.let { SchedulingTier.valueOf(it) }

    @TypeConverter fun toSchedulingTier(tier: SchedulingTier?): String? = tier?.name

    @TypeConverter fun fromChatRole(value: String?): ChatRole? = value?.let { ChatRole.valueOf(it) }

    @TypeConverter fun toChatRole(role: ChatRole?): String? = role?.name
}
