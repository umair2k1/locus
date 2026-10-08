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

package com.locus.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.search.HybridSearchUseCase
import com.locus.core.domain.search.SearchResult
import com.locus.core.domain.search.SearchScope
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val degraded: Boolean = false,
    val scope: SearchScope = SearchScope(),
    val availableFolders: List<String> = emptyList(),
    val availableNotes: List<Note> = emptyList(),
    val isScopePickerVisible: Boolean = false,
)

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val hybridSearchUseCase: HybridSearchUseCase,
        private val noteRepository: NoteRepository,
        private val dispatchers: DispatcherProvider,
        savedStateHandle: SavedStateHandle? = null,
    ) : ViewModel() {
        private val initialNoteIds =
            savedStateHandle
                ?.get<String>("noteIds")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        private val initialScope = SearchScope(noteIds = initialNoteIds)
        private val queryFlow = MutableStateFlow("")
        private val scopeFlow = MutableStateFlow(initialScope)
        private val _uiState = MutableStateFlow(SearchUiState(scope = initialScope))
        val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

        init {
            viewModelScope.launch(dispatchers.io) {
                try {
                    val folders = noteRepository.listFolders()
                    _uiState.update { it.copy(availableFolders = folders) }
                } catch (_: Exception) {
                    // Non-fatal
                }
            }
            viewModelScope.launch {
                noteRepository.observeAllNotes().collectLatest { notes ->
                    _uiState.update { it.copy(availableNotes = notes) }
                }
            }
            viewModelScope.launch {
                @OptIn(FlowPreview::class)
                val debouncedQuery = queryFlow.debounce(DEBOUNCE_MILLIS).distinctUntilChanged()
                combine(debouncedQuery, scopeFlow) { query, scope -> Pair(query, scope) }
                    .collectLatest { (query, scope) ->
                        if (query.isBlank()) {
                            _uiState.update {
                                it.copy(
                                    results = emptyList(),
                                    degraded = false,
                                    isSearching = false,
                                )
                            }
                        } else {
                            _uiState.update { it.copy(isSearching = true) }
                            try {
                                val searchResult =
                                    withContext(dispatchers.io) {
                                        hybridSearchUseCase(query = query, scope = scope)
                                    }
                                _uiState.update {
                                    it.copy(
                                        results = searchResult.results,
                                        degraded = searchResult.degraded,
                                        isSearching = false,
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                _uiState.update {
                                    it.copy(
                                        results = emptyList(),
                                        degraded = false,
                                        isSearching = false,
                                    )
                                }
                            }
                        }
                    }
            }
        }

        fun onQueryChange(newQuery: String) {
            queryFlow.value = newQuery
            _uiState.update { it.copy(query = newQuery) }
            if (newQuery.isBlank()) {
                _uiState.update {
                    it.copy(
                        results = emptyList(),
                        degraded = false,
                        isSearching = false,
                    )
                }
            }
        }

        fun clearQuery() {
            onQueryChange("")
        }

        fun onScopeChange(newScope: SearchScope) {
            scopeFlow.value = newScope
            _uiState.update { it.copy(scope = newScope) }
        }

        fun setScopePickerVisible(visible: Boolean) {
            _uiState.update { it.copy(isScopePickerVisible = visible) }
        }

        fun clearScope() {
            onScopeChange(SearchScope())
        }

        fun rescan() {
            viewModelScope.launch(dispatchers.io) {
                _uiState.update { it.copy(isSearching = true) }
                try {
                    noteRepository.rescan()
                    val folders = noteRepository.listFolders()
                    _uiState.update { it.copy(availableFolders = folders) }
                    val currentQuery = queryFlow.value
                    val currentScope = scopeFlow.value
                    if (currentQuery.isNotBlank()) {
                        val searchResult =
                            hybridSearchUseCase(query = currentQuery, scope = currentScope)
                        _uiState.update {
                            it.copy(
                                results = searchResult.results,
                                degraded = searchResult.degraded,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Rescan failure is non-fatal to search UI
                } finally {
                    _uiState.update { it.copy(isSearching = false) }
                }
            }
        }

        private companion object {
            private const val DEBOUNCE_MILLIS = 300L
        }
    }
