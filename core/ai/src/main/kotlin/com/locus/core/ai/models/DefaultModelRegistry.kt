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

import com.locus.core.ai.llama.DefaultDeviceFingerprintProvider
import com.locus.core.ai.llama.DeviceFingerprintProvider
import com.locus.core.ai.llama.ModelDownloader
import com.locus.core.ai.providers.AnthropicAdapter
import com.locus.core.ai.providers.GeminiAdapter
import com.locus.core.domain.models.ModelMeta
import com.locus.core.domain.models.ModelMetaRepository
import com.locus.core.domain.models.ModelRegistry
import com.locus.core.domain.models.RegistryEntry
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.ModelTier
import com.locus.core.domain.settings.NetworkSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultModelRegistry
    @Inject
    constructor(
        private val modelDownloader: ModelDownloader?,
        private val metaRepository: ModelMetaRepository,
        private val deviceProvider: DeviceFingerprintProvider,
        private val providerAdapter: ProviderAdapter? = null,
        private val networkSettingsStore: NetworkSettingsStore? = null,
    ) : ModelRegistry {
        private var explicitModelsDir: File? = null

        /** Secondary constructor for unit testing without Android ApplicationContext. */
        constructor(
            modelsDir: File,
            metaRepository: ModelMetaRepository,
            deviceProvider: DeviceFingerprintProvider = DefaultDeviceFingerprintProvider(),
            providerAdapter: ProviderAdapter? = null,
            networkSettingsStore: NetworkSettingsStore? = null,
        ) : this(
            modelDownloader = null,
            metaRepository = metaRepository,
            deviceProvider = deviceProvider,
            providerAdapter = providerAdapter,
            networkSettingsStore = networkSettingsStore,
        ) {
            this.explicitModelsDir = modelsDir
        }

        private val refreshTrigger = MutableStateFlow(0L)

        fun refresh() {
            refreshTrigger.update { it + 1 }
        }

        override fun observeModels(): Flow<List<RegistryEntry>> {
            val device = deviceProvider.getDeviceFingerprint()
            val cloudDisabledFlow = networkSettingsStore?.isCloudDisabled ?: flowOf(false)
            return combine(
                metaRepository.observeAll(device),
                refreshTrigger,
                cloudDisabledFlow,
            ) { metaList, _, isCloudDisabled ->
                val metaMap = metaList.associateBy { it.modelId }
                val localEntries = buildLocalEntries(metaMap)
                val cloudEntries = if (isCloudDisabled) emptyList() else buildCloudEntries()
                localEntries + cloudEntries
            }
        }

        override suspend fun getModels(): List<RegistryEntry> = observeModels().first()

        private fun getModelsDirectory(): File? = explicitModelsDir ?: modelDownloader?.getModelsDirectory()

        private fun buildLocalEntries(metaMap: Map<String, ModelMeta>): List<RegistryEntry> {
            val dir = getModelsDirectory()
            if (dir == null || !dir.exists() || !dir.isDirectory) {
                return emptyList()
            }
            val files =
                dir
                    .listFiles { file ->
                        file.isFile &&
                            file.name.endsWith(GGUF_EXTENSION, ignoreCase = true) &&
                            !file.name.endsWith(TMP_EXTENSION, ignoreCase = true) &&
                            file.length() > 0L
                    }?.sortedBy { it.name }
                    ?: emptyList()

            return files.map { file ->
                val meta = metaMap[file.name]
                val tokPerSecond = meta?.tokensPerSecond?.takeIf { it > 0.0 }
                val contextLength =
                    GgufMetadataReader.readContextLength(file, DEFAULT_LOCAL_CONTEXT_LENGTH)

                RegistryEntry(
                    ref =
                        ModelRef(
                            id = file.name,
                            tier = ModelTier.LOCAL,
                            providerId = null,
                        ),
                    contextLength = contextLength,
                    capabilities = null,
                    isOffline = true,
                    benchmarkedTokPerSecond = tokPerSecond,
                )
            }
        }

        private fun buildCloudEntries(): List<RegistryEntry> {
            val entries = mutableListOf<RegistryEntry>()
            entries.addAll(buildAnthropicModels())
            entries.addAll(buildGoogleModels())
            entries.addAll(buildOpenAiModels())
            return entries
        }

        private fun buildAnthropicModels(): List<RegistryEntry> =
            ANTHROPIC_MODELS.map { modelId ->
                val caps = AnthropicAdapter.lookupCapabilities(modelId)
                RegistryEntry(
                    ref =
                        ModelRef(
                            id = modelId,
                            tier = ModelTier.CLOUD,
                            providerId = PROVIDER_ANTHROPIC,
                        ),
                    contextLength = caps.contextLength,
                    capabilities = caps,
                    isOffline = false,
                    benchmarkedTokPerSecond = null,
                )
            }

        private fun buildGoogleModels(): List<RegistryEntry> =
            GOOGLE_MODELS.map { modelId ->
                val caps = GeminiAdapter.lookupCapabilities(modelId)
                RegistryEntry(
                    ref =
                        ModelRef(
                            id = modelId,
                            tier = ModelTier.CLOUD,
                            providerId = PROVIDER_GOOGLE,
                        ),
                    contextLength = caps.contextLength,
                    capabilities = caps,
                    isOffline = false,
                    benchmarkedTokPerSecond = null,
                )
            }

        private fun buildOpenAiModels(): List<RegistryEntry> =
            OPENAI_MODELS.map { (modelId, caps) ->
                RegistryEntry(
                    ref =
                        ModelRef(
                            id = modelId,
                            tier = ModelTier.CLOUD,
                            providerId = PROVIDER_OPENAI,
                        ),
                    contextLength = caps.contextLength,
                    capabilities = caps,
                    isOffline = false,
                    benchmarkedTokPerSecond = null,
                )
            }

        companion object {
            const val DEFAULT_LOCAL_CONTEXT_LENGTH = 8192
            const val PROVIDER_ANTHROPIC = "anthropic"
            const val PROVIDER_GOOGLE = "google"
            const val PROVIDER_OPENAI = "openai"

            private const val GGUF_EXTENSION = ".gguf"
            private const val TMP_EXTENSION = ".tmp"

            val ANTHROPIC_MODELS =
                listOf(
                    "claude-3-7-sonnet-latest",
                    "claude-3-5-sonnet-latest",
                    "claude-3-5-haiku-latest",
                    "claude-3-opus-latest",
                    "claude-3-haiku-20240307",
                )

            val GOOGLE_MODELS =
                listOf(
                    "gemini-2.0-flash",
                    "gemini-1.5-pro",
                    "gemini-1.5-flash",
                    "gemini-1.5-flash-8b",
                    "gemini-3.5-flash-lite",
                    "gemini-1.0-pro",
                )

            val OPENAI_MODELS =
                listOf(
                    "gpt-4o" to
                        ProviderCapabilities(
                            supportsNativeTools = true,
                            contextLength = 128_000,
                            pricePerMillionInputTokens = 2.50,
                            pricePerMillionOutputTokens = 10.00,
                        ),
                    "gpt-4o-mini" to
                        ProviderCapabilities(
                            supportsNativeTools = true,
                            contextLength = 128_000,
                            pricePerMillionInputTokens = 0.15,
                            pricePerMillionOutputTokens = 0.60,
                        ),
                    "o1" to
                        ProviderCapabilities(
                            supportsNativeTools = true,
                            contextLength = 200_000,
                            pricePerMillionInputTokens = 15.00,
                            pricePerMillionOutputTokens = 60.00,
                        ),
                    "o3-mini" to
                        ProviderCapabilities(
                            supportsNativeTools = true,
                            contextLength = 200_000,
                            pricePerMillionInputTokens = 1.10,
                            pricePerMillionOutputTokens = 4.40,
                        ),
                    "gpt-4-turbo" to
                        ProviderCapabilities(
                            supportsNativeTools = true,
                            contextLength = 128_000,
                            pricePerMillionInputTokens = 10.00,
                            pricePerMillionOutputTokens = 30.00,
                        ),
                )
        }
    }

