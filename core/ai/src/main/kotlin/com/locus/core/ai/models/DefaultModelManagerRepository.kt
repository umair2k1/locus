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

package com.locus.core.ai.models

import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.locus.core.ai.catalog.CatalogEntry
import com.locus.core.ai.catalog.CatalogRepository
import com.locus.core.ai.hf.HuggingFaceCatalogClient
import com.locus.core.ai.llama.DeviceFingerprintProvider
import com.locus.core.ai.llama.ModelBenchmark
import com.locus.core.ai.llama.ModelDownloadWorker
import com.locus.core.ai.llama.ModelDownloader
import com.locus.core.domain.models.BenchmarkResult
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
import com.locus.core.domain.models.RecommendationRanker
import com.locus.core.domain.models.RepoSortOrder
import com.locus.core.domain.models.TaskRequirements
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("LongParameterList")
@Singleton
class DefaultModelManagerRepository
    @Inject
    constructor(
        private val hfClient: HuggingFaceCatalogClient,
        private val modelDownloader: ModelDownloader,
        private val workManager: WorkManager,
        private val modelBenchmark: ModelBenchmark,
        private val metaRepository: ModelMetaRepository,
        private val deviceProvider: DeviceFingerprintProvider,
        private val catalogRepository: CatalogRepository,
        private val recommendationRanker: RecommendationRanker,
    ) : ModelManagerRepository {
        override suspend fun searchRepos(
            query: String,
            sort: RepoSortOrder?,
        ): Result<List<ModelRepoSummary>> =
            runCatching {
                val hfSort =
                    when (sort) {
                        RepoSortOrder.DOWNLOADS -> "downloads"
                        RepoSortOrder.LIKES -> "likes"
                        RepoSortOrder.NAME, null -> null
                    }
                val direction = if (hfSort != null) -1 else null
                hfClient.searchGgufRepos(query = query, sort = hfSort, direction = direction).map { summary ->
                    ModelRepoSummary(
                        id = summary.id,
                        description = summary.description,
                        downloads = summary.downloads,
                        likes = summary.likes,
                    )
                }
            }

        override suspend fun listQuantFiles(repoId: String): Result<List<ModelFileInfo>> =
            runCatching {
                hfClient.listFiles(repoId).map { fileInfo ->
                    ModelFileInfo(
                        name = fileInfo.name,
                        size = fileInfo.size,
                        sha256 = fileInfo.sha256,
                    )
                }
            }

        override suspend fun enqueueDownload(
            repoId: String,
            filename: String,
            sha256: String,
            expectedSize: Long,
        ): Result<String> =
            runCatching {
                val workId =
                    modelDownloader.enqueueDownloadWork(
                        repo = repoId,
                        filename = filename,
                        sha256 = sha256,
                        expectedSize = expectedSize,
                    )
                workId.toString()
            }

        override fun observeDownloads(): Flow<List<ModelDownloadProgress>> =
            workManager.getWorkInfosByTagFlow(ModelDownloader.TAG_MODEL_DOWNLOAD).map { workInfoList ->
                workInfoList.map { workInfo -> mapWorkInfoToDownloadProgress(workInfo) }
            }

        override suspend fun cancelDownload(workId: String) {
            val resolvedFilename = resolveFilenameForWork(workId)
            runCatching { workManager.cancelWorkById(UUID.fromString(workId)) }
            if (resolvedFilename.isNotBlank()) {
                modelDownloader.deleteModel(resolvedFilename)
            }
        }

        override suspend fun removeDownload(
            workId: String,
            filename: String,
        ) {
            runCatching {
                workManager.cancelWorkById(UUID.fromString(workId))
                workManager.pruneWork()
            }
        }

        private fun resolveFilenameForWork(workId: String): String =
            runCatching {
                val workInfo = workManager.getWorkInfoById(UUID.fromString(workId)).get()
                workInfo
                    ?.tags
                    ?.firstOrNull {
                        it.startsWith("tag_model_") &&
                            it != ModelDownloader.TAG_MODEL_DOWNLOAD
                    }?.removePrefix("tag_model_")
                    .orEmpty()
            }.getOrDefault("")

        override suspend fun getDownloadedModels(): List<DownloadedModel> =
            modelDownloader.getDownloadedModels().map { file ->
                DownloadedModel(
                    filename = file.name,
                    sizeBytes = file.length(),
                    path = file.absolutePath,
                    lastModified = file.lastModified(),
                )
            }

        override suspend fun deleteModel(filename: String): Boolean {
            val deleted = modelDownloader.deleteModel(filename)
            if (deleted) {
                metaRepository.deleteByModelId(filename)
            }
            return deleted
        }

        override suspend fun getStorageStats(): ModelStorageStats = modelDownloader.getStorageStats()

        override fun observeAllModelMeta(): Flow<List<ModelMeta>> {
            val device = deviceProvider.getDeviceFingerprint()
            return metaRepository.observeAll(device)
        }

        override suspend fun saveModelNotesAndRating(
            modelId: String,
            notes: String,
            rating: Int,
        ) {
            metaRepository.saveNotesAndRating(
                modelId = modelId,
                device = deviceProvider.getDeviceFingerprint(),
                notes = notes,
                rating = rating,
            )
        }

        override suspend fun runBenchmark(
            modelId: String,
            path: String,
        ): Result<BenchmarkResult> = runCatching { modelBenchmark.run(modelId, path) }

        private fun mapWorkInfoToDownloadProgress(workInfo: WorkInfo): ModelDownloadProgress {
            val progressData = workInfo.progress
            val outputData = workInfo.outputData

            val filename =
                progressData.getString(ModelDownloadWorker.KEY_FILENAME)
                    ?: outputData.getString(ModelDownloadWorker.KEY_FILENAME)
                    ?: workInfo.tags
                        .firstOrNull {
                            it.startsWith("tag_model_") &&
                                it != ModelDownloader.TAG_MODEL_DOWNLOAD
                        }?.removePrefix("tag_model_")
                        .orEmpty()

            val bytesRead = progressData.getLong(ModelDownloadWorker.KEY_BYTES_READ, 0L)
            val totalBytes = progressData.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
            val progressPercentage = progressData.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)

            val (status, errorMessage) =
                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                        DownloadStatus.PENDING to null
                    WorkInfo.State.RUNNING -> DownloadStatus.DOWNLOADING to null
                    WorkInfo.State.SUCCEEDED -> DownloadStatus.COMPLETED to null
                    WorkInfo.State.FAILED -> {
                        val error =
                            outputData.getString(ModelDownloadWorker.KEY_ERROR)
                                ?: "Download failed"
                        DownloadStatus.FAILED to error
                    }
                    WorkInfo.State.CANCELLED -> DownloadStatus.CANCELLED to null
                }

            return ModelDownloadProgress(
                workId = workInfo.id.toString(),
                filename = filename,
                bytesRead = bytesRead,
                totalBytes = totalBytes,
                progressPercentage =
                    if (status == DownloadStatus.COMPLETED) 100 else progressPercentage,
                status = status,
                errorMessage = errorMessage,
            )
        }

        override fun observeRecommendations(): Flow<List<ModelRecommendation>> =
            catalogRepository.current().map { catalog ->
                val chatCandidates = catalog.chat.map { it.toDomainRecommendation("Chat") }
                val utilityCandidates = catalog.utility.map { it.toDomainRecommendation("Utility") }
                val embeddingsCandidates =
                    catalog.embeddings.map { it.toDomainRecommendation("Embeddings") }

                val rankedChat =
                    recommendationRanker.rank(
                        chatCandidates,
                        TaskRequirements(task = "Chat", minContextLength = 4096),
                    )
                val rankedUtility =
                    recommendationRanker.rank(
                        utilityCandidates,
                        TaskRequirements(task = "Utility", minContextLength = 2048),
                    )
                val rankedEmbeddings =
                    recommendationRanker.rank(
                        embeddingsCandidates,
                        TaskRequirements(task = "Embeddings", minContextLength = 512),
                    )

                rankedChat + rankedUtility + rankedEmbeddings
            }

        private fun CatalogEntry.toDomainRecommendation(task: String): ModelRecommendation =
            ModelRecommendation(
                id = id,
                name = name.ifBlank { id },
                repo = repo,
                filename = filename,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                contextLength = contextLength,
                description = description,
                task = task,
                supportsTools = supportsTools,
            )
    }
