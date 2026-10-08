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
