package com.locus.app.ui.models

import app.cash.turbine.test
import com.locus.core.domain.models.BenchmarkResult
import com.locus.core.domain.models.DeviceCapabilitiesGateway
import com.locus.core.domain.models.DownloadStatus
import com.locus.core.domain.models.DownloadedModel
import com.locus.core.domain.models.ModelDownloadProgress
import com.locus.core.domain.models.ModelFileInfo
import com.locus.core.domain.models.ModelManagerRepository
import com.locus.core.domain.models.ModelMeta
import com.locus.core.domain.models.ModelMetaRepository
import com.locus.core.domain.models.ModelRecommendation
import com.locus.core.domain.models.ModelRepoSummary
import com.locus.core.domain.models.ModelStorageStats
import com.locus.core.domain.models.QuantFilter
import com.locus.core.domain.models.QuantSortOrder
import com.locus.core.domain.models.RecommendationRanker
import com.locus.core.domain.models.RepoSortOrder
import com.locus.core.domain.settings.DismissedRecommendationsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModelManagerViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepo: FakeModelManagerRepository
    private lateinit var fakeDismissedStore: FakeDismissedRecommendationsStore
    private lateinit var viewModel: ModelManagerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeModelManagerRepository()
        fakeDismissedStore = FakeDismissedRecommendationsStore()
        viewModel = ModelManagerViewModel(fakeRepo, fakeDismissedStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsStorageStatsAndDownloadedModelsWithoutSearchingRepos() =
        runTest {
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1024L, state.storageStats.totalUsedBytes)
            assertEquals(1, state.downloadedModels.size)
            assertEquals("qwen-1.gguf", state.downloadedModels[0].filename)
            assertTrue(state.searchResults.isEmpty())
            assertFalse(state.isBrowseHfVisible)
            assertEquals("", state.searchQuery)
            assertFalse(state.isSearchingRepos)
        }

    @Test
    fun toggleBrowseHf_togglesVisibility_andTriggersSearchWhenEmpty() =
        runTest {
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isBrowseHfVisible)
            assertTrue(
                viewModel.uiState.value.searchResults
                    .isEmpty(),
            )

            viewModel.toggleBrowseHf()
            assertTrue(viewModel.uiState.value.isBrowseHfVisible)
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.searchResults.size)
            assertEquals(
                "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                viewModel.uiState.value.searchResults[0]
                    .id,
            )

            viewModel.toggleBrowseHf()
            assertFalse(viewModel.uiState.value.isBrowseHfVisible)
        }

    @Test
    fun selectRecommendation_setsBrowseHfVisibleTrue_andSelectsRepo() =
        runTest {
            advanceUntilIdle()
            val rec =
                ModelRecommendation(
                    id = "rec-1",
                    name = "Qwen 2.5",
                    repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                    filename = "qwen2.5-0.5b-q4_k_m.gguf",
                    sha256 = "sha-123",
                    sizeBytes = 398_000_000L,
                    contextLength = 4096,
                    description = "Test recommendation",
                    task = "Chat",
                )

            viewModel.selectRecommendation(rec)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isBrowseHfVisible)
            assertEquals(
                "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                viewModel.uiState.value.selectedRepo
                    ?.id,
            )
            assertEquals(2, viewModel.uiState.value.quantFiles.size)
        }

    @Test
    fun setRepoSortOrder_updatesSortOrder_andTriggersSearch() =
        runTest {
            advanceUntilIdle()
            viewModel.setRepoSortOrder(RepoSortOrder.LIKES)
            advanceUntilIdle()

            assertEquals(RepoSortOrder.LIKES, viewModel.uiState.value.repoSortOrder)
            assertEquals(RepoSortOrder.LIKES, fakeRepo.lastSearchSort)
        }

    @Test
    fun setRepoFilter_updatesFilterAndQuery_andTriggersSearch() =
        runTest {
            advanceUntilIdle()
            viewModel.setRepoFilter("Llama")
            advanceUntilIdle()

            assertEquals("Llama", viewModel.uiState.value.activeRepoFilter)
            assertEquals("Llama", viewModel.uiState.value.searchQuery)
            assertEquals(1, viewModel.uiState.value.searchResults.size)
            assertEquals(
                "meta-llama/Llama-3.2-1B-Instruct-GGUF",
                viewModel.uiState.value.searchResults[0]
                    .id,
            )

            viewModel.setRepoFilter("All")
            advanceUntilIdle()
            assertEquals("All", viewModel.uiState.value.activeRepoFilter)
            assertEquals("", viewModel.uiState.value.searchQuery)
        }

    @Test
    fun quantFilterAndSort_correctlyFiltersAndSortsQuants() =
        runTest {
            advanceUntilIdle()
            val repo = ModelRepoSummary(id = "Qwen/Qwen2.5-0.5B-Instruct-GGUF")
            viewModel.selectRepo(repo)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.filteredQuantFiles.size)

            viewModel.setQuantFilter(QuantFilter.Q4)
            assertEquals(1, viewModel.uiState.value.filteredQuantFiles.size)
            assertEquals(
                "qwen2.5-0.5b-q4_k_m.gguf",
                viewModel.uiState.value.filteredQuantFiles[0]
                    .name,
            )

            viewModel.setQuantFilter(QuantFilter.Q8)
            assertEquals(1, viewModel.uiState.value.filteredQuantFiles.size)
            assertEquals(
                "qwen2.5-0.5b-q8_0.gguf",
                viewModel.uiState.value.filteredQuantFiles[0]
                    .name,
            )

            viewModel.setQuantFilter(QuantFilter.ALL)
            viewModel.setQuantSortOrder(QuantSortOrder.SIZE_DESC)
            assertEquals(
                "qwen2.5-0.5b-q8_0.gguf",
                viewModel.uiState.value.filteredQuantFiles[0]
                    .name,
            )
            assertEquals(
                "qwen2.5-0.5b-q4_k_m.gguf",
                viewModel.uiState.value.filteredQuantFiles[1]
                    .name,
            )
        }

    @Test
    fun clearSearch_resetsQuery_andSearchesEmpty() =
        runTest {
            advanceUntilIdle()
            viewModel.onSearchQueryChange("test-query")
            assertEquals("test-query", viewModel.uiState.value.searchQuery)

            viewModel.clearSearch()
            advanceUntilIdle()
            assertEquals("", viewModel.uiState.value.searchQuery)
            assertEquals("", fakeRepo.lastSearchQuery)
        }

    @Test
    fun searchRepos_updatesSearchResults() =
        runTest {
            advanceUntilIdle()

            viewModel.onSearchQueryChange("llama")
            viewModel.searchRepos("llama")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, state.searchResults.size)
            assertEquals("meta-llama/Llama-3.2-1B-Instruct-GGUF", state.searchResults[0].id)
        }

    @Test
    fun selectRepo_loadsQuantFiles_andClearResets() =
        runTest {
            advanceUntilIdle()

            val repo = ModelRepoSummary(id = "Qwen/Qwen2.5-0.5B-Instruct-GGUF")
            viewModel.selectRepo(repo)
            advanceUntilIdle()

            val selectedState = viewModel.uiState.value
            assertEquals(repo, selectedState.selectedRepo)
            assertEquals(2, selectedState.quantFiles.size)
            assertEquals("qwen2.5-0.5b-q4_k_m.gguf", selectedState.quantFiles[0].name)

            viewModel.clearSelectedRepo()
            val clearedState = viewModel.uiState.value
            assertNull(clearedState.selectedRepo)
            assertTrue(clearedState.quantFiles.isEmpty())
        }

    @Test
    fun startDownload_enqueuesDownloadAndSetsUserMessage() =
        runTest {
            advanceUntilIdle()

            val file = ModelFileInfo(name = "test-model.gguf", size = 5000L, sha256 = "sha-xyz")
            viewModel.startDownload("test/repo", file)
            advanceUntilIdle()

            assertEquals("test-model.gguf", fakeRepo.lastEnqueuedFilename)
            assertEquals("Started downloading test-model.gguf", viewModel.uiState.value.userMessage)

            viewModel.clearUserMessage()
            assertNull(viewModel.uiState.value.userMessage)
        }

    @Test
    fun cancelDownload_cancelsAndSetsUserMessage() =
        runTest {
            advanceUntilIdle()

            viewModel.cancelDownload("work-123")
            advanceUntilIdle()

            assertEquals("work-123", fakeRepo.lastCancelledWorkId)
            assertEquals("Download cancelled", viewModel.uiState.value.userMessage)
        }

    @Test
    fun removeDownload_dismissesFromActiveDownloadsAndCallsRepository() =
        runTest {
            val download =
                ModelDownloadProgress(
                    workId = "work-999",
                    filename = "qwen-to-remove.gguf",
                    bytesRead = 100L,
                    totalBytes = 1000L,
                    progressPercentage = 10,
                    status = DownloadStatus.DOWNLOADING,
                )
            fakeRepo.emitDownloads(listOf(download))
            advanceUntilIdle()

            assertEquals(1, viewModel.uiState.value.activeDownloads.size)

            viewModel.removeDownload("work-999", "qwen-to-remove.gguf")
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.activeDownloads
                    .isEmpty(),
            )
            assertEquals("work-999", fakeRepo.lastRemovedWorkId)
            assertEquals("qwen-to-remove.gguf", fakeRepo.lastRemovedFilename)
            assertEquals("Removed qwen-to-remove.gguf from queue", viewModel.uiState.value.userMessage)
        }

    @Test
    fun deleteModel_removesModelAndRefreshesState() =
        runTest {
            advanceUntilIdle()

            val modelToDelete = DownloadedModel("qwen-1.gguf", 1024L, "/models/qwen-1.gguf")
            viewModel.deleteModel(modelToDelete)
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.downloadedModels
                    .isEmpty(),
            )
            assertEquals(0L, viewModel.uiState.value.storageStats.totalUsedBytes)
            assertEquals("Deleted qwen-1.gguf", viewModel.uiState.value.userMessage)
        }

    @Test
    fun observeDownloads_updatesActiveDownloadsFlow() =
        runTest {
            advanceUntilIdle()
            viewModel.uiState.test {
                val initial = awaitItem()
                assertTrue(initial.activeDownloads.isEmpty())

                fakeRepo.emitDownloads(
                    listOf(
                        ModelDownloadProgress(
                            workId = "w1",
                            filename = "active-download.gguf",
                            bytesRead = 200L,
                            totalBytes = 1000L,
                            progressPercentage = 20,
                            status = DownloadStatus.DOWNLOADING,
                        ),
                    ),
                )

                val updated = awaitItem()
                assertEquals(1, updated.activeDownloads.size)
                assertEquals("active-download.gguf", updated.activeDownloads[0].filename)
                assertEquals(20, updated.activeDownloads[0].progressPercentage)
            }
        }

    @Test
    fun observeDownloads_filtersOutCompletedDownloadsFromActiveDownloads() =
        runTest {
            advanceUntilIdle()
            viewModel.uiState.test {
                val initial = awaitItem()
                assertTrue(initial.activeDownloads.isEmpty())

                fakeRepo.emitDownloads(
                    listOf(
                        ModelDownloadProgress(
                            workId = "w1",
                            filename = "in-progress.gguf",
                            bytesRead = 200L,
                            totalBytes = 1000L,
                            progressPercentage = 20,
                            status = DownloadStatus.DOWNLOADING,
                        ),
                        ModelDownloadProgress(
                            workId = "w2",
                            filename = "done.gguf",
                            bytesRead = 1000L,
                            totalBytes = 1000L,
                            progressPercentage = 100,
                            status = DownloadStatus.COMPLETED,
                        ),
                    ),
                )

                val updated = awaitItem()
                assertEquals(1, updated.activeDownloads.size)
                assertEquals("in-progress.gguf", updated.activeDownloads[0].filename)
            }
        }

    @Test
    fun observeModelMeta_updatesUiStateModelMetaMap() =
        runTest {
            advanceUntilIdle()
            viewModel.uiState.test {
                val initial = awaitItem()
                assertTrue(initial.modelMetaMap.isEmpty())

                fakeRepo.emitModelMeta(
                    listOf(
                        ModelMeta(
                            modelId = "qwen-1.gguf",
                            device = "test-device",
                            notes = "Very capable",
                            rating = 5,
                            tokensPerSecond = 34.2,
                        ),
                    ),
                )

                val updated = awaitItem()
                assertEquals(1, updated.modelMetaMap.size)
                val meta = updated.modelMetaMap["qwen-1.gguf"]
                assertEquals("Very capable", meta?.notes)
                assertEquals(5, meta?.rating)
                assertEquals(34.2, meta?.tokensPerSecond ?: 0.0, 1e-6)
            }
        }

    @Test
    fun updateModelNotesAndRating_callsRepository() =
        runTest {
            viewModel.updateModelNotesAndRating("qwen-1.gguf", "My custom review", 4)
            testScheduler.advanceUntilIdle()

            assertEquals("qwen-1.gguf", fakeRepo.lastSavedNotesModelId)
            assertEquals("My custom review", fakeRepo.lastSavedNotes)
            assertEquals(4, fakeRepo.lastSavedRating)
        }

    @Test
    fun benchmarkModel_onSuccess_updatesStateAndSetsMessage() =
        runTest {
            val model =
                DownloadedModel(
                    filename = "qwen-1.gguf",
                    sizeBytes = 1024L,
                    path = "/models/qwen-1.gguf",
                )

            viewModel.benchmarkModel(model)
            testScheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull(state.benchmarkingModelId)
            assertEquals("Benchmark completed: 42.5 tok/s", state.userMessage)
        }

    @Test
    fun benchmarkModel_onFailure_updatesStateAndSetsErrorMessage() =
        runTest {
            fakeRepo.benchmarkResultToReturn =
                Result.failure(RuntimeException("Inference engine error"))
            val model =
                DownloadedModel(
                    filename = "qwen-1.gguf",
                    sizeBytes = 1024L,
                    path = "/models/qwen-1.gguf",
                )

            viewModel.benchmarkModel(model)
            testScheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull(state.benchmarkingModelId)
            assertEquals("Benchmark failed: Inference engine error", state.userMessage)
        }

    @Test
    fun recommendations_emitsNonDismissedEntries() =
        runTest {
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(2, state.recommendations.size)
            assertEquals("Qwen3-4B", state.recommendations[0].id)
            assertEquals("Qwen3-1.7B", state.recommendations[1].id)
        }

    @Test
    fun recommendations_filtersOutDismissedEntries() =
        runTest {
            val dismissedStore = FakeDismissedRecommendationsStore(setOf("Qwen3-4B"))
            val vm = ModelManagerViewModel(fakeRepo, dismissedStore)
            advanceUntilIdle()

            val state = vm.uiState.value
            assertEquals(1, state.recommendations.size)
            assertEquals("Qwen3-1.7B", state.recommendations[0].id)
        }

    @Test
    fun dismissRecommendation_updatesDismissedStoreAndRemovesFromUiState() =
        runTest {
            advanceUntilIdle()
            assertEquals(2, viewModel.uiState.value.recommendations.size)

            viewModel.dismissRecommendation("Qwen3-4B")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(1, state.recommendations.size)
            assertEquals("Qwen3-1.7B", state.recommendations[0].id)
        }

    @Test
    fun selectRecommendation_prefillsDownloadFlowWithoutActivatingModel() =
        runTest {
            advanceUntilIdle()
            val recommendation = viewModel.uiState.value.recommendations[0]
            viewModel.selectRecommendation(recommendation)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("Qwen/Qwen3-4B-GGUF", state.searchQuery)
            assertEquals("Qwen/Qwen3-4B-GGUF", state.selectedRepo?.id)
            assertEquals(2, state.quantFiles.size)
        }

    @Test
    fun requestEmbeddingModelSwitch_populatesPendingEmbeddingSwitchInUiState() =
        runTest {
            val fakeCapabilitiesGateway =
                object : DeviceCapabilitiesGateway {
                    override fun getAvailableRamBytes(): Long = 8_000_000_000L

                    override fun getTotalRamBytes(): Long = 12_000_000_000L

                    override fun getAvailableStorageBytes(): Long = 64_000_000_000L

                    override fun getDeviceFingerprint(): String = "test-device"
                }
            val fakeMetaRepo =
                @Suppress("EmptyFunctionBlock")
                object : ModelMetaRepository {
                    override fun observeModelMeta(
                        modelId: String,
                        device: String,
                    ): Flow<ModelMeta?> = flowOf(null)

                    override fun observeAll(device: String): Flow<List<ModelMeta>> = flowOf(emptyList())

                    override suspend fun getModelMeta(
                        modelId: String,
                        device: String,
                    ): ModelMeta? = null

                    override suspend fun saveNotesAndRating(
                        modelId: String,
                        device: String,
                        notes: String,
                        rating: Int,
                    ) {}

                    override suspend fun saveBenchmarkResult(
                        modelId: String,
                        device: String,
                        tokensPerSecond: Double,
                        benchmarkedAt: Long,
                    ) {}

                    override suspend fun deleteByModelId(modelId: String) {}
                }
            val ranker = RecommendationRanker(fakeCapabilitiesGateway, fakeMetaRepo)

            val testVm =
                ModelManagerViewModel(
                    repository = fakeRepo,
                    dismissedRecommendationsStore = fakeDismissedStore,
                    recommendationRanker = ranker,
                )

            val embeddingModel =
                DownloadedModel(
                    filename = "embeddinggemma-300M-Q8_0.gguf",
                    sizeBytes = 330_000_000L,
                    path = "/path/to/embeddinggemma-300M-Q8_0.gguf",
                )
            testVm.requestEmbeddingModelSwitch(embeddingModel)
            advanceUntilIdle()

            val pending = testVm.uiState.value.pendingEmbeddingSwitch
            org.junit.Assert.assertNotNull(pending)
            assertEquals("embeddinggemma-300M-Q8_0.gguf", pending?.targetModelId)
            org.junit.Assert.assertTrue((pending?.tokensPerSecond ?: 0.0) > 0.0)
        }

    @Test
    fun dismissEmbeddingModelSwitch_clearsPendingEmbeddingSwitch() =
        runTest {
            viewModel.dismissEmbeddingModelSwitch()
            org.junit.Assert.assertNull(viewModel.uiState.value.pendingEmbeddingSwitch)
        }
}

