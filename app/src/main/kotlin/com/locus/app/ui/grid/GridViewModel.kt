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

package com.locus.app.ui.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GridUiState(
    val notes: List<Note> = emptyList(),
    val loading: Boolean = true,
    val rootUri: String? = null,
)

@HiltViewModel
class GridViewModel
    @Inject
    constructor(
        private val repo: NoteRepository,
    ) : ViewModel() {
        val uiState: StateFlow<GridUiState> =
            combine(repo.observeAllNotes(), repo.observeRootUri()) { notes, rootUri ->
                GridUiState(notes = notes, loading = false, rootUri = rootUri)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = GridUiState(loading = true),
            )

        fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) {
            viewModelScope.launch { repo.setPinned(noteId, pinned) }
        }

        fun setColor(
            noteId: String,
            color: String?,
        ) {
            viewModelScope.launch { repo.setColor(noteId, color) }
        }

        fun setRootFolder(uriString: String) {
            viewModelScope.launch { repo.setRootUri(uriString) }
        }

        private companion object {
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
