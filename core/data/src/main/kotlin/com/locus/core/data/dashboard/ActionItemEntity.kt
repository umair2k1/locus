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

package com.locus.core.data.dashboard

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.locus.core.domain.dashboard.ActionItem

@Entity(
    tableName = "action_items",
    indices = [
        Index("computedAt"),
        Index("noteId"),
    ],
)
data class ActionItemEntity(
    @PrimaryKey
    val id: String,
    val cardId: String,
    val noteId: String,
    val noteTitle: String,
    val task: String,
    val computedAt: Long,
) {
    fun toDomain(): ActionItem =
        ActionItem(
            id = id,
            noteId = noteId,
            noteTitle = noteTitle,
            task = task,
        )

    companion object {
        fun fromDomain(
            item: ActionItem,
            cardId: String,
            computedAt: Long,
        ): ActionItemEntity =
            ActionItemEntity(
                id = item.id,
                cardId = cardId,
                noteId = item.noteId,
                noteTitle = item.noteTitle,
                task = item.task,
                computedAt = computedAt,
            )
    }
}