private class FakeModelManagerRepository : ModelManagerRepository {
    private val downloadsFlow = MutableStateFlow<List<ModelDownloadProgress>>(emptyList())
    var lastEnqueuedFilename: String? = null
    var lastCancelledWorkId: String? = null
    var lastRemovedWorkId: String? = null
    var lastRemovedFilename: String? = null
    private val metaFlow = MutableStateFlow<List<ModelMeta>>(emptyList())
    var lastSavedNotesModelId: String? = null
    var lastSavedNotes: String? = null
    var lastSavedRating: Int? = null
    var benchmarkResultToReturn: Result<BenchmarkResult> =
        Result.success(
            BenchmarkResult(
                tokensPerSecond = 42.5,
                totalTokens = 128,
                durationMs = 3000L,
            ),
        )

    fun emitModelMeta(list: List<ModelMeta>) {
        metaFlow.value = list
    }

    private val localModels =
        mutableListOf(
            DownloadedModel(
                filename = "qwen-1.gguf",
                sizeBytes = 1024L,
                path = "/models/qwen-1.gguf",
            ),
        )

    fun emitDownloads(list: List<ModelDownloadProgress>) {
        downloadsFlow.value = list
    }

    var lastSearchQuery: String? = null
    var lastSearchSort: RepoSortOrder? = null

