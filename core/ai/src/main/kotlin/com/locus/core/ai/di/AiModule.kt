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
import com.locus.core.domain.models.DeviceCapabilitiesGateway
import com.locus.core.domain.models.ModelManagerRepository
import com.locus.core.domain.models.ModelRegistry
import com.locus.core.domain.notes.InlineAiUseCase
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.SuggestTagsUseCase
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.EmbeddingGateway
import com.locus.core.domain.search.HybridSearchUseCase
import com.locus.core.domain.time.Clock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
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
            val loaded = ensureModelLoaded()
            return if (loaded) {
                runCatching { runner.embed(text) }.getOrDefault(FloatArray(EmbeddingRunner.TARGET_DIM))
            } else {
                FloatArray(EmbeddingRunner.TARGET_DIM)
            }
        }

        private suspend fun ensureModelLoaded(): Boolean {
            if (isInitialized) return true
            return initMutex.withLock {
                if (isInitialized) return@withLock true
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
        @Provides
        @Singleton
        fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()

        @Provides
        @Singleton
        fun provideLlamaRuntime(thermalMonitor: DomainThermalMonitor): LlamaRuntime = LlamaRuntime(thermalMonitor)

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

        @Provides
        @Singleton
        fun provideRagAnswerUseCase(
            hybridSearch: HybridSearchUseCase,
            providerAdapter: ProviderAdapter,
            noteRepository: NoteRepository,
            activeModelRepository: ActiveModelRepository,
            @LocalChat localChatClient: ChatModelClient,
        ): RagAnswerUseCase =
            RagAnswerUseCase(
                hybridSearch = hybridSearch,
                providerAdapter = providerAdapter,
                noteRepository = noteRepository,
                activeModelRepository = activeModelRepository,
                localChatClient = localChatClient,
            )

        @Provides
        @Singleton
        fun provideInlineAiUseCase(providerAdapter: ProviderAdapter): InlineAiUseCase =
            InlineAiUseCase(
                chatModelClient = providerAdapter,
            )

        @Provides
        @Singleton
        fun provideSuggestTagsUseCase(providerAdapter: ProviderAdapter): SuggestTagsUseCase =
            SuggestTagsUseCase(
                chatModelClient = providerAdapter,
            )

        @Provides
        @Singleton
        fun provideComputeDigestUseCase(
            noteRepository: NoteRepository,
            providerAdapter: ProviderAdapter,
            clock: Clock,
        ): ComputeDigestUseCase =
            ComputeDigestUseCase(
                noteRepository = noteRepository,
                chatModelClient = providerAdapter,
                clock = clock,
            )

        @Provides
        @Singleton
        fun provideComputeClustersUseCase(
            chunkRepository: ChunkRepository,
            noteRepository: NoteRepository,
            providerAdapter: ProviderAdapter,
            clock: Clock,
        ): ComputeClustersUseCase =
            ComputeClustersUseCase(
                chunkRepository = chunkRepository,
                noteRepository = noteRepository,
                chatModelClient = providerAdapter,
                clock = clock,
            )
    }
}
