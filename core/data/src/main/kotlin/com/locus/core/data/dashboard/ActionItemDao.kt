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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActionItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ActionItemEntity>)

    @Query("SELECT * FROM action_items ORDER BY computedAt DESC")
    fun observeAll(): Flow<List<ActionItemEntity>>

    @Query("SELECT * FROM action_items ORDER BY computedAt DESC")
    suspend fun getAll(): List<ActionItemEntity>

    @Query("DELETE FROM action_items")
    suspend fun deleteAll()

    @Query("DELETE FROM action_items WHERE id = :id")
    suspend fun delete(id: String)
}
