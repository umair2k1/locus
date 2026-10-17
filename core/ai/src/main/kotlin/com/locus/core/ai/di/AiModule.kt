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

package com.locus.core.ai.di

import android.content.Context
import androidx.work.WorkManager
import com.locus.core.ai.BuildConfig
import com.locus.core.ai.embedding.EmbeddingRunner
import com.locus.core.ai.llama.DefaultDeviceFingerprintProvider
import com.locus.core.ai.llama.DeviceFingerprintProvider
import com.locus.core.ai.llama.LlamaRuntime
import com.locus.core.ai.llama.LocalLlamaChatModelClient
import com.locus.core.ai.llama.ModelDownloader
import com.locus.core.ai.llama.ModelKind
import com.locus.core.ai.llama.ThermalMonitor
import com.locus.core.ai.models.AndroidDeviceCapabilitiesGateway
import com.locus.core.ai.models.DefaultModelManagerRepository
import com.locus.core.ai.models.DefaultModelRegistry
import com.locus.core.ai.providers.GeminiAdapter
import com.locus.core.ai.providers.OpenAiCompatibleAdapter
import com.locus.core.domain.chat.ActiveModelRepository
import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.chat.RagAnswerUseCase
import com.locus.core.domain.dashboard.ComputeClustersUseCase
import com.locus.core.domain.dashboard.ComputeDigestUseCase
import com.locus.core.domain.dashboard.ExtractActionItemsUseCase
import com.locus.core.domain.dashboard.ParseRemindersUseCase
import com.locus.core.domain.models.DeviceCapabilitiesGateway
import com.locus.core.domain.models.ModelManagerRepository
import com.locus.core.domain.models.ModelRegistry
import com.locus.core.domain.notes.InlineAiUseCase
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.SuggestTagsUseCase
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import com.locus.core.domain.reminders.AlarmScheduler
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.EmbeddingGateway
import com.locus.core.domain.search.HybridSearchUseCase
import com.locus.core.domain.settings.NetworkSettingsStore
import com.locus.core.domain.time.Clock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import com.locus.core.domain.chat.ThermalMonitor as DomainThermalMonitor

@Singleton
internal class EmbeddingRunnerGatewayAdapter
    @Inject
    constructor(
        private val runner: EmbeddingRunner,
        private val modelDownloader: ModelDownloader,
        private val runtime: LlamaRuntime,
    ) : EmbeddingGateway {
        private val initMutex = Mutex()

        @Volatile private var isInitialized = false

        override suspend fun embed(text: String): FloatArray {
            if (runtime.loadedModelKind == ModelKind.CHAT) {
                return FloatArray(EmbeddingRunner.TARGET_DIM)
            }
            val loaded = ensureModelLoaded()
            return if (loaded) {
                runCatching { runner.embed(text) }.getOrDefault(FloatArray(EmbeddingRunner.TARGET_DIM))
            } else {
                FloatArray(EmbeddingRunner.TARGET_DIM)
            }
        }

        private fun isEmbeddingReady(): Boolean =
            isInitialized &&
                (runtime.loadedModelKind == null || runtime.loadedModelKind == ModelKind.EMBEDDING)

        private suspend fun ensureModelLoaded(): Boolean {
            if (isEmbeddingReady()) {
                return true
            }
            return initMutex.withLock {
                if (runtime.loadedModelKind == ModelKind.CHAT) {
                    return@withLock false
                }
                if (isEmbeddingReady()) {
                    return@withLock true
                }
                val modelFile = modelDownloader.getModelFile()
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    return@withLock false
                }
                val loadResult = modelDownloader.loadModel(runtime)
                if (loadResult.isSuccess) {
                    isInitialized = true
                    true
                } else {
                    false
                }
            }
        }
    }

