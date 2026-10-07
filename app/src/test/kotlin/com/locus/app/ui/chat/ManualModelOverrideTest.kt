package com.locus.app.ui.chat

import com.locus.core.domain.chat.ActiveModelInfo
import com.locus.core.domain.chat.ActiveModelRepository
import com.locus.core.domain.chat.ChatMessage
import com.locus.core.domain.chat.ChatRepository
import com.locus.core.domain.chat.ChatSession
import com.locus.core.domain.chat.ModelTier
import com.locus.core.domain.chat.RagAnswerUseCase
import com.locus.core.domain.models.RegistryEntry
import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.notes.UuidV7
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.search.ChunkMetadata
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.EmbeddedChunk
import com.locus.core.domain.search.EmbeddingGateway
import com.locus.core.domain.search.HybridSearchUseCase
import com.locus.core.domain.search.KeywordSearch
import com.locus.core.domain.search.RankedChunk
import com.locus.core.domain.search.SearchResult
import com.locus.core.domain.search.SearchScope
import com.locus.core.domain.time.Clock
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ManualModelOverrideTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
        }

    private lateinit var chatRepository: FakeChatRepository
    private lateinit var activeModelRepo: FakeActiveModelRepository
    private lateinit var viewModel: ChatViewModel

    private val localModelEntry =
        RegistryEntry(
            ref = ModelRef(id = "llama-3.2-3b", tier = ModelTier.LOCAL, providerId = null),
            contextLength = 4096,
            capabilities = null,
            isOffline = true,
            benchmarkedTokPerSecond = 25.0,
        )

    private val cloudModelEntry =
        RegistryEntry(
            ref = ModelRef(id = "gpt-4o", tier = ModelTier.CLOUD, providerId = "openai"),
            contextLength = 128000,
            capabilities = null,
            isOffline = false,
            benchmarkedTokPerSecond = null,
        )

    @Before
    fun setUp() {
        val clock = Clock { Instant.EPOCH }
        chatRepository = FakeChatRepository(clock)
        activeModelRepo =
            FakeActiveModelRepository(
                ActiveModelInfo(name = "llama-3.2-3b", tier = ModelTier.LOCAL),
            )

        val hybridSearch =
            HybridSearchUseCase(
                keywordSearch = FakeKeywordSearch(),
                chunkRepository = FakeChunkRepository(),
                embeddingGateway = FakeEmbeddingGateway(),
            )
        val ragAnswer =
            RagAnswerUseCase(
                hybridSearch = hybridSearch,
                providerAdapter = FakeProviderAdapter(),
            )

        viewModel =
            ChatViewModel(
                chatRepository = chatRepository,
                ragAnswerUseCase = ragAnswer,
                noteRepository = FakeNoteRepository(),
                clock = clock,
                dispatchers = testDispatchers,
                activeModelRepository = activeModelRepo,
            )
    }

    @Test
    fun localToCloudSwitch_triggersSec5DialogBeforeSending_andDeclineRevertsToLocal() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            // 1. Initial state is local model
            viewModel.selectModel(localModelEntry)
            assertEquals(ModelTier.LOCAL, viewModel.uiState.value.activeModel.tier)

            // Send initial message on local model
            viewModel.sendMessage("Message 1 on local model")
            advanceUntilIdle()
            assertEquals(2, chatRepository.allMessages.size)
            assertNull(viewModel.pendingCloudTransition.value)

            // 2. Mid-conversation manual switch to cloud model
            viewModel.selectModel(cloudModelEntry)
            assertEquals("gpt-4o", viewModel.uiState.value.activeModel.name)

            // 3. User attempts to send next message
            viewModel.sendMessage("Message 2 with note data")
            advanceUntilIdle()

            // SEC-5 dialog appears before any data is sent!
            val decision = viewModel.pendingCloudTransition.value
            assertNotNull(decision)
            assertEquals("openai", decision!!.providerId)
            assertEquals("llama-3.2-3b", decision.fromModel.id)
            assertEquals("gpt-4o", decision.toModel.id)

            // No second message was sent yet
            assertEquals(2, chatRepository.allMessages.size)

            // 4. Declining leaves the conversation on the local model with no data sent
            viewModel.cancelCloudTransition()
            advanceUntilIdle()

            assertNull(viewModel.pendingCloudTransition.value)
            assertEquals("llama-3.2-3b", viewModel.uiState.value.activeModel.name)
            assertEquals(ModelTier.LOCAL, viewModel.uiState.value.activeModel.tier)
            assertEquals(2, chatRepository.allMessages.size)
        }

    @Test
    fun localToCloudSwitch_confirmingDialog_sendsMessageWithCloudModel() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            viewModel.selectModel(localModelEntry)
            viewModel.sendMessage("Initial message")
            advanceUntilIdle()
            assertEquals(2, chatRepository.allMessages.size)

            viewModel.selectModel(cloudModelEntry)
            viewModel.sendMessage("Cloud message")
            advanceUntilIdle()

            assertNotNull(viewModel.pendingCloudTransition.value)

            // Confirm
            viewModel.confirmCloudTransition()
            advanceUntilIdle()

            assertNull(viewModel.pendingCloudTransition.value)
            assertEquals(4, chatRepository.allMessages.size)
            assertEquals("gpt-4o", viewModel.uiState.value.activeModel.name)
            assertEquals(ModelTier.CLOUD, viewModel.uiState.value.activeModel.tier)
        }

    private class FakeChatRepository(
        private val clock: Clock,
    ) : ChatRepository {
        val allMessages = mutableListOf<ChatMessage>()
        private val sessionsFlow = MutableStateFlow<List<ChatSession>>(emptyList())
        private val messagesMap = mutableMapOf<String, MutableStateFlow<List<ChatMessage>>>()

        override fun observeSessions(): Flow<List<ChatSession>> = sessionsFlow

        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
            messagesMap.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

        override suspend fun createSession(name: String): ChatSession {
            val now = clock.now()
            val id = UuidV7.generate(clock)
            val session =
                ChatSession(
                    id = id,
                    name = name,
                    createdAt = now,
                    modifiedAt = now,
                )
            sessionsFlow.value = listOf(session) + sessionsFlow.value
            return session
        }

        override suspend fun appendMessage(
            sessionId: String,
            message: ChatMessage,
        ) {
            allMessages.add(message)
            val flow = messagesMap.getOrPut(sessionId) { MutableStateFlow(emptyList()) }
            flow.value = flow.value + message
        }
    }

    private class FakeNoteRepository : NoteRepository {
        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

        override fun observeAllNotes(): Flow<List<Note>> = emptyFlow()

        override suspend fun readBody(noteId: String): String = ""

        override suspend fun listFolders(): List<String> = emptyList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) {
            // no-op
        }

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note =
            Note(
                id = "note-1",
                title = title,
                type = type,
                folderPath = folderPath,
                pinned = false,
                color = null,
                tags = emptyList(),
                created = Instant.EPOCH,
                modified = Instant.EPOCH,
                checksum = Checksum.sha256(""),
            )

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            // no-op
        }

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) {
            // no-op
        }

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) {
            // no-op
        }

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }

    private class FakeActiveModelRepository(
        initial: ActiveModelInfo = ActiveModelInfo("test", ModelTier.LOCAL),
    ) : ActiveModelRepository {
        private val flow = MutableStateFlow(initial)

        override fun observeActiveModel(): Flow<ActiveModelInfo> = flow

        override suspend fun setActiveModel(model: ActiveModelInfo) {
            flow.value = model
        }

        override fun getActiveModel(): ActiveModelInfo = flow.value
    }

    private class FakeProviderAdapter : ProviderAdapter {
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                supportsNativeTools = false,
                contextLength = 8192,
                pricePerMillionInputTokens = null,
                pricePerMillionOutputTokens = null,
            )

        override fun streamChat(
            messages: List<ProviderMessage>,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> = flow { emit(StreamEvent.Done()) }
    }

    private class FakeKeywordSearch : KeywordSearch {
        override suspend fun search(
            query: String,
            scope: SearchScope,
        ): List<SearchResult> = emptyList()
    }

    private class FakeChunkRepository : ChunkRepository {
        override suspend fun isAvailable(): Boolean = true

        override suspend fun getMetadata(noteId: String): ChunkMetadata? = null

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) {
            // no-op
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> = emptyList()

        override suspend fun deleteAll() {
            // no-op
        }
    }

    private class FakeEmbeddingGateway : EmbeddingGateway {
        override suspend fun embed(text: String): FloatArray = FloatArray(16) { 0.1f }
    }
}
