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
import com.locus.core.domain.dashboard.DigestCard
import com.locus.core.domain.dashboard.DigestItem
import com.locus.core.domain.dashboard.DigestPeriod
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

@Entity(
    tableName = "digests",
    indices = [
        Index("period"),
        Index("computedAt"),
    ],
)
data class DigestEntity(
    @PrimaryKey
    val id: String,
    val period: String,
    val itemsJson: String,
    val overallSummary: String,
    val computedAt: Long,
) {
    fun toDomain(): DigestCard {
        val jsonArray = JSONArray(itemsJson)
        val items = mutableListOf<DigestItem>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            items.add(
                DigestItem(
                    noteId = obj.getString("noteId"),
                    noteTitle = obj.getString("noteTitle"),
                    summary = obj.getString("summary"),
                ),
            )
        }
        return DigestCard(
            id = id,
            period = DigestPeriod.valueOf(period),
            items = items,
            overallSummary = overallSummary,
            computedAt = Instant.ofEpochMilli(computedAt),
        )
    }

    companion object {
        fun fromDomain(card: DigestCard): DigestEntity {
            val jsonArray = JSONArray()
            for (item in card.items) {
                val obj = JSONObject()
                obj.put("noteId", item.noteId)
                obj.put("noteTitle", item.noteTitle)
                obj.put("summary", item.summary)
                jsonArray.put(obj)
            }
            return DigestEntity(
                id = card.id,
                period = card.period.name,
                itemsJson = jsonArray.toString(),
                overallSummary = card.overallSummary,
                computedAt = card.computedAt.toEpochMilli(),
            )
        }
    }
}
