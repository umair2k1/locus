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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecommendationRankerTest {
    private lateinit var fakeCapabilitiesGateway: FakeDeviceCapabilitiesGateway
    private lateinit var fakeMetaRepository: FakeModelMetaRepository
    private lateinit var ranker: RecommendationRanker

    @Before
    fun setUp() {
        fakeCapabilitiesGateway =
            FakeDeviceCapabilitiesGateway(
                ramBytes = 8_000_000_000L, // 8 GB
                totalRam = 12_000_000_000L, // 12 GB
                storageBytes = 64_000_000_000L, // 64 GB
                fingerprint = "test-pixel-9",
            )
        fakeMetaRepository = FakeModelMetaRepository()
        ranker = RecommendationRanker(fakeCapabilitiesGateway, fakeMetaRepository)
    }

    @Test
    fun `fake low-RAM device excludes too-large model from ranked list`() =
        runTest {
            // Low-RAM device with only 2 GB available RAM
            fakeCapabilitiesGateway.ramBytes = 2_000_000_000L

            val smallModel =
                createModelRecommendation(
                    id = "qwen-1.7b",
                    name = "Qwen3 1.7B",
                    filename = "qwen-1.7b.gguf",
                    sizeBytes = 1_800_000_000L, // 1.8 GB (fits in 2 GB RAM)
                )
            val largeModel =
                createModelRecommendation(
                    id = "qwen-4b",
                    name = "Qwen3 4B",
                    filename = "qwen-4b.gguf",
                    sizeBytes = 2_500_000_000L, // 2.5 GB (exceeds 2 GB RAM)
                )

            val ranked = ranker.rank(listOf(largeModel, smallModel))

            assertEquals(1, ranked.size)
            assertEquals("qwen-1.7b", ranked.first().id)
            assertFalse(ranked.any { it.id == "qwen-4b" })
        }

    @Test
    fun `re-index time estimate scales linearly with fixture chunk count`() {
        val tokPerSec = 50.0

        val estimate100 =
            ranker.estimateReindexTimeSeconds(totalChunkCount = 100, tokPerSecond = tokPerSec)
        val estimate200 =
            ranker.estimateReindexTimeSeconds(totalChunkCount = 200, tokPerSecond = tokPerSec)
        val estimate500 =
            ranker.estimateReindexTimeSeconds(totalChunkCount = 500, tokPerSecond = tokPerSec)

        assertEquals(2.0, estimate100, 0.0001)
        assertEquals(4.0, estimate200, 0.0001)
        assertEquals(10.0, estimate500, 0.0001)

        // Verifying exact linear scaling
        assertEquals(estimate100 * 2.0, estimate200, 0.0001)
        assertEquals(estimate100 * 5.0, estimate500, 0.0001)
    }

    @Test
    fun `re-index time estimate handles zero or negative chunk count or throughput gracefully`() {
        assertEquals(
            0.0,
            ranker.estimateReindexTimeSeconds(totalChunkCount = 0, tokPerSecond = 50.0),
            0.0001,
        )
        assertEquals(
            0.0,
            ranker.estimateReindexTimeSeconds(totalChunkCount = -5, tokPerSecond = 50.0),
            0.0001,
        )
        assertEquals(
            0.0,
            ranker.estimateReindexTimeSeconds(totalChunkCount = 100, tokPerSecond = 0.0),
            0.0001,
        )
        assertEquals(
            0.0,
            ranker.estimateReindexTimeSeconds(totalChunkCount = 100, tokPerSecond = -10.0),
            0.0001,
        )
    }

    @Test
    fun `low-storage device excludes model exceeding available storage headroom`() =
        runTest {
            fakeCapabilitiesGateway.storageBytes = 1_000_000_000L // 1 GB free storage

            val modelSmall =
                createModelRecommendation(
                    id = "model-500m",
                    sizeBytes = 500_000_000L, // 500 MB (fits)
                )
            val modelBig =
                createModelRecommendation(
                    id = "model-2g",
                    sizeBytes = 2_000_000_000L, // 2 GB (exceeds storage)
                )

            val ranked = ranker.rank(listOf(modelBig, modelSmall))

            assertEquals(1, ranked.size)
            assertEquals("model-500m", ranked.first().id)
        }

    @Test
    fun `ranks equally-eligible entries by measured tok-per-s from benchmark`() =
        runTest {
            val fastModel =
                createModelRecommendation(
                    id = "model-fast",
                    filename = "model-fast.gguf",
                    sizeBytes = 2_000_000_000L,
                )
            val slowModel =
                createModelRecommendation(
                    id = "model-slow",
                    filename = "model-slow.gguf",
                    sizeBytes = 2_000_000_000L,
                )

            fakeMetaRepository.saveBenchmarkResult(
                modelId = "model-fast.gguf",
                device = "test-pixel-9",
                tokensPerSecond = 35.0,
                benchmarkedAt = 1000L,
            )
            fakeMetaRepository.saveBenchmarkResult(
                modelId = "model-slow.gguf",
                device = "test-pixel-9",
                tokensPerSecond = 15.0,
                benchmarkedAt = 1000L,
            )

            val ranked = ranker.rank(listOf(slowModel, fastModel))

            assertEquals(2, ranked.size)
            assertEquals("model-fast", ranked[0].id)
            assertEquals("model-slow", ranked[1].id)
        }

    @Test
    fun `falls back to size-based speed heuristic when no benchmark exists on device`() =
        runTest {
            // No benchmark saved for either model
            val smallModel =
                createModelRecommendation(
                    id = "model-small",
                    filename = "model-small.gguf",
                    sizeBytes = 1_000_000_000L, // 1 GB -> higher estimated tok/s
                )
            val largeModel =
                createModelRecommendation(
                    id = "model-large",
                    filename = "model-large.gguf",
                    sizeBytes = 3_000_000_000L, // 3 GB -> lower estimated tok/s
                )

            val ranked = ranker.rank(listOf(largeModel, smallModel))

            assertEquals(2, ranked.size)
            assertEquals("model-small", ranked[0].id)
            assertEquals("model-large", ranked[1].id)
        }

    @Test
    fun `capability matching prioritizes tool support for agentic tasks`() =
        runTest {
            val toolModel =
                createModelRecommendation(
                    id = "tool-model",
                    filename = "tool-model.gguf",
                    sizeBytes = 2_000_000_000L,
                    supportsTools = true,
                )
            val noToolModel =
                createModelRecommendation(
                    id = "notool-model",
                    filename = "notool-model.gguf",
                    sizeBytes = 1_000_000_000L,
                    supportsTools = false,
                )

            val taskRequirements = TaskRequirements(task = "Agentic", requiresTools = true)
            val ranked = ranker.rank(listOf(noToolModel, toolModel), taskRequirements)

            assertEquals(2, ranked.size)
            assertEquals("tool-model", ranked[0].id)
            assertEquals("notool-model", ranked[1].id)
        }

    @Test
    fun `capability matching prioritizes models meeting minimum context length`() =
        runTest {
            val shortCtxModel =
                createModelRecommendation(
                    id = "short-ctx",
                    contextLength = 2048,
                    sizeBytes = 1_000_000_000L,
                )
            val longCtxModel =
                createModelRecommendation(
                    id = "long-ctx",
                    contextLength = 8192,
                    sizeBytes = 1_000_000_000L,
                )

            val taskRequirements = TaskRequirements(task = "Chat", minContextLength = 4096)
            val ranked = ranker.rank(listOf(shortCtxModel, longCtxModel), taskRequirements)

            assertEquals(2, ranked.size)
            assertEquals("long-ctx", ranked[0].id)
            assertEquals("short-ctx", ranked[1].id)
        }

    @Test
    fun `estimateReindexTime computes estimate with measured speed when benchmark exists`() =
        runTest {
            fakeMetaRepository.saveBenchmarkResult(
                modelId = "embedding-model.gguf",
                device = "test-pixel-9",
                tokensPerSecond = 50.0,
                benchmarkedAt = 1000L,
            )

            val estimate =
                ranker.estimateReindexTime(
                    modelId = "embedding-model.gguf",
                    totalChunkCount = 250,
                    modelSizeBytes = 330_000_000L,
                )

            assertEquals(250, estimate.totalChunkCount)
            assertEquals(50.0, estimate.tokensPerSecond, 0.0001)
            assertTrue(estimate.isMeasured)
            assertEquals(5.0, estimate.estimatedSeconds, 0.0001)
        }

    @Test
    fun `estimateReindexTime computes estimate with heuristic speed when unbenchmarked`() =
        runTest {
            val sizeBytes = 300_000_000L
            val expectedSpeed = ranker.estimateTokensPerSecond(sizeBytes)

            val estimate =
                ranker.estimateReindexTime(
                    modelId = "unbenchmarked-model.gguf",
                    totalChunkCount = 300,
                    modelSizeBytes = sizeBytes,
                )

            assertEquals(300, estimate.totalChunkCount)
            assertEquals(expectedSpeed, estimate.tokensPerSecond, 0.0001)
            assertFalse(estimate.isMeasured)
            assertEquals(300.0 / expectedSpeed, estimate.estimatedSeconds, 0.0001)
        }

    @Test
    fun `fitsHeadroom returns true when available memory or storage is unconstrained`() {
        assertTrue(
            ranker.fitsHeadroom(
                sizeBytes = 5_000_000_000L,
                availableRam = 0L,
                availableStorage = 0L,
            ),
        )
        assertTrue(
            ranker.fitsHeadroom(
                sizeBytes = 5_000_000_000L,
                availableRam = -1L,
                availableStorage = -1L,
            ),
        )
        assertTrue(
            ranker.fitsHeadroom(
                sizeBytes = 0L,
                availableRam = 1_000_000_000L,
                availableStorage = 1_000_000_000L,
            ),
        )
    }

    @Suppress("LongParameterList")
    private fun createModelRecommendation(
        id: String,
        name: String = id,
        repo: String = "org/$id",
        filename: String = "$id.gguf",
        sha256: String = "hash-$id",
        sizeBytes: Long = 1_000_000_000L,
        contextLength: Int = 4096,
        description: String = "Description for $id",
        task: String = "Chat",
        supportsTools: Boolean = false,
    ): ModelRecommendation =
        ModelRecommendation(
            id = id,
            name = name,
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

private class FakeDeviceCapabilitiesGateway(
    var ramBytes: Long = 8_000_000_000L,
    var totalRam: Long = 12_000_000_000L,
    var storageBytes: Long = 64_000_000_000L,
    var fingerprint: String = "test-device",
) : DeviceCapabilitiesGateway {
    override fun getAvailableRamBytes(): Long = ramBytes

    override fun getTotalRamBytes(): Long = totalRam

    override fun getAvailableStorageBytes(): Long = storageBytes

    override fun getDeviceFingerprint(): String = fingerprint
}

private class FakeModelMetaRepository : ModelMetaRepository {
    private val metas = mutableMapOf<String, ModelMeta>()

    private fun key(
        modelId: String,
        device: String,
    ) = "$modelId@$device"

    override fun observeModelMeta(
        modelId: String,
        device: String,
    ): Flow<ModelMeta?> = flowOf(metas[key(modelId, device)])

    override fun observeAll(device: String): Flow<List<ModelMeta>> = flowOf(metas.values.filter { it.device == device })

    override suspend fun getModelMeta(
        modelId: String,
        device: String,
    ): ModelMeta? = metas[key(modelId, device)]

    override suspend fun saveNotesAndRating(
        modelId: String,
        device: String,
        notes: String,
        rating: Int,
    ) {
        val k = key(modelId, device)
        val existing = metas[k] ?: ModelMeta(modelId = modelId, device = device)
        metas[k] = existing.copy(notes = notes, rating = rating)
    }

    override suspend fun saveBenchmarkResult(
        modelId: String,
        device: String,
        tokensPerSecond: Double,
        benchmarkedAt: Long,
    ) {
        val k = key(modelId, device)
        val existing = metas[k] ?: ModelMeta(modelId = modelId, device = device)
        metas[k] =
            existing.copy(
                tokensPerSecond = tokensPerSecond,
                benchmarkedAt = benchmarkedAt,
            )
    }

    override suspend fun deleteByModelId(modelId: String) {
        metas.entries.removeIf { it.value.modelId == modelId }
    }
}