    override suspend fun searchRepos(
        query: String,
        sort: RepoSortOrder?,
    ): Result<List<ModelRepoSummary>> {
        lastSearchQuery = query
        lastSearchSort = sort
        return Result.success(
            if (query.contains("llama", ignoreCase = true)) {
                listOf(
                    ModelRepoSummary(
                        id = "meta-llama/Llama-3.2-1B-Instruct-GGUF",
                        description = "Llama 3.2 1B",
                        downloads = 5000,
                        likes = 120,
                    ),
                )
            } else {
                listOf(
                    ModelRepoSummary(
                        id = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
                        description = "Qwen 2.5 0.5B Instruct",
                        downloads = 10000,
                        likes = 350,
                    ),
                )
            },
        )
    }

    override suspend fun listQuantFiles(repoId: String): Result<List<ModelFileInfo>> =
        Result.success(
            listOf(
                ModelFileInfo(
                    name = "qwen2.5-0.5b-q4_k_m.gguf",
                    size = 398_000_000L,
                    sha256 = "sha1",
                ),
                ModelFileInfo(
                    name = "qwen2.5-0.5b-q8_0.gguf",
                    size = 680_000_000L,
                    sha256 = "sha2",
                ),
            ),
        )

    override suspend fun enqueueDownload(
        repoId: String,
        filename: String,
        sha256: String,
        expectedSize: Long,
    ): Result<String> {
        lastEnqueuedFilename = filename
        return Result.success("work-id-123")
    }

