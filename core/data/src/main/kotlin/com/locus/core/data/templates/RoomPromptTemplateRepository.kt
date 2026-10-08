package com.locus.core.data.templates

import com.locus.core.domain.templates.PromptTemplate
import com.locus.core.domain.templates.PromptTemplateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomPromptTemplateRepository
    @Inject
    constructor(
        private val dao: PromptTemplateDao,
    ) : PromptTemplateRepository {
        override fun observeAll(): Flow<List<PromptTemplate>> =
            dao.observeAll().map { entities ->
                entities.map { it.toDomain() }
            }

        override suspend fun getById(id: String): PromptTemplate? = dao.getById(id)?.toDomain()

        override suspend fun upsert(template: PromptTemplate) {
            dao.upsert(PromptTemplateEntity.fromDomain(template))
        }

        override suspend fun delete(id: String) {
            dao.delete(id)
        }
    }
