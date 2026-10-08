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

package com.locus.core.domain.models

import kotlinx.coroutines.flow.Flow

interface ModelManagerRepository {
    suspend fun searchRepos(
        query: String,
        sort: RepoSortOrder? = null,
    ): Result<List<ModelRepoSummary>>

    suspend fun listQuantFiles(repoId: String): Result<List<ModelFileInfo>>

    suspend fun enqueueDownload(
        repoId: String,
        filename: String,
        sha256: String = "",
        expectedSize: Long = 0L,
    ): Result<String>

    fun observeDownloads(): Flow<List<ModelDownloadProgress>>

    suspend fun cancelDownload(workId: String)

    suspend fun removeDownload(
        workId: String,
        filename: String = "",
    )

    suspend fun getDownloadedModels(): List<DownloadedModel>

    suspend fun deleteModel(filename: String): Boolean

    suspend fun importModel(
        filename: String,
        sourceBytes: ByteArray,
    ): Result<DownloadedModel> = Result.failure(UnsupportedOperationException("importModel not implemented"))

    suspend fun getStorageStats(): ModelStorageStats

    fun observeAllModelMeta(): Flow<List<ModelMeta>>

    suspend fun saveModelNotesAndRating(
        modelId: String,
        notes: String,
        rating: Int,
    )

    suspend fun runBenchmark(
        modelId: String,
        path: String,
    ): Result<BenchmarkResult>

    fun observeRecommendations(): Flow<List<ModelRecommendation>>
}
