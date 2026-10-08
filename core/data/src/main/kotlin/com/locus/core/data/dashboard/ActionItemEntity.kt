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
