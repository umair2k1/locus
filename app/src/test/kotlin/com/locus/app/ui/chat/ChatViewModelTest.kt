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

package com.locus.app.ui.chat

import com.locus.app.ui.editor.parseInlineMarkdown
import com.locus.core.domain.chat.ActiveModelInfo
import com.locus.core.domain.chat.ActiveModelRepository
import com.locus.core.domain.chat.ChatMessage
import com.locus.core.domain.chat.ChatRepository
import com.locus.core.domain.chat.ChatRole
import com.locus.core.domain.chat.ChatSession
import com.locus.core.domain.chat.CitedSource
import com.locus.core.domain.chat.ModelTier
import com.locus.core.domain.chat.RagAnswerUseCase
import com.locus.core.domain.chat.ThermalMonitor
import com.locus.core.domain.models.ModelRegistry
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testDispatchers: TestDispatcherProvider
    private lateinit var fakeClock: FakeClock
    private lateinit var fakeChatRepository: FakeChatRepository
    private lateinit var fakeNoteRepository: FakeNoteRepository
    private lateinit var ragAnswerUseCase: RagAnswerUseCase
    private lateinit var fakeProviderAdapter: FakeProviderAdapter
    private lateinit var fakeActiveModelRepository: FakeActiveModelRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        testDispatchers = TestDispatcherProvider(testDispatcher)
        fakeClock = FakeClock(Instant.parse("2026-09-18T12:00:00Z"))
        fakeChatRepository = FakeChatRepository(fakeClock)
        fakeNoteRepository = FakeNoteRepository()

        val searchResults =
            listOf(
                SearchResult(
                    noteId = "note-uuid-1",
                    title = "Kotlin Guide",
                    snippet = "Kotlin features",
                    score = 0.95,
                    headingPath = listOf("Overview"),
                ),
            )
        val hybridSearch =
            HybridSearchUseCase(
                keywordSearch = FakeKeywordSearch(searchResults),
                chunkRepository = FakeChunkRepository(),
                embeddingGateway = FakeEmbeddingGateway(),
            )
        fakeProviderAdapter =
            FakeProviderAdapter(
                listOf(
                    StreamEvent.TokenDelta("Kotlin "),
                    StreamEvent.TokenDelta("is "),
                    StreamEvent.TokenDelta("great [1]."),
                    StreamEvent.Done(),
                ),
            )
        ragAnswerUseCase =
            RagAnswerUseCase(
                hybridSearch = hybridSearch,
                providerAdapter = fakeProviderAdapter,
            )
        fakeActiveModelRepository = FakeActiveModelRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        rag: RagAnswerUseCase = ragAnswerUseCase,
        thermalMonitor: ThermalMonitor? = null,
    ): ChatViewModel =
        ChatViewModel(
            chatRepository = fakeChatRepository,
            ragAnswerUseCase = rag,
            noteRepository = fakeNoteRepository,
            clock = fakeClock,
            dispatchers = testDispatchers,
            activeModelRepository = fakeActiveModelRepository,
            thermalMonitor = thermalMonitor,
        )

    @Test
    fun twoNamedSessions_retainIndependentHistory_afterRestart() =
        runTest(testDispatcher) {
            val vm1 = createViewModel()
            advanceUntilIdle()

            // 1. Create Session Alpha and append a message
            vm1.createNewSession("Session Alpha")
            advanceUntilIdle()
            val alphaSessionId = vm1.uiState.value.activeSessionId
            assertNotNull(alphaSessionId)

            val alphaUserMsg =
                ChatMessage(
                    id = "msg-alpha-1",
                    sessionId = alphaSessionId!!,
                    role = ChatRole.USER,
                    content = "Hello from Alpha",
                    citations = emptyList(),
                    timestamp = fakeClock.now(),
                )
            fakeChatRepository.appendMessage(alphaSessionId, alphaUserMsg)
            advanceUntilIdle()

            // 2. Create Session Beta and append a message
            vm1.createNewSession("Session Beta")
            advanceUntilIdle()
            val betaSessionId = vm1.uiState.value.activeSessionId
            assertNotNull(betaSessionId)

            val betaUserMsg =
                ChatMessage(
                    id = "msg-beta-1",
                    sessionId = betaSessionId!!,
                    role = ChatRole.USER,
                    content = "Hello from Beta",
                    citations = emptyList(),
                    timestamp = fakeClock.now(),
                )
            fakeChatRepository.appendMessage(betaSessionId, betaUserMsg)
            advanceUntilIdle()

            // Verify vm1 sees Beta's message while Beta is active
            assertEquals(listOf(betaUserMsg), vm1.uiState.value.messages)

            // Switch to Alpha
            vm1.selectSession(alphaSessionId)
            advanceUntilIdle()
            assertEquals(listOf(alphaUserMsg), vm1.uiState.value.messages)

            // 3. Simulate app restart: brand-new ViewModel instance with the same repository
            val vm2 = createViewModel()
            advanceUntilIdle()

            // Verify both sessions exist
            val sessionNames =
                vm2.uiState.value.sessions
                    .map { it.name }
                    .toSet()
            assertTrue(sessionNames.contains("Session Alpha"))
            assertTrue(sessionNames.contains("Session Beta"))

            // Select Alpha on restarted ViewModel: history contains ONLY Alpha's message
            vm2.selectSession(alphaSessionId)
            advanceUntilIdle()
            assertEquals(1, vm2.uiState.value.messages.size)
            assertEquals(
                "Hello from Alpha",
                vm2.uiState.value.messages
                    .first()
                    .content,
            )

            // Select Beta on restarted ViewModel: history contains ONLY Beta's message
            vm2.selectSession(betaSessionId)
            advanceUntilIdle()
            assertEquals(1, vm2.uiState.value.messages.size)
            assertEquals(
                "Hello from Beta",
                vm2.uiState.value.messages
                    .first()
                    .content,
            )
        }

    @Test
    fun sendMessage_streamsTokensAndAppendsAssistantMessage() =
        runTest(testDispatcher) {
            val vm =
                ChatViewModel(
                    chatRepository = fakeChatRepository,
                    ragAnswerUseCase = ragAnswerUseCase,
                    noteRepository = fakeNoteRepository,
                    clock = fakeClock,
                    dispatchers = testDispatchers,
                    activeModelRepository = fakeActiveModelRepository,
                )
            advanceUntilIdle()

            vm.sendMessage("Explain Kotlin")
            advanceUntilIdle()

            val state = vm.uiState.value
            assertNull(state.streamingText)
            assertEquals(2, state.messages.size)

            val userMsg = state.messages[0]
            assertEquals(ChatRole.USER, userMsg.role)
            assertEquals("Explain Kotlin", userMsg.content)

            val assistantMsg = state.messages[1]
            assertEquals(ChatRole.ASSISTANT, assistantMsg.role)
            assertTrue(assistantMsg.content.contains("Kotlin is great"))
            assertEquals(1, assistantMsg.citations.size)
            assertEquals("note-uuid-1", assistantMsg.citations.first().noteId)
        }

    @Test
    fun pinAsNote_createsNoteWithFormattedSourcesSection() =
        runTest(testDispatcher) {
            val vm =
                ChatViewModel(
                    chatRepository = fakeChatRepository,
                    ragAnswerUseCase = ragAnswerUseCase,
                    noteRepository = fakeNoteRepository,
                    clock = fakeClock,
                    dispatchers = testDispatchers,
                    activeModelRepository = fakeActiveModelRepository,
                )
            advanceUntilIdle()

            val assistantMsg =
                ChatMessage(
                    id = "msg-assistant",
                    sessionId = "session-1",
                    role = ChatRole.ASSISTANT,
                    content =
                        "Kotlin runs on the JVM [1] and supports multiplatform [2].",
                    citations =
                        listOf(
                            CitedSource(
                                noteId = "note-uuid-1",
                                noteTitle = "JVM Architecture",
                                headingPath = listOf("Intro"),
                            ),
                            CitedSource(
                                noteId = "note-uuid-2",
                                noteTitle = "KMP Guide",
                                headingPath = emptyList(),
                            ),
                        ),
                    timestamp = fakeClock.now(),
                )

            var createdNote: Note? = null
            vm.pinAsNote(assistantMsg) { createdNote = it }
            advanceUntilIdle()

            assertNotNull(createdNote)
            assertEquals(
                "Kotlin runs on the JVM [1] and supports multipl…",
                createdNote!!.title,
            )

            val savedBody = fakeNoteRepository.bodies[createdNote!!.id]
            assertNotNull(savedBody)
            assertTrue(
                savedBody!!.contains(
                    "Kotlin runs on the JVM [1] and supports multiplatform [2].",
                ),
            )
            assertTrue(savedBody.contains("## Sources"))
            assertTrue(
                savedBody.contains(
                    "- [[note-uuid-1]] [JVM Architecture](locus://note/note-uuid-1)",
                ),
            )
            assertTrue(
                savedBody.contains(
                    "- [[note-uuid-2]] [KMP Guide](locus://note/note-uuid-2)",
                ),
            )

            // Verify markdown parser resolves the back-reference / link to note IDs
            val annotated = parseInlineMarkdown(savedBody)
            val noteIdAnnotations =
                annotated
                    .getStringAnnotations(
                        tag = "NOTE_ID",
                        start = 0,
                        end = annotated.length,
                    ).map { it.item }
            assertTrue(noteIdAnnotations.contains("note-uuid-1"))
            assertTrue(noteIdAnnotations.contains("note-uuid-2"))
        }

    @Test
    fun activeModel_updatesImmediatelyWhenRepositoryEmits() =
        runTest(testDispatcher) {
            val vm =
                ChatViewModel(
                    chatRepository = fakeChatRepository,
                    ragAnswerUseCase = ragAnswerUseCase,
                    noteRepository = fakeNoteRepository,
                    clock = fakeClock,
                    dispatchers = testDispatchers,
                    activeModelRepository = fakeActiveModelRepository,
                )
            advanceUntilIdle()

            assertEquals("gpt-4o", vm.uiState.value.activeModel.name)
            assertEquals(
                com.locus.core.domain.chat.ModelTier.CLOUD,
                vm.uiState.value.activeModel.tier,
            )
            assertTrue(vm.uiState.value.activeModel.isCloud)

            val localModel =
                com.locus.core.domain.chat.ActiveModelInfo(
                    name = "qwen-2.5-7b",
                    tier = com.locus.core.domain.chat.ModelTier.LOCAL,
                    contextLength = 4096,
                )
            fakeActiveModelRepository.setActiveModel(localModel)
            advanceUntilIdle()

            assertEquals(localModel, vm.uiState.value.activeModel)
            assertTrue(vm.uiState.value.activeModel.isLocal)
        }

    @Test
    fun modelRegistry_emissionsUpdateAvailableModelsInUiState_andSelectModelUpdatesActive() =
        runTest(testDispatcher) {
            val localEntry =
                RegistryEntry(
                    ref = ModelRef("qwen-3-4b.gguf", ModelTier.LOCAL, null),
                    contextLength = 32_768,
                    capabilities = null,
                    isOffline = true,
                    benchmarkedTokPerSecond = 18.5,
                )
            val fakeRegistry = FakeModelRegistry(listOf(localEntry))
            val vm =
                ChatViewModel(
                    chatRepository = fakeChatRepository,
                    ragAnswerUseCase = ragAnswerUseCase,
                    noteRepository = fakeNoteRepository,
                    clock = fakeClock,
                    dispatchers = testDispatchers,
                    activeModelRepository = fakeActiveModelRepository,
                    modelRegistry = fakeRegistry,
                )
            advanceUntilIdle()

            assertEquals(1, vm.uiState.value.availableModels.size)
            assertEquals(
                "qwen-3-4b.gguf",
                vm.uiState.value.availableModels[0]
                    .ref.id,
            )

            vm.selectModel(localEntry)
            advanceUntilIdle()

            assertEquals("qwen-3-4b.gguf", vm.uiState.value.activeModel.name)
            assertEquals(ModelTier.LOCAL, vm.uiState.value.activeModel.tier)
            assertEquals(32_768, vm.uiState.value.activeModel.contextLength)
        }

    @Test
    fun thermalWarningSurfacesInUiStateAndEffectWhenThrottlingOccurs() =
        runTest(testDispatcher) {
            val fakeThermal = FakeThermalMonitor(initialThrottling = false)
            val vm =
                ChatViewModel(
                    chatRepository = fakeChatRepository,
                    ragAnswerUseCase = ragAnswerUseCase,
                    noteRepository = fakeNoteRepository,
                    clock = fakeClock,
                    dispatchers = testDispatchers,
                    activeModelRepository = fakeActiveModelRepository,
                    thermalMonitor = fakeThermal,
                )
            advanceUntilIdle()
            assertFalse(vm.uiState.value.showThermalWarning)

            fakeThermal.setThrottling(true)
            advanceUntilIdle()

            assertTrue(vm.uiState.value.showThermalWarning)
        }

    @Test
    fun dismissThermalWarningClearsUiStateAndCallsMonitor() =
        runTest(testDispatcher) {
            val fakeThermal = FakeThermalMonitor(initialThrottling = true)
            val vm =
                ChatViewModel(
                    chatRepository = fakeChatRepository,
                    ragAnswerUseCase = ragAnswerUseCase,
                    noteRepository = fakeNoteRepository,
                    clock = fakeClock,
                    dispatchers = testDispatchers,
                    activeModelRepository = fakeActiveModelRepository,
                    thermalMonitor = fakeThermal,
                )
            advanceUntilIdle()
            assertTrue(vm.uiState.value.showThermalWarning)

            vm.dismissThermalWarning()
            advanceUntilIdle()

            assertFalse(vm.uiState.value.showThermalWarning)
            assertFalse(fakeThermal.isThrottlingLikely.value)
        }

    @Test
    fun stopGeneration_cancelsActiveStreamingAndPreservesPartialText() =
        runTest(testDispatcher) {
            val hangingAdapter =
                object : ProviderAdapter {
                    override val capabilities =
                        ProviderCapabilities(
                            supportsNativeTools = false,
                            contextLength = 8192,
                            pricePerMillionInputTokens = null,
                            pricePerMillionOutputTokens = null,
                        )

                    override fun streamChat(
                        messages: List<ProviderMessage>,
                        tools: List<ToolSchema>,
                    ): Flow<StreamEvent> =
                        flow {
                            emit(StreamEvent.TokenDelta("Partial answer before user stops"))
                            kotlinx.coroutines.delay(10_000)
                            emit(StreamEvent.TokenDelta(" Should never appear"))
                        }
                }

            val customRag =
                RagAnswerUseCase(
                    hybridSearch =
                        HybridSearchUseCase(
                            keywordSearch =
                                object : KeywordSearch {
                                    override suspend fun search(
                                        query: String,
                                        scope: SearchScope,
                                    ): List<SearchResult> = emptyList()
                                },
                            chunkRepository = FakeChunkRepository(),
                            embeddingGateway = FakeEmbeddingGateway(),
                        ),
                    providerAdapter = hangingAdapter,
                )

            val vm = createViewModel(rag = customRag)
            advanceUntilIdle()

            vm.sendMessage("What is the cost?")
            testScheduler.advanceTimeBy(50)
            assertEquals("Partial answer before user stops", vm.uiState.value.streamingText)

            vm.stopGeneration()
            advanceUntilIdle()

            assertNull(vm.uiState.value.streamingText)
            val assistantMessages =
                vm.uiState.value.messages
                    .filter { it.role == ChatRole.ASSISTANT }
            assertTrue(assistantMessages.isNotEmpty())
            assertEquals("Partial answer before user stops", assistantMessages.last().content)
        }

    // --- Fakes ---
    private class FakeThermalMonitor(
        initialThrottling: Boolean = false,
    ) : ThermalMonitor {
        private val _isThrottlingLikely = MutableStateFlow(initialThrottling)
        override val isThrottlingLikely: StateFlow<Boolean> = _isThrottlingLikely.asStateFlow()

        override fun startMonitoring() {
            // no-op in test
        }

        override fun stopMonitoring() {
            // no-op in test
        }

        override fun dismissWarning() {
            _isThrottlingLikely.value = false
        }

        fun setThrottling(value: Boolean) {
            _isThrottlingLikely.value = value
        }
    }

    private class FakeClock(
        private var currentInstant: Instant,
    ) : Clock {
        override fun now(): Instant = currentInstant

        fun advanceSeconds(seconds: Long) {
            currentInstant = currentInstant.plusSeconds(seconds)
        }
    }

    private class FakeModelRegistry(
        initial: List<RegistryEntry> = emptyList(),
    ) : ModelRegistry {
        private val flow = MutableStateFlow(initial)

        override fun observeModels(): Flow<List<RegistryEntry>> = flow

        override suspend fun getModels(): List<RegistryEntry> = flow.value

        fun emit(models: List<RegistryEntry>) {
            flow.value = models
        }
    }

    private class FakeChatRepository(
        private val clock: Clock,
    ) : ChatRepository {
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
            val flow = messagesMap.getOrPut(sessionId) { MutableStateFlow(emptyList()) }
            flow.value = flow.value + message
            val now = clock.now()
            sessionsFlow.value =
                sessionsFlow.value
                    .map { session ->
                        if (session.id == sessionId) {
                            session.copy(modifiedAt = now)
                        } else {
                            session
                        }
                    }.sortedByDescending { it.modifiedAt }
        }
    }

    private class FakeNoteRepository : NoteRepository {
        val createdNotes = mutableListOf<Note>()
        val bodies = mutableMapOf<String, String>()

        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

        override fun observeAllNotes(): Flow<List<Note>> = emptyFlow()

        override suspend fun readBody(noteId: String): String = bodies[noteId].orEmpty()

        override suspend fun listFolders(): List<String> = emptyList()

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) = Unit

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note {
            val note =
                Note(
                    id = "note-${createdNotes.size + 1}",
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
            createdNotes.add(note)
            return note
        }

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {
            bodies[noteId] = newBody
        }

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) = Unit

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) = Unit

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }

    private class FakeActiveModelRepository : ActiveModelRepository {
        private val flow =
            MutableStateFlow(
                ActiveModelInfo(
                    name = "gpt-4o",
                    tier = ModelTier.CLOUD,
                ),
            )

        override fun observeActiveModel(): Flow<ActiveModelInfo> = flow

        override suspend fun setActiveModel(model: ActiveModelInfo) {
            flow.value = model
        }

        override fun getActiveModel(): ActiveModelInfo = flow.value
    }

    private class FakeProviderAdapter(
        private val events: List<StreamEvent>,
    ) : ProviderAdapter {
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
        ): Flow<StreamEvent> =
            flow {
                for (event in events) {
                    emit(event)
                }
            }
    }

    private class FakeKeywordSearch(
        private val results: List<SearchResult>,
    ) : KeywordSearch {
        override suspend fun search(
            query: String,
            scope: SearchScope,
        ): List<SearchResult> = results
    }

    private class FakeChunkRepository : ChunkRepository {
        override suspend fun isAvailable(): Boolean = true

        override suspend fun getMetadata(noteId: String): ChunkMetadata? = null

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) = Unit

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> = emptyList()

        override suspend fun deleteAll() = Unit
    }

    private class FakeEmbeddingGateway : EmbeddingGateway {
        override suspend fun embed(text: String): FloatArray = FloatArray(16) { 0.1f }
    }

    private class TestDispatcherProvider(
        private val dispatcher: CoroutineDispatcher,
    ) : DispatcherProvider {
        override val io: CoroutineDispatcher
            get() = dispatcher
        override val default: CoroutineDispatcher
            get() = dispatcher
        override val main: CoroutineDispatcher
            get() = dispatcher
        override val mainImmediate: CoroutineDispatcher
            get() = dispatcher
    }
}
