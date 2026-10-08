package com.locus.core.data.dashboard

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DigestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DigestEntity)

    @Query("SELECT * FROM digests WHERE period = :period ORDER BY computedAt DESC LIMIT 1")
    fun observeLatestByPeriod(period: String): Flow<DigestEntity?>

    @Query("SELECT * FROM digests WHERE period = :period ORDER BY computedAt DESC LIMIT 1")
    suspend fun getLatestByPeriod(period: String): DigestEntity?

    @Query("SELECT * FROM digests ORDER BY computedAt DESC")
    fun observeAll(): Flow<List<DigestEntity>>

    @Query("DELETE FROM digests WHERE id = :id")
    suspend fun delete(id: String)
}
