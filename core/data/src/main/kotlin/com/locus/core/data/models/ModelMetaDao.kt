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

package com.locus.core.data.models

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelMetaDao {
    @Query("SELECT * FROM model_meta WHERE modelId = :modelId AND device = :device")
    suspend fun get(
        modelId: String,
        device: String,
    ): ModelMetaEntity?

    @Query("SELECT * FROM model_meta WHERE modelId = :modelId AND device = :device")
    fun observe(
        modelId: String,
        device: String,
    ): Flow<ModelMetaEntity?>

    @Query("SELECT * FROM model_meta WHERE device = :device")
    fun observeAll(device: String): Flow<List<ModelMetaEntity>>

    @Query("SELECT * FROM model_meta WHERE modelId = :modelId")
    suspend fun getAllForModel(modelId: String): List<ModelMetaEntity>

    @Query("SELECT * FROM model_meta")
    fun observeAll(): Flow<List<ModelMetaEntity>>

    @Upsert suspend fun upsert(entity: ModelMetaEntity)

    @Transaction
    suspend fun updateNotesAndRating(
        modelId: String,
        device: String,
        notes: String,
        rating: Int,
    ) {
        val existing = get(modelId, device)
        if (existing != null) {
            upsert(existing.copy(notes = notes, rating = rating))
        } else {
            upsert(
                ModelMetaEntity(
                    modelId = modelId,
                    device = device,
                    notes = notes,
                    rating = rating,
                ),
            )
        }
    }

    @Transaction
    suspend fun updateBenchmark(
        modelId: String,
        device: String,
        tokensPerSecond: Double,
        benchmarkedAt: Long,
    ) {
        val existing = get(modelId, device)
        if (existing != null) {
            upsert(
                existing.copy(
                    tokensPerSecond = tokensPerSecond,
                    benchmarkedAt = benchmarkedAt,
                ),
            )
        } else {
            upsert(
                ModelMetaEntity(
                    modelId = modelId,
                    device = device,
                    tokensPerSecond = tokensPerSecond,
                    benchmarkedAt = benchmarkedAt,
                ),
            )
        }
    }

    @Query("DELETE FROM model_meta WHERE modelId = :modelId")
    suspend fun deleteByModelId(modelId: String)

    @Query("DELETE FROM model_meta WHERE modelId = :modelId AND device = :device")
    suspend fun delete(
        modelId: String,
        device: String,
    )
}
