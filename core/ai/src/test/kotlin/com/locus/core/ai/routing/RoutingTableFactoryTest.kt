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
import com.locus.core.ai.catalog.ModelCatalog
import com.locus.core.ai.catalog.RoutingDefaults
import com.locus.core.domain.models.ModelRegistry
import com.locus.core.domain.models.RegistryEntry
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.ModelTier
import com.locus.core.domain.routing.TaskType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoutingTableFactoryTest {
    private lateinit var fakeRegistry: FakeModelRegistry
    private lateinit var fakeCatalogRepository: FakeCatalogRepository
    private lateinit var factory: RoutingTableFactory

    private val catalog =
        ModelCatalog(
            schemaVersion = 1,
            chat =
                listOf(
                    CatalogEntry(
                        id = "qwen3-4b",
                        name = "Qwen 3 4B",
                        repo = "Qwen/Qwen3-4B-GGUF",
                        filename = "qwen3-4b.gguf",
                        sha256 = "dummy-sha",
                        sizeBytes = 2_000_000_000L,
                        contextLength = 4096,
                    ),
                ),
            utility =
                listOf(
                    CatalogEntry(
                        id = "qwen3-1.7b",
                        name = "Qwen 3 1.7B",
                        repo = "Qwen/Qwen3-1.7B-GGUF",
                        filename = "qwen3-1.7b.gguf",
                        sha256 = "dummy-sha",
                        sizeBytes = 1_000_000_000L,
                        contextLength = 2048,
                    ),
                ),
            routingDefaults =
                RoutingDefaults(
                    chat = "qwen3-4b",
                    utility = "qwen3-1.7b",
                    embeddings = "nomic-embed",
                ),
        )

    private val localChatEntry =
        RegistryEntry(
            ref = ModelRef(id = "qwen3-4b", tier = ModelTier.LOCAL, providerId = null),
            contextLength = 4096,
            capabilities = null,
            isOffline = true,
            benchmarkedTokPerSecond = 25.0,
        )

    private val localUtilityEntry =
        RegistryEntry(
            ref = ModelRef(id = "qwen3-1.7b", tier = ModelTier.LOCAL, providerId = null),
            contextLength = 2048,
            capabilities = null,
            isOffline = true,
            benchmarkedTokPerSecond = 45.0,
        )

    private val cheapCloudEntry =
        RegistryEntry(
            ref =
                ModelRef(
                    id = "gpt-4o-mini",
                    tier = ModelTier.CLOUD,
                    providerId = "openai",
                ),
            contextLength = 128_000,
            capabilities =
                ProviderCapabilities(
                    supportsNativeTools = true,
                    contextLength = 128_000,
                    pricePerMillionInputTokens = 0.15,
                    pricePerMillionOutputTokens = 0.60,
                ),
            isOffline = false,
            benchmarkedTokPerSecond = null,
        )

    private val strongCloudEntry =
        RegistryEntry(
            ref = ModelRef(id = "gpt-4o", tier = ModelTier.CLOUD, providerId = "openai"),
            contextLength = 128_000,
            capabilities =
                ProviderCapabilities(
                    supportsNativeTools = true,
                    contextLength = 128_000,
                    pricePerMillionInputTokens = 2.50,
                    pricePerMillionOutputTokens = 10.00,
                ),
            isOffline = false,
            benchmarkedTokPerSecond = null,
        )

    private val strongestCloudEntry =
        RegistryEntry(
            ref = ModelRef(id = "o1", tier = ModelTier.CLOUD, providerId = "openai"),
            contextLength = 200_000,
            capabilities =
                ProviderCapabilities(
                    supportsNativeTools = true,
                    contextLength = 200_000,
                    pricePerMillionInputTokens = 15.00,
                    pricePerMillionOutputTokens = 60.00,
                ),
            isOffline = false,
            benchmarkedTokPerSecond = null,
        )

    @Before
    fun setUp() {
        fakeRegistry = FakeModelRegistry()
        fakeCatalogRepository = FakeCatalogRepository(catalog)

        factory =
            RoutingTableFactory(
                modelRegistry = fakeRegistry,
                catalogRepository = fakeCatalogRepository,
                recommendationRanker = null,
            )
    }

    @Test
    fun create_resolvesAllSlots_withLocalFallbacks() =
        runTest {
            fakeRegistry.models =
                listOf(
                    localChatEntry,
                    localUtilityEntry,
                    cheapCloudEntry,
                    strongCloudEntry,
                    strongestCloudEntry,
                )

            val table = factory.create()

            val chatPolicy = table.policyFor(TaskType.CHAT_RAG_QA)
            assertEquals(ModelTier.LOCAL, chatPolicy.default.tier)
            assertEquals(localChatEntry.ref, chatPolicy.default)
            assertEquals(strongCloudEntry.ref, chatPolicy.upgrade)
            assertEquals(ModelTier.LOCAL, chatPolicy.fallback.tier)
            assertEquals(localChatEntry.ref, chatPolicy.fallback)

            val digestPolicy = table.policyFor(TaskType.DIGEST_TAGGING_CLUSTER_LABEL)
            assertEquals(ModelTier.LOCAL, digestPolicy.default.tier)
            assertEquals(localUtilityEntry.ref, digestPolicy.default)
            assertEquals(cheapCloudEntry.ref, digestPolicy.upgrade)
            assertEquals(ModelTier.LOCAL, digestPolicy.fallback.tier)
            assertEquals(localChatEntry.ref, digestPolicy.fallback)

            val agenticPolicy = table.policyFor(TaskType.AGENTIC_MULTI_STEP)
            assertEquals(strongestCloudEntry.ref, agenticPolicy.default)
            assertNull(agenticPolicy.upgrade)
            assertEquals(ModelTier.LOCAL, agenticPolicy.fallback.tier)
            assertEquals(localChatEntry.ref, agenticPolicy.fallback)
        }

    @Test
    fun create_fallsBackToCatalogDefaults_whenNoLocalModelsInRegistry() =
        runTest {
            fakeRegistry.models = listOf(cheapCloudEntry, strongCloudEntry)

            val table = factory.create()

            val chatPolicy = table.policyFor(TaskType.CHAT_RAG_QA)
            assertEquals("qwen3-4b", chatPolicy.default.id)
            assertEquals(ModelTier.LOCAL, chatPolicy.default.tier)
            assertEquals("qwen3-4b", chatPolicy.fallback.id)
            assertEquals(ModelTier.LOCAL, chatPolicy.fallback.tier)

            val digestPolicy = table.policyFor(TaskType.DIGEST_TAGGING_CLUSTER_LABEL)
            assertEquals("qwen3-1.7b", digestPolicy.default.id)
            assertEquals(ModelTier.LOCAL, digestPolicy.default.tier)
            assertEquals("qwen3-4b", digestPolicy.fallback.id)
            assertEquals(ModelTier.LOCAL, digestPolicy.fallback.tier)
        }

    @Test
    fun create_respectsPreferredStrongCloudModel() =
        runTest {
            val customStrong =
                ModelRef(
                    id = "claude-3-5-sonnet-latest",
                    tier = ModelTier.CLOUD,
                    providerId = "anthropic",
                )
            val customStrongEntry =
                RegistryEntry(
                    ref = customStrong,
                    contextLength = 200_000,
                    capabilities =
                        ProviderCapabilities(
                            supportsNativeTools = true,
                            contextLength = 200_000,
                            pricePerMillionInputTokens = 3.0,
                            pricePerMillionOutputTokens = 15.0,
                        ),
                    isOffline = false,
                    benchmarkedTokPerSecond = null,
                )

            fakeRegistry.models =
                listOf(
                    localChatEntry,
                    localUtilityEntry,
                    cheapCloudEntry,
                    strongCloudEntry,
                    customStrongEntry,
                )

            val table = factory.create(preferredStrongCloudModel = customStrong)
            val chatPolicy = table.policyFor(TaskType.CHAT_RAG_QA)

            assertEquals(customStrong, chatPolicy.upgrade)
        }

    @Test
    fun create_handlesEmptyCloudRegistry_gracefully() =
        runTest {
            fakeRegistry.models = listOf(localChatEntry, localUtilityEntry)

            val table = factory.create()

            val chatPolicy = table.policyFor(TaskType.CHAT_RAG_QA)
            assertEquals(localChatEntry.ref, chatPolicy.default)
            assertNull(chatPolicy.upgrade)
            assertEquals(localChatEntry.ref, chatPolicy.fallback)

            val digestPolicy = table.policyFor(TaskType.DIGEST_TAGGING_CLUSTER_LABEL)
            assertEquals(localUtilityEntry.ref, digestPolicy.default)
            assertNull(digestPolicy.upgrade)
            assertEquals(localChatEntry.ref, digestPolicy.fallback)

            val agenticPolicy = table.policyFor(TaskType.AGENTIC_MULTI_STEP)
            assertEquals(localChatEntry.ref, agenticPolicy.default)
            assertNull(agenticPolicy.upgrade)
            assertEquals(localChatEntry.ref, agenticPolicy.fallback)
        }

    private class FakeModelRegistry(
        var models: List<RegistryEntry> = emptyList(),
    ) : ModelRegistry {
        override fun observeModels(): Flow<List<RegistryEntry>> = flowOf(models)

        override suspend fun getModels(): List<RegistryEntry> = models
    }

    private class FakeCatalogRepository(
        private val fakeCatalog: ModelCatalog,
    ) : CatalogRepository(
            context = RuntimeEnvironment.getApplication(),
            okHttpClient = OkHttpClient(),
            assetLoader = { null },
        ) {
        override fun current(): Flow<ModelCatalog> = flowOf(fakeCatalog)

        override suspend fun refreshFromRemote(): Result<Unit> = Result.success(Unit)
    }
}
