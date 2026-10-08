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

import com.locus.core.domain.models.BenchmarkResult
import com.locus.core.domain.models.ModelMetaRepository
import javax.inject.Inject
import javax.inject.Singleton

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val NANOS_PER_MILLI = 1_000_000L
private const val EMBEDDING_BENCHMARK_ESTIMATED_TOKENS = 35
private const val EMBEDDING_BENCHMARK_ITERATIONS = 5

@Singleton
class ModelBenchmark
    @Inject
    constructor(
        private val llamaRuntime: LlamaRuntime,
        private val metaRepository: ModelMetaRepository,
        private val deviceProvider: DeviceFingerprintProvider,
    ) {
        suspend fun run(
            modelId: String,
            path: String,
        ): BenchmarkResult {
            val isEmbeddingModel =
                modelId.contains("embedding", ignoreCase = true) ||
                    path.contains("embedding", ignoreCase = true)

            return if (isEmbeddingModel) {
                runEmbeddingBenchmark(modelId, path)
            } else {
                runChatBenchmark(modelId, path)
            }
        }

        private suspend fun runChatBenchmark(
            modelId: String,
            path: String,
        ): BenchmarkResult {
            val loadResult = llamaRuntime.loadModel(path, ModelKind.CHAT)
            check(loadResult.isSuccess) {
                "Failed to load model for benchmark from path: $path: ${loadResult.exceptionOrNull()?.message}"
            }

            val cannedPrompt =
                "Explain the core benefits of offline-first software architectures in three concise sentences."
            val samplingParams = SamplingParams(maxTokens = 128, temperature = 0.0)

            val startNanos = System.nanoTime()
            var tokenCount = 0
            llamaRuntime.generateStream(cannedPrompt, samplingParams).collect { _ -> tokenCount++ }
            val elapsedNanos = System.nanoTime() - startNanos
            val elapsedSeconds = elapsedNanos.toDouble() / NANOS_PER_SECOND
            val durationMs = elapsedNanos / NANOS_PER_MILLI
            val tokensPerSecond =
                if (elapsedSeconds > 0.0 && tokenCount > 0) tokenCount / elapsedSeconds else 0.0

            val device = deviceProvider.getDeviceFingerprint()
            val benchmarkedAt = System.currentTimeMillis()
            metaRepository.saveBenchmarkResult(modelId, device, tokensPerSecond, benchmarkedAt)

            return BenchmarkResult(
                tokensPerSecond = tokensPerSecond,
                totalTokens = tokenCount,
                durationMs = durationMs,
            )
        }

        private suspend fun runEmbeddingBenchmark(
            modelId: String,
            path: String,
        ): BenchmarkResult {
            val loadResult = llamaRuntime.loadModel(path, ModelKind.EMBEDDING)
            check(loadResult.isSuccess) {
                "Failed to load embedding model for benchmark from path: $path: " +
                    "${loadResult.exceptionOrNull()?.message}"
            }

            // Benchmark embedding inference throughput on representative chunk text (~100 tokens)
            val cannedChunk =
                "Offline-first software architectures ensure local resilience, low latency, " +
                    "and continuous data accessibility by design. " +
                    "Local-first applications store state on-device and synchronize " +
                    "conflict-free replicas asynchronously when connectivity is present."
            val estimatedChunkTokens = EMBEDDING_BENCHMARK_ESTIMATED_TOKENS

            // Warm-up single pass
            llamaRuntime.embed(cannedChunk)

            val iterations = EMBEDDING_BENCHMARK_ITERATIONS
            val startNanos = System.nanoTime()
            repeat(iterations) {
                val embedResult = llamaRuntime.embed(cannedChunk)
                check(embedResult.isSuccess) {
                    "Failed to generate benchmark embeddings: ${embedResult.exceptionOrNull()?.message}"
                }
            }
            val elapsedNanos = System.nanoTime() - startNanos
            val elapsedSeconds = elapsedNanos.toDouble() / NANOS_PER_SECOND
            val durationMs = elapsedNanos / NANOS_PER_MILLI
            val totalTokens = estimatedChunkTokens * iterations
            val tokensPerSecond =
                if (elapsedSeconds > 0.0 && totalTokens > 0) totalTokens / elapsedSeconds else 0.0

            val device = deviceProvider.getDeviceFingerprint()
            val benchmarkedAt = System.currentTimeMillis()
            metaRepository.saveBenchmarkResult(modelId, device, tokensPerSecond, benchmarkedAt)

            return BenchmarkResult(
                tokensPerSecond = tokensPerSecond,
                totalTokens = totalTokens,
                durationMs = durationMs,
            )
        }
    }
