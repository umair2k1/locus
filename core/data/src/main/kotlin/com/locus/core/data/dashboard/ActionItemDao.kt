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
