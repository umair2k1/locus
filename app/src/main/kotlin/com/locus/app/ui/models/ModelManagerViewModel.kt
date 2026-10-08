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

package com.locus.app.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.models.DownloadStatus
import com.locus.core.domain.models.DownloadedModel
import com.locus.core.domain.models.ModelDownloadProgress
import com.locus.core.domain.models.ModelFileInfo
import com.locus.core.domain.models.ModelManagerRepository
import com.locus.core.domain.models.ModelMeta
import com.locus.core.domain.models.ModelRecommendation
import com.locus.core.domain.models.ModelRepoSummary
import com.locus.core.domain.models.ModelStorageStats
import com.locus.core.domain.models.QuantFilter
import com.locus.core.domain.models.QuantSortOrder
import com.locus.core.domain.models.RecommendationRanker
import com.locus.core.domain.models.RepoSortOrder
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.IndexingCoordinator
import com.locus.core.domain.settings.DismissedRecommendationsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingEmbeddingSwitch(
    val targetModelId: String,
    val targetModelName: String,
    val totalChunkCount: Int,
    val estimatedSeconds: Double,
    val tokensPerSecond: Double,
    val isMeasured: Boolean,
)

val REPO_FILTER_PRESETS = listOf("All", "Qwen", "Llama", "DeepSeek", "Gemma", "Mistral", "Embedding")

data class ModelManagerUiState(
    val storageStats: ModelStorageStats = ModelStorageStats(0L, 0L, 0L),
    val downloadedModels: List<DownloadedModel> = emptyList(),
    val activeDownloads: List<ModelDownloadProgress> = emptyList(),
    val recommendations: List<ModelRecommendation> = emptyList(),
    val isBrowseHfVisible: Boolean = false,
    val searchQuery: String = "",
    val isSearchingRepos: Boolean = false,
    val searchResults: List<ModelRepoSummary> = emptyList(),
    val searchError: String? = null,
    val repoSortOrder: RepoSortOrder = RepoSortOrder.DOWNLOADS,
    val activeRepoFilter: String = "All",
    val selectedRepo: ModelRepoSummary? = null,
    val isLoadingQuants: Boolean = false,
    val quantFiles: List<ModelFileInfo> = emptyList(),
    val quantsError: String? = null,
    val quantFilter: QuantFilter = QuantFilter.ALL,
    val quantSortOrder: QuantSortOrder = QuantSortOrder.SIZE_ASC,
    val modelMetaMap: Map<String, ModelMeta> = emptyMap(),
    val benchmarkingModelId: String? = null,
    val userMessage: String? = null,
    val pendingEmbeddingSwitch: PendingEmbeddingSwitch? = null,
    val isReindexing: Boolean = false,
) {
    val displayedSearchResults: List<ModelRepoSummary>
        get() {
            val list =
                when {
                    activeRepoFilter.isBlank() || activeRepoFilter.equals("All", ignoreCase = true) -> searchResults
                    else ->
                        searchResults.filter {
                            it.id.contains(activeRepoFilter, ignoreCase = true) ||
                                it.description.contains(activeRepoFilter, ignoreCase = true)
                        }
                }
            return when (repoSortOrder) {
                RepoSortOrder.DOWNLOADS -> list.sortedByDescending { it.downloads }
                RepoSortOrder.LIKES -> list.sortedByDescending { it.likes }
                RepoSortOrder.NAME -> list.sortedBy { it.id.lowercase() }
            }
        }

    val filteredQuantFiles: List<ModelFileInfo>
        get() {
            val filtered =
                when (quantFilter) {
                    QuantFilter.ALL -> quantFiles
                    QuantFilter.Q4 -> quantFiles.filter { it.name.contains("q4", ignoreCase = true) }
                    QuantFilter.Q5 -> quantFiles.filter { it.name.contains("q5", ignoreCase = true) }
                    QuantFilter.Q8 -> quantFiles.filter { it.name.contains("q8", ignoreCase = true) }
                    QuantFilter.Q6 -> quantFiles.filter { it.name.contains("q6", ignoreCase = true) }
                    QuantFilter.IQ -> quantFiles.filter { it.name.contains("iq", ignoreCase = true) }
                }
            return when (quantSortOrder) {
                QuantSortOrder.SIZE_ASC -> filtered.sortedBy { it.size }
                QuantSortOrder.SIZE_DESC -> filtered.sortedByDescending { it.size }
                QuantSortOrder.NAME -> filtered.sortedBy { it.name.lowercase() }
            }
        }
}

