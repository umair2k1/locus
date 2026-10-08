package com.locus.core.data.templates

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PromptTemplateDao {
    @Query("SELECT * FROM prompt_templates ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PromptTemplateEntity>>

    @Query("SELECT * FROM prompt_templates WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PromptTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PromptTemplateEntity)

    @Query("DELETE FROM prompt_templates WHERE id = :id")
    suspend fun delete(id: String)
}
