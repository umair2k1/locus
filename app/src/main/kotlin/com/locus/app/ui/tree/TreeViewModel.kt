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

package com.locus.app.ui.tree

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderNode(
    val name: String,
    val path: String,
    val children: List<FolderNode> = emptyList(),
)

data class TreeUiState(
    val tree: FolderNode = FolderNode(name = "Notes", path = "", children = emptyList()),
    val selectedPath: String? = null,
    val notesInSelected: List<Note> = emptyList(),
    val expandedPaths: Set<String> = emptySet(),
    val loading: Boolean = false,
)

fun buildFolderTree(
    flatPaths: List<String>,
    rootName: String = "Notes",
): FolderNode {
    val cleanPaths =
        flatPaths
            .map { it.trim().trim('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

    fun buildChildren(
        currentParentPath: String,
        subPaths: List<String>,
    ): List<FolderNode> {
        val groups = subPaths.groupBy { it.substringBefore('/') }
        return groups
            .map { (segment, fullSubPaths) ->
                val childPath = if (currentParentPath.isEmpty()) segment else "$currentParentPath/$segment"
                val remainingSubPaths =
                    fullSubPaths.mapNotNull { path ->
                        if (path == segment) null else path.substringAfter('/')
                    }
                FolderNode(
                    name = segment,
                    path = childPath,
                    children = buildChildren(childPath, remainingSubPaths),
                )
            }.sortedBy { it.name.lowercase() }
    }

    return FolderNode(
        name = rootName,
        path = "",
        children = buildChildren("", cleanPaths),
    )
}

@HiltViewModel
class TreeViewModel
    @Inject
    constructor(
        private val repo: NoteRepository,
    ) : ViewModel() {
        private val treeFlow =
            MutableStateFlow(FolderNode(name = "Notes", path = "", children = emptyList()))
        private val selectedPathFlow = MutableStateFlow<String?>(null)
        private val expandedPathsFlow = MutableStateFlow<Set<String>>(emptySet())
        private val loadingFlow = MutableStateFlow(true)
        private val notesInSelectedFlow = MutableStateFlow<List<Note>>(emptyList())
        private var notesJob: Job? = null

        val uiState: StateFlow<TreeUiState> =
            combine(
                treeFlow,
                selectedPathFlow,
                notesInSelectedFlow,
                expandedPathsFlow,
                loadingFlow,
            ) { tree, selectedPath, notes, expandedPaths, loading ->
                TreeUiState(
                    tree = tree,
                    selectedPath = selectedPath,
                    notesInSelected = notes,
                    expandedPaths = expandedPaths,
                    loading = loading,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = TreeUiState(loading = true),
            )

        init {
            loadFolders()
            viewModelScope.launch {
                repo.observeAllNotes().collect {
                    reloadFoldersSilent()
                }
            }
        }

        fun selectFolder(path: String?) {
            selectedPathFlow.value = path
            notesJob?.cancel()
            if (path != null) {
                notesJob =
                    viewModelScope.launch {
                        repo.observeNotesInFolder(path).collect { notes ->
                            notesInSelectedFlow.value = notes
                        }
                    }
            } else {
                notesInSelectedFlow.value = emptyList()
            }
        }

        fun toggleFolderExpanded(path: String) {
            val current = expandedPathsFlow.value
            expandedPathsFlow.value =
                if (current.contains(path)) {
                    current - path
                } else {
                    current + path
                }
        }

        fun createFolder(
            parentPath: String,
            name: String,
        ) {
            viewModelScope.launch {
                repo.createFolder(parentPath, name)
                val folders = repo.listFolders()
                treeFlow.value = buildFolderTree(folders)
                if (parentPath.isNotEmpty()) {
                    expandedPathsFlow.value = expandedPathsFlow.value + parentPath
                }
            }
        }

        fun refresh() {
            loadFolders()
        }

        private fun loadFolders() {
            viewModelScope.launch {
                loadingFlow.value = true
                val folders = repo.listFolders()
                treeFlow.value = buildFolderTree(folders)
                loadingFlow.value = false
            }
        }

        private suspend fun reloadFoldersSilent() {
            val folders = repo.listFolders()
            treeFlow.value = buildFolderTree(folders)
        }

        companion object {
            private const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