@HiltViewModel
@Suppress("TooManyFunctions")
class ModelManagerViewModel
    @Inject
    constructor(
        private val repository: ModelManagerRepository,
        private val dismissedRecommendationsStore: DismissedRecommendationsStore,
        private val indexingCoordinator: IndexingCoordinator? = null,
        private val recommendationRanker: RecommendationRanker? = null,
        private val chunkRepository: ChunkRepository? = null,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ModelManagerUiState())
        val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()
        private val dismissedDownloadWorkIds = MutableStateFlow<Set<String>>(emptySet())

        private var searchJob: Job? = null
        private var quantsJob: Job? = null

        init {
            refreshStorageAndModels()
            observeDownloads()
            observeModelMeta()
            observeRecommendations()
        }

        fun refreshStorageAndModels() {
            viewModelScope.launch {
                val stats = repository.getStorageStats()
                val models = repository.getDownloadedModels()
                _uiState.update {
                    it.copy(
                        storageStats = stats,
                        downloadedModels = models,
                    )
                }
            }
        }

        private fun observeDownloads() {
            viewModelScope.launch {
                combine(
                    repository.observeDownloads(),
                    dismissedDownloadWorkIds,
                ) { rawDownloads, dismissedIds ->
                    val hadCompleted = rawDownloads.any { it.status == DownloadStatus.COMPLETED }
                    val activeList =
                        rawDownloads.filter {
                            it.workId !in dismissedIds &&
                                (
                                    it.status == DownloadStatus.DOWNLOADING ||
                                        it.status == DownloadStatus.PENDING
                                )
                        }
                    Pair(activeList, hadCompleted)
                }.collect { (activeList, hadCompleted) ->
                    _uiState.update { it.copy(activeDownloads = activeList) }
                    if (hadCompleted) {
                        refreshStorageAndModels()
                    }
                }
            }
        }

        private fun observeModelMeta() {
            viewModelScope.launch {
                repository.observeAllModelMeta().collect { metaList ->
                    _uiState.update { state ->
                        state.copy(modelMetaMap = metaList.associateBy { it.modelId })
                    }
                }
            }
        }

        private fun observeRecommendations() {
            viewModelScope.launch {
                combine(
                    repository.observeRecommendations(),
                    dismissedRecommendationsStore.dismissedIds,
                ) { recs, dismissedIds -> recs.filter { it.id !in dismissedIds } }
                    .collect { visibleRecs ->
                        _uiState.update { it.copy(recommendations = visibleRecs) }
                    }
            }
        }

        fun dismissRecommendation(id: String) {
            viewModelScope.launch { dismissedRecommendationsStore.dismiss(id) }
        }

        fun toggleBrowseHf() {
            val next = !_uiState.value.isBrowseHfVisible
            _uiState.update { it.copy(isBrowseHfVisible = next) }
            if (next && _uiState.value.searchResults.isEmpty() && !_uiState.value.isSearchingRepos) {
                searchRepos()
            }
        }

        fun setBrowseHfVisible(visible: Boolean) {
            _uiState.update { it.copy(isBrowseHfVisible = visible) }
            if (visible && _uiState.value.searchResults.isEmpty() && !_uiState.value.isSearchingRepos) {
                searchRepos()
            }
        }

        fun selectRecommendation(recommendation: ModelRecommendation) {
            _uiState.update { it.copy(isBrowseHfVisible = true) }
            onSearchQueryChange(recommendation.repo)
            selectRepo(
                ModelRepoSummary(
                    id = recommendation.repo,
                    description = recommendation.description.ifBlank { recommendation.name },
                ),
            )
        }

        fun onSearchQueryChange(query: String) {
            val matchingPreset =
                REPO_FILTER_PRESETS.firstOrNull { preset ->
                    !preset.equals("All", ignoreCase = true) &&
                        preset.equals(query.trim(), ignoreCase = true)
                }
            _uiState.update {
                it.copy(
                    searchQuery = query,
                    activeRepoFilter = matchingPreset ?: if (query.isBlank()) "All" else "",
                )
            }
        }

        fun clearSearch() {
            onSearchQueryChange("")
            searchRepos("")
        }

        fun setRepoSortOrder(order: RepoSortOrder) {
            if (_uiState.value.repoSortOrder == order) return
            _uiState.update { it.copy(repoSortOrder = order) }
            searchRepos()
        }

        fun setRepoFilter(filter: String) {
            _uiState.update { it.copy(activeRepoFilter = filter) }
            val query = if (filter.equals("All", ignoreCase = true)) "" else filter
            onSearchQueryChange(query)
            searchRepos(query)
        }

        fun setQuantFilter(filter: QuantFilter) {
            _uiState.update { it.copy(quantFilter = filter) }
        }

        fun setQuantSortOrder(order: QuantSortOrder) {
            _uiState.update { it.copy(quantSortOrder = order) }
        }

        fun searchRepos(queryOverride: String? = null) {
            val query = (queryOverride ?: _uiState.value.searchQuery).trim()
            val sortOrder = _uiState.value.repoSortOrder
            searchJob?.cancel()
            searchJob =
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(
                            isSearchingRepos = true,
                            searchError = null,
                        )
                    }
                    repository
                        .searchRepos(query = query, sort = sortOrder)
                        .onSuccess { repos ->
                            _uiState.update {
                                it.copy(
                                    isSearchingRepos = false,
                                    searchResults = repos,
                                    searchError = null,
                                )
                            }
                        }.onFailure { error ->
                            _uiState.update {
                                it.copy(
                                    isSearchingRepos = false,
                                    searchError =
                                        error.message
                                            ?: "Failed to search repositories",
                                )
                            }
                        }
                }
        }

        fun selectRepo(repo: ModelRepoSummary) {
            quantsJob?.cancel()
            quantsJob =
                viewModelScope.launch {
                    _uiState.update {
                        it.copy(
                            selectedRepo = repo,
                            isLoadingQuants = true,
                            quantFiles = emptyList(),
                            quantsError = null,
                        )
                    }
                    repository
                        .listQuantFiles(repo.id)
                        .onSuccess { files ->
                            _uiState.update {
                                it.copy(
                                    isLoadingQuants = false,
                                    quantFiles = files,
                                    quantsError = null,
                                )
                            }
                        }.onFailure { error ->
                            _uiState.update {
                                it.copy(
                                    isLoadingQuants = false,
                                    quantsError =
                                        error.message
                                            ?: "Failed to list quant files",
                                )
                            }
                        }
                }
        }

        fun clearSelectedRepo() {
            quantsJob?.cancel()
            _uiState.update {
                it.copy(
                    selectedRepo = null,
                    isLoadingQuants = false,
                    quantFiles = emptyList(),
                    quantsError = null,
                )
            }
        }

        fun startDownload(
            repoId: String,
            file: ModelFileInfo,
        ) {
            viewModelScope.launch {
                repository
                    .enqueueDownload(
                        repoId = repoId,
                        filename = file.name,
                        sha256 = file.sha256,
                        expectedSize = file.size,
                    ).onSuccess {
                        _uiState.update { state ->
                            state.copy(userMessage = "Started downloading ${file.name}")
                        }
                    }.onFailure { error ->
                        _uiState.update { state ->
                            state.copy(userMessage = "Download failed to enqueue: ${error.message}")
                        }
                    }
            }
        }

        fun cancelDownload(workId: String) {
            viewModelScope.launch {
                repository.cancelDownload(workId)
                refreshStorageAndModels()
                _uiState.update { state -> state.copy(userMessage = "Download cancelled") }
            }
        }

        fun removeDownload(
            workId: String,
            filename: String,
        ) {
            dismissedDownloadWorkIds.update { it + workId }
            viewModelScope.launch {
                repository.removeDownload(workId, filename)
                refreshStorageAndModels()
                _uiState.update { state ->
                    val label = filename.ifBlank { "Download" }
                    state.copy(userMessage = "Removed $label from queue")
                }
            }
        }

        fun deleteModel(model: DownloadedModel) {
            viewModelScope.launch {
                val deleted = repository.deleteModel(model.filename)
                if (deleted) {
                    refreshStorageAndModels()
                    _uiState.update { state -> state.copy(userMessage = "Deleted ${model.filename}") }
                } else {
                    _uiState.update { state ->
                        state.copy(userMessage = "Failed to delete ${model.filename}")
                    }
                }
            }
        }

        fun updateModelNotesAndRating(
            modelId: String,
            notes: String,
            rating: Int,
        ) {
            viewModelScope.launch { repository.saveModelNotesAndRating(modelId, notes, rating) }
        }

        fun benchmarkModel(model: DownloadedModel) {
            if (_uiState.value.benchmarkingModelId != null) return
            _uiState.update { it.copy(benchmarkingModelId = model.filename) }
            viewModelScope.launch {
                val result = repository.runBenchmark(model.filename, model.path)
                _uiState.update { state ->
                    state.copy(
                        benchmarkingModelId = null,
                        userMessage =
                            result.fold(
                                onSuccess = { bench ->
                                    "Benchmark completed: ${String.format(
                                        java.util.Locale.US,
                                        "%.1f",
                                        bench.tokensPerSecond,
                                    )} tok/s"
                                },
                                onFailure = { e ->
                                    "Benchmark failed: ${e.message ?: "Unknown error"}"
                                },
                            ),
                    )
                }
            }
        }

        fun clearUserMessage() {
            _uiState.update { it.copy(userMessage = null) }
        }

        fun requestEmbeddingModelSwitch(model: DownloadedModel) {
            requestEmbeddingModelSwitch(model.filename, model.filename, model.sizeBytes)
        }

        fun requestEmbeddingModelSwitch(
            modelId: String,
            modelName: String,
            sizeBytes: Long,
        ) {
            viewModelScope.launch {
                val totalChunks =
                    chunkRepository?.countChunks() ?: indexingCoordinator?.getTotalChunkCount() ?: 0
                val estimate =
                    recommendationRanker?.estimateReindexTime(
                        modelId = modelId,
                        totalChunkCount = totalChunks,
                        modelSizeBytes = sizeBytes,
                    )
                _uiState.update { state ->
                    state.copy(
                        pendingEmbeddingSwitch =
                            PendingEmbeddingSwitch(
                                targetModelId = modelId,
                                targetModelName = modelName,
                                totalChunkCount = estimate?.totalChunkCount ?: totalChunks,
                                estimatedSeconds = estimate?.estimatedSeconds ?: 0.0,
                                tokensPerSecond = estimate?.tokensPerSecond ?: 10.0,
                                isMeasured = estimate?.isMeasured ?: false,
                            ),
                    )
                }
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun confirmEmbeddingModelSwitch() {
            val pending = _uiState.value.pendingEmbeddingSwitch ?: return
            _uiState.update { it.copy(pendingEmbeddingSwitch = null, isReindexing = true) }
            viewModelScope.launch {
                try {
                    indexingCoordinator?.reindexAllForModelChange(pending.targetModelId)
                    _uiState.update {
                        it.copy(
                            isReindexing = false,
                            userMessage =
                                "Switched embedding model to ${pending.targetModelName}. Full re-index completed.",
                        )
                    }
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            isReindexing = false,
                            userMessage = "Re-indexing failed: ${e.message ?: "Unknown error"}",
                        )
                    }
                }
            }
        }

        fun dismissEmbeddingModelSwitch() {
            _uiState.update { it.copy(pendingEmbeddingSwitch = null) }
        }
    }
