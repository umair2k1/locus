package com.locus.core.data.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditEntryEntity)

    @Query("SELECT * FROM audit_entries ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<AuditEntryEntity>>

    @Query("SELECT * FROM audit_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AuditEntryEntity?

    @Query("SELECT * FROM audit_entries ORDER BY timestamp DESC")
    suspend fun getAll(): List<AuditEntryEntity>

    @Query("DELETE FROM audit_entries WHERE id = :id")
    suspend fun deleteById(id: String)
}