@Suppress(
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
)
internal object GgufMetadataReader {
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)
    private const val MIN_HEADER_BYTES = 24L
    private const val MAX_SCAN_ENTRIES = 256L
    private const val MAX_KEY_LENGTH = 1024L
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11

    fun readContextLength(
        file: File,
        defaultContextLength: Int,
    ): Int {
        if (!file.exists() || file.length() < MIN_HEADER_BYTES) return defaultContextLength
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (!magic.contentEquals(GGUF_MAGIC)) return@use defaultContextLength

                val version = raf.readIntLe()
                if (version < 2 || version > 3) return@use defaultContextLength

                raf.readLongLe() // tensor count
                val metadataKvCount = raf.readLongLe()

                val maxKv = minOf(metadataKvCount, MAX_SCAN_ENTRIES)
                var entriesChecked = 0
                while (entriesChecked < maxKv) {
                    entriesChecked++
                    val keyLen = raf.readLongLe()
                    if (keyLen <= 0 || keyLen > MAX_KEY_LENGTH) break
                    val keyBytes = ByteArray(keyLen.toInt())
                    raf.readFully(keyBytes)
                    val key = String(keyBytes, Charsets.UTF_8)

                    val valueType = raf.readIntLe()
                    if (key.endsWith(".context_length", ignoreCase = true)) {
                        return@use when (valueType) {
                            TYPE_UINT32, TYPE_INT32 -> raf.readIntLe()
                            TYPE_UINT64, TYPE_INT64 -> raf.readLongLe().toInt()
                            else -> defaultContextLength
                        }
                    } else {
                        if (!skipValue(raf, valueType)) break
                    }
                }
                defaultContextLength
            }
        }.getOrDefault(defaultContextLength)
    }

    private fun skipValue(
        raf: RandomAccessFile,
        type: Int,
    ): Boolean {
        when (type) {
            0, 1, 7 -> raf.skipBytes(1)
            2, 3 -> raf.skipBytes(2)
            TYPE_UINT32, TYPE_INT32, 6 -> raf.skipBytes(4)
            TYPE_STRING -> {
                val len = raf.readLongLe()
                if (len < 0 || len > 10_000_000L) return false
                raf.skipBytes(len.toInt())
            }
            TYPE_ARRAY -> {
                val itemType = raf.readIntLe()
                val arrayLen = raf.readLongLe()
                if (arrayLen < 0 || arrayLen > 100_000L) return false
                repeat(arrayLen.toInt()) { if (!skipValue(raf, itemType)) return false }
            }
            TYPE_UINT64, TYPE_INT64, 12 -> raf.skipBytes(8)
            else -> return false
        }
        return true
    }

    private fun RandomAccessFile.readIntLe(): Int {
        val b1 = read()
        val b2 = read()
        val b3 = read()
        val b4 = read()
        if ((b1 or b2 or b3 or b4) < 0) throw EOFException()
        return (b1 and 0xFF) or
            ((b2 and 0xFF) shl 8) or
            ((b3 and 0xFF) shl 16) or
            ((b4 and 0xFF) shl 24)
    }

    private fun RandomAccessFile.readLongLe(): Long {
        val low = readIntLe().toLong() and 0xFFFFFFFFL
        val high = readIntLe().toLong() and 0xFFFFFFFFL
        return (high shl 32) or low
    }
}