internal class RoutingChatModelClient(
    private val cloudClient: ChatModelClient,
    private val localClient: ChatModelClient,
    private val activeModelRepository: ActiveModelRepository,
    private val networkSettingsStore: NetworkSettingsStore,
) : ChatModelClient {
    override fun generate(
        prompt: String,
        tools: List<ToolSchema>,
    ): Flow<StreamEvent> =
        flow {
            val isCloudDisabled = networkSettingsStore.isCloudDisabled.firstOrNull() ?: false
            val isLocalActive = activeModelRepository.getActiveModel().isLocal
            val target = if (isCloudDisabled || isLocalActive) localClient else cloudClient
            target.generate(prompt, tools).collect { emit(it) }
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {
    @Binds
    @Singleton
    internal abstract fun bindEmbeddingGateway(adapter: EmbeddingRunnerGatewayAdapter): EmbeddingGateway

    @Binds
    @Singleton
    @LocalChat
    abstract fun bindLocalChatModelClient(client: LocalLlamaChatModelClient): ChatModelClient

    @Binds
    @Singleton
    abstract fun bindModelManagerRepository(impl: DefaultModelManagerRepository): ModelManagerRepository

    @Binds
    @Singleton
    abstract fun bindDeviceFingerprintProvider(impl: DefaultDeviceFingerprintProvider): DeviceFingerprintProvider

    @Binds @Singleton
    abstract fun bindModelRegistry(impl: DefaultModelRegistry): ModelRegistry

    @Binds
    @Singleton
    abstract fun bindDeviceCapabilitiesGateway(impl: AndroidDeviceCapabilitiesGateway): DeviceCapabilitiesGateway

    @Binds @Singleton
    abstract fun bindThermalMonitor(impl: ThermalMonitor): DomainThermalMonitor

    companion object {
        private const val GGUF_EXT = ".gguf"
        private const val TMP_EXT = ".tmp"

        @Provides
        @Singleton
        fun provideOkHttpClient(networkSettingsStore: NetworkSettingsStore): OkHttpClient =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val isOffline =
                        runBlocking {
                            networkSettingsStore.isCloudDisabled.firstOrNull() ?: false
                        }
                    if (isOffline) {
                        throw IOException(
                            "Offline Mode is enabled: external cloud and internet connections are disabled.",
                        )
                    }
                    chain.proceed(chain.request())
                }.build()

        @Provides
        @Singleton
        fun provideLlamaRuntime(
            thermalMonitor: DomainThermalMonitor,
            @ApplicationContext context: Context,
            activeModelRepository: ActiveModelRepository,
        ): LlamaRuntime =
            LlamaRuntime(thermalMonitor).apply {
                defaultChatModelResolver = {
                    val modelsDir = File(context.filesDir, "models")
                    val files =
                        modelsDir
                            .listFiles { file ->
                                file.isFile &&
                                    file.name.endsWith(GGUF_EXT, ignoreCase = true) &&
                                    !file.name.endsWith(TMP_EXT, ignoreCase = true) &&
                                    file.length() > 0L
                            }?.sortedBy { it.name }
                            .orEmpty()
                    val activeName = activeModelRepository.getActiveModel().name
                    files.find { it.name.equals(activeName, ignoreCase = true) }
                        ?: files.firstOrNull { !it.name.contains("embedding", ignoreCase = true) }
                        ?: files.firstOrNull()
                }
            }

        @Provides
        @Singleton
        fun provideWorkManager(
            @ApplicationContext context: Context,
        ): WorkManager = WorkManager.getInstance(context)

        @Provides
        @Singleton
        fun provideProviderAdapter(client: OkHttpClient): ProviderAdapter =
            if (BuildConfig.DEV_API_KEY.isNotBlank()) {
                GeminiAdapter(
                    // SEC-3: never log
                    apiKey = BuildConfig.DEV_API_KEY,
                    model = BuildConfig.DEV_MODEL.ifBlank { "gemini-3.5-flash-lite" },
                    client = client,
                )
            } else {
                OpenAiCompatibleAdapter(
                    baseUrl = "https://api.openai.com/v1",
                    client = client,
                )
            }

        @Provides
        @Singleton
        @CloudChat
        fun provideCloudChatModelClient(providerAdapter: ProviderAdapter): ChatModelClient = providerAdapter

        @Suppress("LongParameterList")
        @Provides
        @Singleton
        fun provideRagAnswerUseCase(
            hybridSearch: HybridSearchUseCase,
            providerAdapter: ProviderAdapter,
            noteRepository: NoteRepository,
            activeModelRepository: ActiveModelRepository,
            @LocalChat localChatClient: ChatModelClient,
            networkSettingsStore: NetworkSettingsStore,
        ): RagAnswerUseCase =
            RagAnswerUseCase(
                hybridSearch = hybridSearch,
                providerAdapter = providerAdapter,
                noteRepository = noteRepository,
                activeModelRepository = activeModelRepository,
                localChatClient = localChatClient,
                networkSettingsStore = networkSettingsStore,
            )

        @Provides
        @Singleton
        fun provideInlineAiUseCase(
            providerAdapter: ProviderAdapter,
            @LocalChat localChatClient: ChatModelClient,
            activeModelRepository: ActiveModelRepository,
            networkSettingsStore: NetworkSettingsStore,
        ): InlineAiUseCase =
            InlineAiUseCase(
                chatModelClient =
                    RoutingChatModelClient(
                        cloudClient = providerAdapter,
                        localClient = localChatClient,
                        activeModelRepository = activeModelRepository,
                        networkSettingsStore = networkSettingsStore,
                    ),
            )

        @Provides
        @Singleton
        fun provideSuggestTagsUseCase(
            providerAdapter: ProviderAdapter,
            @LocalChat localChatClient: ChatModelClient,
            activeModelRepository: ActiveModelRepository,
            networkSettingsStore: NetworkSettingsStore,
        ): SuggestTagsUseCase =
            SuggestTagsUseCase(
                chatModelClient =
                    RoutingChatModelClient(
                        cloudClient = providerAdapter,
                        localClient = localChatClient,
                        activeModelRepository = activeModelRepository,
                        networkSettingsStore = networkSettingsStore,
                    ),
            )

        @Suppress("LongParameterList")
        @Provides
        @Singleton
        fun provideComputeDigestUseCase(
            noteRepository: NoteRepository,
            providerAdapter: ProviderAdapter,
            @LocalChat localChatClient: ChatModelClient,
            activeModelRepository: ActiveModelRepository,
            networkSettingsStore: NetworkSettingsStore,
            clock: Clock,
        ): ComputeDigestUseCase =
            ComputeDigestUseCase(
                noteRepository = noteRepository,
                chatModelClient =
                    RoutingChatModelClient(
                        cloudClient = providerAdapter,
                        localClient = localChatClient,
                        activeModelRepository = activeModelRepository,
                        networkSettingsStore = networkSettingsStore,
                    ),
                clock = clock,
            )

        @Suppress("LongParameterList")
        @Provides
        @Singleton
        fun provideComputeClustersUseCase(
            chunkRepository: ChunkRepository,
            noteRepository: NoteRepository,
            providerAdapter: ProviderAdapter,
            @LocalChat localChatClient: ChatModelClient,
            activeModelRepository: ActiveModelRepository,
            networkSettingsStore: NetworkSettingsStore,
            clock: Clock,
        ): ComputeClustersUseCase =
            ComputeClustersUseCase(
                chunkRepository = chunkRepository,
                noteRepository = noteRepository,
                chatModelClient =
                    RoutingChatModelClient(
                        cloudClient = providerAdapter,
                        localClient = localChatClient,
                        activeModelRepository = activeModelRepository,
                        networkSettingsStore = networkSettingsStore,
                    ),
                clock = clock,
            )

        @Suppress("LongParameterList")
        @Provides
        @Singleton
        fun provideExtractActionItemsUseCase(
            noteRepository: NoteRepository,
            providerAdapter: ProviderAdapter,
            @LocalChat localChatClient: ChatModelClient,
            activeModelRepository: ActiveModelRepository,
            networkSettingsStore: NetworkSettingsStore,
            clock: Clock,
        ): ExtractActionItemsUseCase =
            ExtractActionItemsUseCase(
                noteRepository = noteRepository,
                chatModelClient =
                    RoutingChatModelClient(
                        cloudClient = providerAdapter,
                        localClient = localChatClient,
                        activeModelRepository = activeModelRepository,
                        networkSettingsStore = networkSettingsStore,
                    ),
                clock = clock,
            )

        @Provides
        @Singleton
        fun provideParseRemindersUseCase(
            noteRepository: NoteRepository,
            alarmScheduler: AlarmScheduler,
            clock: Clock,
        ): ParseRemindersUseCase =
            ParseRemindersUseCase(
                noteRepository = noteRepository,
                alarmScheduler = alarmScheduler,
                clock = clock,
            )
    }
}
