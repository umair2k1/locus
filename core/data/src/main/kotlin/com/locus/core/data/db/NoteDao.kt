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

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Upsert suspend fun upsert(entity: NoteIndexEntity)

    @Query("SELECT * FROM note_index WHERE id = :id")
    suspend fun getById(id: String): NoteIndexEntity?

    @Query("DELETE FROM note_index WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM note_index")
    fun observeAll(): Flow<List<NoteIndexEntity>>

    @Query("SELECT * FROM note_index WHERE folderPath = :path")
    fun observeByFolder(path: String): Flow<List<NoteIndexEntity>>

    @Query(
        """
        SELECT note_index.*
        FROM note_index
        JOIN note_fts ON note_index.rowid = note_fts.rowid
        WHERE note_fts MATCH :query
        """,
    )
    suspend fun ftsSearch(query: String): List<NoteIndexEntity>

    @RawQuery suspend fun ftsSearchScoped(query: SupportSQLiteQuery): List<NoteIndexEntity>
}
