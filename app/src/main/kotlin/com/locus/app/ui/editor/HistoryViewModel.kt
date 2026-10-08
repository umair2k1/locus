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

package com.locus.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.notes.FlushTrigger
import com.locus.core.domain.notes.HistoryRevision
import com.locus.core.domain.notes.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val revisions: List<HistoryRevision> = emptyList(),
    val selectedRevision: HistoryRevision? = null,
    val isLoading: Boolean = false,
)

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        private val repo: NoteRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(HistoryUiState())
        val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

        fun loadRevisions(noteId: String) {
            if (noteId.isEmpty() || noteId == "new") {
                _uiState.update {
                    it.copy(
                        revisions = emptyList(),
                        selectedRevision = null,
                        isLoading = false,
                    )
                }
                return
            }
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val revisions = repo.listRevisions(noteId)
                _uiState.update {
                    it.copy(
                        revisions = revisions,
                        selectedRevision = revisions.firstOrNull(),
                        isLoading = false,
                    )
                }
            }
        }

        fun selectRevision(revision: HistoryRevision) {
            _uiState.update { it.copy(selectedRevision = revision) }
        }

        fun restoreRevision(
            noteId: String,
            revision: HistoryRevision,
            onRestored: () -> Unit = {},
        ) {
            viewModelScope.launch {
                repo.edit(noteId, revision.body)
                repo.forceFlush(noteId, FlushTrigger.EDITOR_CLOSE)
                onRestored()
            }
        }
    }
