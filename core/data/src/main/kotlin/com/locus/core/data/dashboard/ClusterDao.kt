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
