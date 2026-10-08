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

import com.locus.core.ai.llama.DeviceFingerprintProvider
import com.locus.core.domain.models.ModelMeta
import com.locus.core.domain.models.ModelMetaRepository
import com.locus.core.domain.routing.ModelTier
import com.locus.core.domain.settings.NetworkSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DefaultModelRegistryTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var modelsDir: File
    private lateinit var fakeMetaRepository: FakeModelMetaRepository
    private lateinit var fakeDeviceProvider: FakeDeviceFingerprintProvider
    private lateinit var registry: DefaultModelRegistry

    @Before
    fun setUp() {
        modelsDir = tempFolder.newFolder("models")
        fakeMetaRepository = FakeModelMetaRepository()
        fakeDeviceProvider = FakeDeviceFingerprintProvider("test-device-id")
        registry =
            DefaultModelRegistry(
                modelsDir = modelsDir,
                metaRepository = fakeMetaRepository,
                deviceProvider = fakeDeviceProvider,
            )
    }

    @Test
    fun scansLocalModelsDirectory_findsArbitraryGgufFiles_andEmitsAsLocalEntries() =
        runTest {
            val customGguf = File(modelsDir, "arbitrary-custom-model.gguf")
            customGguf.writeText("simulated binary gguf payload")

            val textFile = File(modelsDir, "notes.txt")
            textFile.writeText("some notes")

            val tmpFile = File(modelsDir, "downloading-model.gguf.tmp")
            tmpFile.writeText("incomplete download")

            val models = registry.observeModels().first()

            val localEntry = models.find { it.ref.id == "arbitrary-custom-model.gguf" }
            assertNotNull("Arbitrary GGUF must be selectable in the registry", localEntry)
            assertEquals(ModelTier.LOCAL, localEntry?.ref?.tier)
            assertNull(localEntry?.ref?.providerId)
            assertTrue(localEntry?.isOffline == true)
            assertEquals(DefaultModelRegistry.DEFAULT_LOCAL_CONTEXT_LENGTH, localEntry?.contextLength)
            assertNull(localEntry?.capabilities)
            assertNull(localEntry?.benchmarkedTokPerSecond)

            assertNull("Non-gguf files must not be included", models.find { it.ref.id == "notes.txt" })
            assertNull(
                "Tmp download files must not be included",
                models.find { it.ref.id.endsWith(".tmp") },
            )
        }

    @Test
    fun readsConfiguredCloudProviders_emitsDeclaredModels() =
        runTest {
            val models = registry.observeModels().first()

            val cloudModels = models.filter { it.ref.tier == ModelTier.CLOUD }
            assertFalse("Cloud models must be declared and available", cloudModels.isEmpty())

            val sonnet = cloudModels.find { it.ref.id == "claude-3-5-sonnet-latest" }
            assertNotNull("Anthropic Claude 3.5 Sonnet must be present", sonnet)
            assertEquals("anthropic", sonnet?.ref?.providerId)
            assertFalse(sonnet?.isOffline == true)
            assertEquals(200_000, sonnet?.contextLength)
            assertEquals(3.0, sonnet?.capabilities?.pricePerMillionInputTokens!!, 0.001)

            val flash = cloudModels.find { it.ref.id == "gemini-1.5-flash" }
            assertNotNull("Google Gemini 1.5 Flash must be present", flash)
            assertEquals("google", flash?.ref?.providerId)
            assertFalse(flash?.isOffline == true)
            assertEquals(1_048_576, flash?.contextLength)
            assertEquals(0.075, flash?.capabilities?.pricePerMillionInputTokens!!, 0.001)

            val gpt4o = cloudModels.find { it.ref.id == "gpt-4o" }
            assertNotNull("OpenAI GPT-4o must be present", gpt4o)
            assertEquals("openai", gpt4o?.ref?.providerId)
            assertFalse(gpt4o?.isOffline == true)
            assertEquals(128_000, gpt4o?.contextLength)
            assertEquals(2.50, gpt4o?.capabilities?.pricePerMillionInputTokens!!, 0.001)
        }

    @Test
    fun attachesBenchmarkResult_fromMetaRepository_whenAvailable() =
        runTest {
            val modelFile = File(modelsDir, "benchmark-test.gguf")
            modelFile.writeText("simulated model")

            fakeMetaRepository.saveBenchmarkResult(
                modelId = "benchmark-test.gguf",
                device = "test-device-id",
                tokensPerSecond = 24.8,
                benchmarkedAt = 123456789L,
            )

            val models = registry.observeModels().first()
            val entry = models.find { it.ref.id == "benchmark-test.gguf" }

            assertNotNull(entry)
            assertEquals(24.8, entry?.benchmarkedTokPerSecond!!, 0.001)
        }

    @Test
    fun manuallyCopyingArbitraryGguf_makesItSelectableInPicker() =
        runTest {
            val initialModels = registry.observeModels().first()
            val localCountBefore = initialModels.count { it.ref.tier == ModelTier.LOCAL }
            assertEquals(0, localCountBefore)

            val newFile = File(modelsDir, "my-downloaded-model-v2.gguf")
            newFile.writeText("binary model data")

            registry.refresh()

            val updatedModels = registry.observeModels().first()
            val localEntry = updatedModels.find { it.ref.id == "my-downloaded-model-v2.gguf" }

            assertNotNull(
                "Manually copied .gguf file must become selectable in the registry",
                localEntry,
            )
            assertEquals(ModelTier.LOCAL, localEntry?.ref?.tier)
            assertTrue(localEntry?.isOffline == true)
        }

    @Test
    fun handlesEmptyFilesAndMissingDirectoryGracefully() =
        runTest {
            val emptyFile = File(modelsDir, "empty-corrupted.gguf")
            emptyFile.writeText("")

            val models = registry.observeModels().first()
            assertNull(
                "0-byte files should not be included in available models",
                models.find { it.ref.id == "empty-corrupted.gguf" },
            )

            val missingDirRegistry =
                DefaultModelRegistry(
                    modelsDir = File(tempFolder.root, "non_existent_dir"),
                    metaRepository = fakeMetaRepository,
                    deviceProvider = fakeDeviceProvider,
                )

            val modelsFromMissing = missingDirRegistry.observeModels().first()
            assertTrue(
                "Should still return cloud models when local dir is missing",
                modelsFromMissing.isNotEmpty(),
            )
            assertTrue(modelsFromMissing.none { it.ref.tier == ModelTier.LOCAL })
        }

    @Test
    fun whenCloudDisabled_cloudModelsAreExcluded() =
        runTest {
            val localGguf = File(modelsDir, "local-model.gguf")
            localGguf.writeText("simulated payload")

            val fakeNetworkStore = FakeNetworkSettingsStore(initialCloudDisabled = false)
            val reg =
                DefaultModelRegistry(
                    modelsDir = modelsDir,
                    metaRepository = fakeMetaRepository,
                    deviceProvider = fakeDeviceProvider,
                    networkSettingsStore = fakeNetworkStore,
                )

            val initialModels = reg.observeModels().first()
            assertTrue(initialModels.any { it.ref.tier == ModelTier.CLOUD })
            assertTrue(initialModels.any { it.ref.tier == ModelTier.LOCAL })

            fakeNetworkStore.setCloudDisabled(true)
            val offlineModels = reg.observeModels().first()
            assertFalse(offlineModels.any { it.ref.tier == ModelTier.CLOUD })
            assertTrue(offlineModels.any { it.ref.tier == ModelTier.LOCAL })
        }

    private class FakeModelMetaRepository : ModelMetaRepository {
        private val metaMap = mutableMapOf<Pair<String, String>, ModelMeta>()
        private val flow = MutableStateFlow<List<ModelMeta>>(emptyList())

        override fun observeModelMeta(
            modelId: String,
            device: String,
        ): Flow<ModelMeta?> = MutableStateFlow(metaMap[modelId to device])

        override fun observeAll(device: String): Flow<List<ModelMeta>> = flow.asStateFlow()

        override suspend fun getModelMeta(
            modelId: String,
            device: String,
        ): ModelMeta? = metaMap[modelId to device]

        override suspend fun saveNotesAndRating(
            modelId: String,
            device: String,
            notes: String,
            rating: Int,
        ) {
            val existing =
                metaMap[modelId to device] ?: ModelMeta(modelId = modelId, device = device)
            val updated = existing.copy(notes = notes, rating = rating)
            metaMap[modelId to device] = updated
            flow.value = metaMap.values.toList()
        }

        override suspend fun saveBenchmarkResult(
            modelId: String,
            device: String,
            tokensPerSecond: Double,
            benchmarkedAt: Long,
        ) {
            val existing =
                metaMap[modelId to device] ?: ModelMeta(modelId = modelId, device = device)
            val updated =
                existing.copy(tokensPerSecond = tokensPerSecond, benchmarkedAt = benchmarkedAt)
            metaMap[modelId to device] = updated
            flow.value = metaMap.values.toList()
        }

        override suspend fun deleteByModelId(modelId: String) {
            val keysToRemove = metaMap.keys.filter { it.first == modelId }
            keysToRemove.forEach { metaMap.remove(it) }
            flow.value = metaMap.values.toList()
        }
    }

    private class FakeDeviceFingerprintProvider(
        private val fingerprint: String = "fake-device",
    ) : DeviceFingerprintProvider {
        override fun getDeviceFingerprint(): String = fingerprint
    }

    private class FakeNetworkSettingsStore(
        initialCloudDisabled: Boolean = false,
    ) : NetworkSettingsStore {
        private val _isCloudDisabled = MutableStateFlow(initialCloudDisabled)
        override val isCloudDisabled: Flow<Boolean> = _isCloudDisabled

        override suspend fun setCloudDisabled(disabled: Boolean) {
            _isCloudDisabled.value = disabled
        }
    }
}