    override fun observeDownloads(): Flow<List<ModelDownloadProgress>> = downloadsFlow

    override suspend fun cancelDownload(workId: String) {
        lastCancelledWorkId = workId
    }

    override suspend fun removeDownload(
        workId: String,
        filename: String,
    ) {
        lastRemovedWorkId = workId
        lastRemovedFilename = filename
    }

    override suspend fun getDownloadedModels(): List<DownloadedModel> = localModels.toList()

    override suspend fun deleteModel(filename: String): Boolean = localModels.removeIf { it.filename == filename }

    override suspend fun getStorageStats(): ModelStorageStats =
        ModelStorageStats(
            totalUsedBytes = localModels.sumOf { it.sizeBytes },
            freeBytes = 50_000_000_000L,
            totalDeviceBytes = 128_000_000_000L,
        )

    override fun observeAllModelMeta(): Flow<List<ModelMeta>> = metaFlow

    override suspend fun saveModelNotesAndRating(
        modelId: String,
        notes: String,
        rating: Int,
    ) {
        lastSavedNotesModelId = modelId
        lastSavedNotes = notes
        lastSavedRating = rating
    }

    override suspend fun runBenchmark(
        modelId: String,
        path: String,
    ): Result<BenchmarkResult> = benchmarkResultToReturn

