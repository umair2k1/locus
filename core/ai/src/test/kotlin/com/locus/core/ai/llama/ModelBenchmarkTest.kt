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

package com.locus.core.ai.llama

import com.locus.core.domain.models.ModelMeta
import com.locus.core.domain.models.ModelMetaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBenchmarkTest {
    @Test
    fun run_loadsModelRunsGenerationAndStoresTokPerSecond() =
        runTest {
            var loadedPath: String? = null
            var loadedKind: ModelKind? = null
            var generatedPrompt: String? = null
            var maxTokensRequested: Int? = null

            val fakeRuntime =
                object : LlamaRuntime() {
                    override suspend fun loadModel(
                        path: String,
                        kind: ModelKind,
                    ): Result<Unit> {
                        loadedPath = path
                        loadedKind = kind
                        return Result.success(Unit)
                    }

                    override fun generateStream(
                        prompt: String,
                        samplingParams: SamplingParams,
                    ): Flow<String> =
                        flow {
                            generatedPrompt = prompt
                            maxTokensRequested = samplingParams.maxTokens
                            repeat(10) { i -> emit("token_$i ") }
                        }
                }

            val fakeMetaRepo = FakeModelMetaRepository()
            val fakeDeviceProvider =
                object : DeviceFingerprintProvider {
                    override fun getDeviceFingerprint(): String = "test-device-fingerprint"
                }

            val benchmark = ModelBenchmark(fakeRuntime, fakeMetaRepo, fakeDeviceProvider)
            val result = benchmark.run("model-1.gguf", "/data/models/model-1.gguf")

            assertEquals("/data/models/model-1.gguf", loadedPath)
            assertEquals(ModelKind.CHAT, loadedKind)
            assertNotNull(generatedPrompt)
            assertEquals(128, maxTokensRequested)
            assertEquals(10, result.totalTokens)
            assertTrue("Tokens per second should be positive", result.tokensPerSecond > 0.0)

            assertEquals("model-1.gguf", fakeMetaRepo.lastSavedModelId)
            assertEquals("test-device-fingerprint", fakeMetaRepo.lastSavedDevice)
            assertEquals(result.tokensPerSecond, fakeMetaRepo.lastSavedTokensPerSecond ?: 0.0, 1e-6)
            assertTrue((fakeMetaRepo.lastSavedBenchmarkedAt ?: 0L) > 0L)
        }

    @Test
    fun run_loadsEmbeddingModelRunsEmbedAndStoresTokPerSecond() =
        runTest {
            var loadedPath: String? = null
            var loadedKind: ModelKind? = null
            var embedCallCount = 0

            val fakeRuntime =
                object : LlamaRuntime() {
                    override suspend fun loadModel(
                        path: String,
                        kind: ModelKind,
                    ): Result<Unit> {
                        loadedPath = path
                        loadedKind = kind
                        return Result.success(Unit)
                    }

                    override suspend fun embed(text: String): Result<FloatArray> {
                        embedCallCount++
                        return Result.success(FloatArray(512) { 0.1f })
                    }
                }

            val fakeMetaRepo = FakeModelMetaRepository()
            val fakeDeviceProvider =
                object : DeviceFingerprintProvider {
                    override fun getDeviceFingerprint(): String = "test-device-fingerprint"
                }

            val benchmark = ModelBenchmark(fakeRuntime, fakeMetaRepo, fakeDeviceProvider)
            val result = benchmark.run("embeddinggemma-2-Q8_0.gguf", "/data/models/embeddinggemma-2-Q8_0.gguf")

            assertEquals("/data/models/embeddinggemma-2-Q8_0.gguf", loadedPath)
            assertEquals(ModelKind.EMBEDDING, loadedKind)
            assertEquals(6, embedCallCount) // 1 warm-up + 5 benchmark iterations
            assertTrue("Total tokens should be positive", result.totalTokens > 0)
            assertTrue("Tokens per second should be positive", result.tokensPerSecond > 0.0)
            assertEquals("embeddinggemma-2-Q8_0.gguf", fakeMetaRepo.lastSavedModelId)
            assertEquals("test-device-fingerprint", fakeMetaRepo.lastSavedDevice)
            assertEquals(result.tokensPerSecond, fakeMetaRepo.lastSavedTokensPerSecond ?: 0.0, 1e-6)
        }

    @Test
    fun run_whenModelLoadFails_throwsIllegalStateException() =
        runTest {
            val fakeRuntime =
                object : LlamaRuntime() {
                    override suspend fun loadModel(
                        path: String,
                        kind: ModelKind,
                    ): Result<Unit> = Result.failure(IllegalArgumentException("File not found"))
                }

            val fakeMetaRepo = FakeModelMetaRepository()
            val fakeDeviceProvider =
                object : DeviceFingerprintProvider {
                    override fun getDeviceFingerprint(): String = "test-device"
                }

            val benchmark = ModelBenchmark(fakeRuntime, fakeMetaRepo, fakeDeviceProvider)

            val error =
                assertThrows(IllegalStateException::class.java) {
                    kotlinx.coroutines.runBlocking {
                        benchmark.run("missing.gguf", "/path/missing.gguf")
                    }
                }
            assertTrue(error.message?.contains("Failed to load model for benchmark") == true)
        }

    private class FakeModelMetaRepository : ModelMetaRepository {
        var lastSavedModelId: String? = null
        var lastSavedDevice: String? = null
        var lastSavedTokensPerSecond: Double? = null
        var lastSavedBenchmarkedAt: Long? = null

        override fun observeModelMeta(
            modelId: String,
            device: String,
        ): Flow<ModelMeta?> = emptyFlow()

        override fun observeAll(device: String): Flow<List<ModelMeta>> = emptyFlow()

        override suspend fun getModelMeta(
            modelId: String,
            device: String,
        ): ModelMeta? = null

        override suspend fun saveNotesAndRating(
            modelId: String,
            device: String,
            notes: String,
            rating: Int,
        ) {
            // No-op for testing
        }

        override suspend fun saveBenchmarkResult(
            modelId: String,
            device: String,
            tokensPerSecond: Double,
            benchmarkedAt: Long,
        ) {
            lastSavedModelId = modelId
            lastSavedDevice = device
            lastSavedTokensPerSecond = tokensPerSecond
            lastSavedBenchmarkedAt = benchmarkedAt
        }

        override suspend fun deleteByModelId(modelId: String) {
            // No-op for testing
        }
    }
}
