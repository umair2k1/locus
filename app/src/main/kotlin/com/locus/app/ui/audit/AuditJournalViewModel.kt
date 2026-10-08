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

package com.locus.app.ui.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.agent.AuditEntry
import com.locus.core.domain.agent.AuditJournal
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class AuditJournalViewModel
    @Inject
    constructor(
        private val auditJournal: AuditJournal,
    ) : ViewModel() {
        val entries: StateFlow<List<AuditEntry>> =
            auditJournal
                .observeEntries()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = emptyList(),
                )

        fun revert(entryId: String) {
            viewModelScope.launch {
                auditJournal.revert(entryId)
            }
        }

        fun exportJson(): String {
            val currentEntries = entries.value
            val array = JSONArray()
            for (e in currentEntries) {
                val obj = JSONObject()
                obj.put("id", e.id)
                obj.put("toolName", e.toolName)
                obj.put("argumentsJson", e.argumentsJson)
                val notesArray = JSONArray()
                e.affectedNoteIds.forEach { notesArray.put(it) }
                obj.put("affectedNoteIds", notesArray)
                obj.put("timestamp", e.timestamp)
                obj.put("modelId", e.modelId)
                obj.put("diff", e.diff)
                array.put(obj)
            }
            return array.toString(2)
        }
    }

private const val STOP_TIMEOUT_MILLIS = 5_000L
