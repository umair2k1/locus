package com.locus.core.data.dashboard

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.locus.core.domain.dashboard.ClusterCard
import org.json.JSONArray
import java.time.Instant

@Entity(
    tableName = "clusters",
    indices = [
        Index("computedAt"),
    ],
)
data class ClusterEntity(
    @PrimaryKey
    val id: String,
    val label: String,
    val noteIdsJson: String,
    val computedAt: Long,
) {
    fun toDomain(): ClusterCard {
        val jsonArray = JSONArray(noteIdsJson)
        val noteIds = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            noteIds.add(jsonArray.getString(i))
        }
        return ClusterCard(
            id = id,
            label = label,
            noteIds = noteIds,
            computedAt = Instant.ofEpochMilli(computedAt),
        )
    }

    companion object {
        fun fromDomain(card: ClusterCard): ClusterEntity {
            val jsonArray = JSONArray()
            for (noteId in card.noteIds) {
                jsonArray.put(noteId)
            }
            return ClusterEntity(
                id = card.id,
                label = card.label,
                noteIdsJson = jsonArray.toString(),
                computedAt = card.computedAt.toEpochMilli(),
            )
        }
    }
}