    private val recommendationsFlow =
        MutableStateFlow<List<ModelRecommendation>>(
            listOf(
                ModelRecommendation(
                    id = "Qwen3-4B",
                    name = "Qwen3 4B",
                    repo = "Qwen/Qwen3-4B-GGUF",
                    filename = "Qwen3-4B-Q4_K_M.gguf",
                    sha256 = "sha-qwen-4b",
                    sizeBytes = 2497280256L,
                    contextLength = 32768,
                    description = "Recommended default chat model",
                    task = "Chat",
                ),
                ModelRecommendation(
                    id = "Qwen3-1.7B",
                    name = "Qwen3 1.7B",
                    repo = "Qwen/Qwen3-1.7B-GGUF",
                    filename = "Qwen3-1.7B-Q8_0.gguf",
                    sha256 = "sha-qwen-1.7b",
                    sizeBytes = 1834426016L,
                    contextLength = 32768,
                    description = "Recommended default utility model",
                    task = "Utility",
                ),
            ),
        )

    fun emitRecommendations(list: List<ModelRecommendation>) {
        recommendationsFlow.value = list
    }

    override fun observeRecommendations(): Flow<List<ModelRecommendation>> = recommendationsFlow
}

private class FakeDismissedRecommendationsStore(
    initialDismissed: Set<String> = emptySet(),
) : DismissedRecommendationsStore {
    private val flow = MutableStateFlow(initialDismissed)
    override val dismissedIds: Flow<Set<String>> = flow

    override suspend fun dismiss(entryId: String) {
        flow.value = flow.value + entryId
    }
}
