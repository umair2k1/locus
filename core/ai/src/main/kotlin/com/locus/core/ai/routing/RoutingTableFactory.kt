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

package com.locus.core.ai.routing

import com.locus.core.ai.catalog.CatalogEntry
import com.locus.core.ai.catalog.CatalogRepository
import com.locus.core.domain.models.ModelRecommendation
import com.locus.core.domain.models.ModelRegistry
import com.locus.core.domain.models.RecommendationRanker
import com.locus.core.domain.models.RegistryEntry
import com.locus.core.domain.models.TaskRequirements
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.ModelTier
import com.locus.core.domain.routing.RoutingTable
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [RoutingTable] from the current [ModelRegistry] and [CatalogRepository] state.
 *
 * Resolves which concrete [ModelRef]s fill each slot:
 * - localUtilityModel: on-device utility model (ranked or catalog default)
 * - localChatModel: on-device chat model (ranked or catalog default)
 * - cheapCloudModel: lowest-cost available cloud model
 * - strongCloudModel: user's preferred or standard high-capability cloud model
 * - strongestAvailableCloudModel: highest-capability available cloud model
 *
 * Invariant: every chain always ends at a local model (P-4).
 */
@Suppress("ReturnCount")
@Singleton
class RoutingTableFactory
    @Inject
    constructor(
        private val modelRegistry: ModelRegistry,
        private val catalogRepository: CatalogRepository,
        private val recommendationRanker: RecommendationRanker? = null,
    ) {
        companion object {
            private const val DEFAULT_CHAT_MODEL_ID = "qwen3-4b"
            private const val DEFAULT_UTILITY_MODEL_ID = "qwen3-1.7b"
            private const val CHAT_MIN_CONTEXT_LENGTH = 4096
            private const val UTILITY_MIN_CONTEXT_LENGTH = 2048
            private const val STRONG_TIER_MIN_PRICE = 1.0
            private const val STRONG_TIER_MAX_PRICE = 5.0
        }

        suspend fun create(preferredStrongCloudModel: ModelRef? = null): RoutingTable {
            val catalog = catalogRepository.current().first()
            val registeredModels = modelRegistry.getModels()

            val localModels = registeredModels.filter { it.ref.tier == ModelTier.LOCAL }
            val cloudModels = registeredModels.filter { it.ref.tier == ModelTier.CLOUD }

            val localChatModel =
                resolveLocalChatModel(catalog.chat, catalog.routingDefaults.chat, localModels)
            val localUtilityModel =
                resolveLocalUtilityModel(
                    catalog.utility,
                    catalog.routingDefaults.utility,
                    localModels,
                    localChatModel,
                )

            val cheapCloudModel = resolveCheapCloudModel(cloudModels)
            val strongCloudModel = resolveStrongCloudModel(cloudModels, preferredStrongCloudModel)
            val strongestAvailableCloudModel = resolveStrongestAvailableCloudModel(cloudModels)

            return RoutingTable(
                localUtilityModel = localUtilityModel,
                localChatModel = localChatModel,
                cheapCloudModel = cheapCloudModel,
                strongCloudModel = strongCloudModel,
                strongestAvailableCloudModel = strongestAvailableCloudModel,
            )
        }

        private suspend fun resolveLocalChatModel(
            catalogChat: List<CatalogEntry>,
            defaultChatId: String,
            localModels: List<com.locus.core.domain.models.RegistryEntry>,
        ): ModelRef {
            if (recommendationRanker != null && catalogChat.isNotEmpty()) {
                val chatCandidates = catalogChat.map { it.toDomainRecommendation("Chat") }
                val ranked =
                    recommendationRanker.rank(
                        chatCandidates,
                        TaskRequirements(task = "Chat", minContextLength = CHAT_MIN_CONTEXT_LENGTH),
                    )
                val matched =
                    ranked.firstNotNullOfOrNull { cand ->
                        localModels
                            .find {
                                it.ref.id.equals(cand.id, ignoreCase = true) ||
                                    it.ref.id.contains(cand.id, ignoreCase = true)
                            }?.ref
                    }
                if (matched != null) return matched
            }

            if (defaultChatId.isNotBlank()) {
                val matched =
                    localModels
                        .find {
                            it.ref.id.equals(defaultChatId, ignoreCase = true) ||
                                it.ref.id.contains(defaultChatId, ignoreCase = true)
                        }?.ref
                if (matched != null) return matched
            }

            return localModels.firstOrNull()?.ref
                ?: ModelRef(
                    id =
                        defaultChatId.ifBlank {
                            catalogChat.firstOrNull()?.id ?: DEFAULT_CHAT_MODEL_ID
                        },
                    tier = ModelTier.LOCAL,
                    providerId = null,
                )
        }

        private suspend fun resolveLocalUtilityModel(
            catalogUtility: List<CatalogEntry>,
            defaultUtilityId: String,
            localModels: List<com.locus.core.domain.models.RegistryEntry>,
            localChatModel: ModelRef,
        ): ModelRef {
            if (recommendationRanker != null && catalogUtility.isNotEmpty()) {
                val utilityCandidates = catalogUtility.map { it.toDomainRecommendation("Utility") }
                val ranked =
                    recommendationRanker.rank(
                        utilityCandidates,
                        TaskRequirements(
                            task = "Utility",
                            minContextLength = UTILITY_MIN_CONTEXT_LENGTH,
                        ),
                    )
                val matched =
                    ranked.firstNotNullOfOrNull { cand ->
                        localModels
                            .find {
                                it.ref.id.equals(cand.id, ignoreCase = true) ||
                                    it.ref.id.contains(cand.id, ignoreCase = true)
                            }?.ref
                    }
                if (matched != null) return matched
            }

            if (defaultUtilityId.isNotBlank()) {
                val matched =
                    localModels
                        .find {
                            it.ref.id.equals(defaultUtilityId, ignoreCase = true) ||
                                it.ref.id.contains(defaultUtilityId, ignoreCase = true)
                        }?.ref
                if (matched != null) return matched
            }

            val otherLocal = localModels.firstOrNull { it.ref.id != localChatModel.id }?.ref
            if (otherLocal != null) return otherLocal

            if (localModels.isNotEmpty()) return localModels.first().ref

            return ModelRef(
                id =
                    defaultUtilityId.ifBlank {
                        catalogUtility.firstOrNull()?.id ?: DEFAULT_UTILITY_MODEL_ID
                    },
                tier = ModelTier.LOCAL,
                providerId = null,
            )
        }

        private fun resolveCheapCloudModel(cloudModels: List<RegistryEntry>): ModelRef? {
            if (cloudModels.isEmpty()) return null
            return cloudModels
                .filter { it.capabilities?.pricePerMillionInputTokens != null }
                .minByOrNull { it.capabilities?.pricePerMillionInputTokens ?: Double.MAX_VALUE }
                ?.ref
                ?: cloudModels.firstOrNull()?.ref
        }

        private fun resolveStrongCloudModel(
            cloudModels: List<RegistryEntry>,
            preferred: ModelRef?,
        ): ModelRef? {
            if (cloudModels.isEmpty()) return null
            if (preferred != null && cloudModels.any { it.ref.id == preferred.id }) {
                return preferred
            }
            return cloudModels
                .find {
                    it.ref.id.contains("4o", ignoreCase = true) &&
                        !it.ref.id.contains("mini", ignoreCase = true)
                }?.ref
                ?: cloudModels.find { it.ref.id.contains("sonnet", ignoreCase = true) }?.ref
                ?: cloudModels.find { it.ref.id.contains("1.5-pro", ignoreCase = true) }?.ref
                ?: cloudModels
                    .find {
                        val price = it.capabilities?.pricePerMillionInputTokens ?: 0.0
                        price in STRONG_TIER_MIN_PRICE..STRONG_TIER_MAX_PRICE
                    }?.ref
                ?: cloudModels.firstOrNull()?.ref
        }

        private fun resolveStrongestAvailableCloudModel(cloudModels: List<RegistryEntry>): ModelRef? {
            if (cloudModels.isEmpty()) return null
            return cloudModels
                .find {
                    it.ref.id.contains("o1", ignoreCase = true) ||
                        it.ref.id.contains("3-7-sonnet", ignoreCase = true) ||
                        it.ref.id.contains("opus", ignoreCase = true)
                }?.ref
                ?: cloudModels
                    .filter { it.capabilities?.pricePerMillionInputTokens != null }
                    .maxByOrNull { it.capabilities?.pricePerMillionInputTokens ?: 0.0 }
                    ?.ref
                ?: cloudModels.maxByOrNull { it.contextLength }?.ref
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
