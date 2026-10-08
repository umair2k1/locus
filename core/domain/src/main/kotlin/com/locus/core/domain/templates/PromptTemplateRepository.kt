package com.locus.core.domain.templates

import kotlinx.coroutines.flow.Flow

interface PromptTemplateRepository {
    fun observeAll(): Flow<List<PromptTemplate>>

    suspend fun getById(id: String): PromptTemplate?

    suspend fun upsert(template: PromptTemplate)

    suspend fun delete(id: String)
}
