package com.locus.core.data.reminders

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ReminderDao {
    @Upsert suspend fun upsert(entity: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: String): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE noteId = :noteId")
    suspend fun getRemindersByNoteId(noteId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE active = 1")
    suspend fun getActiveReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE active = 1")
    fun observeActiveReminders(): Flow<List<ReminderEntity>>

    @Query("UPDATE reminders SET active = 0 WHERE id = :id")
    suspend fun deactivate(id: String)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: String)
}
