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
interface ClusterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ClusterEntity>)

    @Query("SELECT * FROM clusters ORDER BY computedAt DESC")
    fun observeAll(): Flow<List<ClusterEntity>>

    @Query("SELECT * FROM clusters ORDER BY computedAt DESC")
    suspend fun getAll(): List<ClusterEntity>

    @Query("DELETE FROM clusters")
    suspend fun deleteAll()

    @Query("DELETE FROM clusters WHERE id = :id")
    suspend fun delete(id: String)
}
