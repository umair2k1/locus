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

package com.locus.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.templates.PromptTemplate
import com.locus.core.domain.templates.PromptTemplateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private const val STOP_TIMEOUT_MILLIS = 5_000L

@HiltViewModel
class PromptTemplatesViewModel
    @Inject
    constructor(
        private val repository: PromptTemplateRepository,
    ) : ViewModel() {
        val templates: StateFlow<List<PromptTemplate>> =
            repository
                .observeAll()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = emptyList(),
                )

        fun saveTemplate(
            id: String?,
            title: String,
            templateBody: String,
        ) {
            val templateId = id ?: UUID.randomUUID().toString()
            viewModelScope.launch {
                repository.upsert(
                    PromptTemplate(
                        id = templateId,
                        title = title.trim(),
                        templateBody = templateBody.trim(),
                    ),
                )
            }
        }

        fun deleteTemplate(id: String) {
            viewModelScope.launch {
                repository.delete(id)
            }
        }
    }
