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

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** P-4 task capability requirements for matching against recommendation candidates. */
data class TaskRequirements(
    val task: String = "",
    val requiresTools: Boolean = false,
    val minContextLength: Int = 0,
)

/** Result of an estimated re-index time computation for an embedding model switch (S-4, M-9). */
data class ReindexTimeEstimate(
    val modelId: String,
    val totalChunkCount: Int,
    val tokensPerSecond: Double,
    val isMeasured: Boolean,
    val estimatedSeconds: Double,
)

/**
 * Domain use case responsible for device- and task-aware recommendation ranking (M-9):
 * 1. Gates candidates by available RAM and storage headroom from [DeviceCapabilitiesGateway].
 * 2. Ranks eligible entries by measured tok/s from [ModelMetaRepository], falling back to a
 * ```
 *    model-size speed heuristic when no benchmark exists on the active device.
 * ```
 * 3. Matches task capabilities (tool support, context length) to satisfy P-4 requirements.
 * 4. Estimates re-index duration `(total chunk count) / (tok/s)` for embedding model switches.
 */
@Singleton
class RecommendationRanker
    @Inject
    constructor(
        private val capabilitiesGateway: DeviceCapabilitiesGateway,
        private val metaRepository: ModelMetaRepository,
    ) {
        companion object {
            private const val DEFAULT_SPEED_TOK_PER_SEC = 10.0
            private const val SPEED_HEURISTIC_NUMERATOR = 30_000_000_000.0
            private const val SPEED_MIN_TOK_PER_SEC = 1.0
            private const val SPEED_MAX_TOK_PER_SEC = 200.0
            private const val SPEED_TOLERANCE = 0.001
        }

        /**
         * Checks whether a model with [sizeBytes] fits the given [availableRam] and [availableStorage]
         * headroom. If a headroom value is non-positive (unconstrained or unreported), it is treated as
         * unlimited.
         */
        fun fitsHeadroom(
            sizeBytes: Long,
            availableRam: Long,
            availableStorage: Long,
        ): Boolean {
            val fitsRam = availableRam <= 0L || sizeBytes <= availableRam
            val fitsStorage = availableStorage <= 0L || sizeBytes <= availableStorage
            return sizeBytes <= 0L || (fitsRam && fitsStorage)
        }

        /**
         * Size-based speed heuristic estimating tokens/second when no benchmark exists on the device.
         * Models with smaller file sizes process tokens faster on CPU/mobile.
         */
        fun estimateTokensPerSecond(sizeBytes: Long): Double {
            if (sizeBytes <= 0L) return DEFAULT_SPEED_TOK_PER_SEC
            return (SPEED_HEURISTIC_NUMERATOR / sizeBytes.toDouble()).coerceIn(
                SPEED_MIN_TOK_PER_SEC,
                SPEED_MAX_TOK_PER_SEC,
            )
        }

        /**
         * Resolves the effective throughput in tokens/second for a model candidate on the active device:
         * Uses measured benchmark from [ModelMetaRepository] if available; otherwise falls back to the
         * size-based heuristic.
         */
        suspend fun getEffectiveTokPerSec(
            candidate: ModelRecommendation,
            device: String,
        ): Double {
            val measured =
                metaRepository.getModelMeta(candidate.filename, device)?.tokensPerSecond?.takeIf {
                    it > 0.0
                }
                    ?: metaRepository.getModelMeta(candidate.id, device)?.tokensPerSecond?.takeIf {
                        it > 0.0
                    }
            return measured ?: estimateTokensPerSecond(candidate.sizeBytes)
        }

        /**
         * Filters catalog candidates that fit available device RAM/storage headroom, then ranks eligible
         * models by capability match against [taskRequirements], measured or estimated tok/s, and context
         * length.
         */
        suspend fun rank(
            candidates: List<ModelRecommendation>,
            taskRequirements: TaskRequirements = TaskRequirements(),
        ): List<ModelRecommendation> {
            val availableRam = capabilitiesGateway.getAvailableRamBytes()
            val availableStorage = capabilitiesGateway.getAvailableStorageBytes()
            val device = capabilitiesGateway.getDeviceFingerprint()

            val fitting =
                candidates.filter { candidate ->
                    fitsHeadroom(candidate.sizeBytes, availableRam, availableStorage)
                }

            val speedMap = fitting.associate { it.id to getEffectiveTokPerSec(it, device) }
            return fitting.sortedWith { a, b -> compareCandidates(a, b, speedMap, taskRequirements) }
        }

        private fun compareCandidates(
            a: ModelRecommendation,
            b: ModelRecommendation,
            speedMap: Map<String, Double>,
            taskRequirements: TaskRequirements,
        ): Int {
            val toolComp =
                if (taskRequirements.requiresTools) {
                    b.supportsTools.compareTo(a.supportsTools)
                } else {
                    0
                }
            val ctxComp =
                if (taskRequirements.minContextLength > 0) {
                    val meetsA = if (a.contextLength >= taskRequirements.minContextLength) 1 else 0
                    val meetsB = if (b.contextLength >= taskRequirements.minContextLength) 1 else 0
                    meetsB.compareTo(meetsA)
                } else {
                    0
                }
            val speedA = speedMap[a.id] ?: 0.0
            val speedB = speedMap[b.id] ?: 0.0
            val speedComp =
                if (abs(speedA - speedB) > SPEED_TOLERANCE) {
                    speedB.compareTo(speedA)
                } else {
                    0
                }

            return sequenceOf(
                toolComp,
                ctxComp,
                speedComp,
                b.contextLength.compareTo(a.contextLength),
                b.supportsTools.compareTo(a.supportsTools),
                a.id.compareTo(b.id),
            ).firstOrNull { it != 0 }
                ?: 0
        }

        /**
         * Calculates estimated re-index time in seconds as `(total chunk count) / (tok/s)`. Scales
         * linearly with [totalChunkCount].
         */
        fun estimateReindexTimeSeconds(
            totalChunkCount: Int,
            tokPerSecond: Double,
        ): Double {
            if (totalChunkCount <= 0 || tokPerSecond <= 0.0) return 0.0
            return totalChunkCount.toDouble() / tokPerSecond
        }

        /** Computes full [ReindexTimeEstimate] for switching to [modelId] on the active device. */
        suspend fun estimateReindexTime(
            modelId: String,
            totalChunkCount: Int,
            modelSizeBytes: Long = 0L,
        ): ReindexTimeEstimate {
            val device = capabilitiesGateway.getDeviceFingerprint()
            val meta = metaRepository.getModelMeta(modelId, device)
            val measuredTokPerSec = meta?.tokensPerSecond?.takeIf { it > 0.0 }
            val effectiveTokPerSec = measuredTokPerSec ?: estimateTokensPerSecond(modelSizeBytes)
            val estimatedSeconds = estimateReindexTimeSeconds(totalChunkCount, effectiveTokPerSec)

            return ReindexTimeEstimate(
                modelId = modelId,
                totalChunkCount = totalChunkCount,
                tokensPerSecond = effectiveTokPerSec,
                isMeasured = measuredTokPerSec != null,
                estimatedSeconds = estimatedSeconds,
            )
        }
    }
