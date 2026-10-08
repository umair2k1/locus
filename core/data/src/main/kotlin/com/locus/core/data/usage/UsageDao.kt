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

package com.locus.core.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** P-5: DAO for recording and querying token usage records. */
@Dao
interface UsageDao {
    @Insert suspend fun insert(entity: UsageEntity): Long

    @Query(
        "SELECT * FROM token_usage WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp ASC",
    )
    fun observeUsageBetween(
        startTime: Long,
        endTime: Long,
    ): Flow<List<UsageEntity>>

    @Query(
        "SELECT * FROM token_usage WHERE timestamp >= :startTime AND timestamp < :endTime ORDER BY timestamp ASC",
    )
    suspend fun getUsageBetween(
        startTime: Long,
        endTime: Long,
    ): List<UsageEntity>

    @Query("SELECT * FROM token_usage ORDER BY timestamp ASC")
    fun observeAllUsage(): Flow<List<UsageEntity>>

    @Query("SELECT * FROM token_usage ORDER BY timestamp ASC")
    suspend fun getAllUsage(): List<UsageEntity>

    @Query("DELETE FROM token_usage")
    suspend fun clearAll()
}
