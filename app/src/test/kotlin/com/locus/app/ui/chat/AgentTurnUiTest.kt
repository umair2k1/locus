package com.locus.app.ui.chat

import com.locus.core.domain.agent.AgentRunCoordinator
import com.locus.core.domain.agent.AuditEntry
import com.locus.core.domain.agent.AuditJournal
import com.locus.core.domain.agent.CallOrigin
import com.locus.core.domain.agent.ConfirmReason
import com.locus.core.domain.agent.PendingToolCall
import com.locus.core.domain.agent.SafetyDecision
import com.locus.core.domain.agent.ToolConfirmationDeniedException
import com.locus.core.domain.agent.WriteToolName
import com.locus.core.domain.chat.ActiveModelInfo
import com.locus.core.domain.chat.ActiveModelRepository
import com.locus.core.domain.chat.ChatMessage
import com.locus.core.domain.chat.ChatRepository
import com.locus.core.domain.chat.ChatSession
import com.locus.core.domain.chat.ModelTier
import com.locus.core.domain.chat.RagAnswerUseCase
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
import com.locus.core.domain.search.ChunkMetadata
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.EmbeddedChunk
import com.locus.core.domain.search.EmbeddingGateway
import com.locus.core.domain.search.HybridSearchUseCase
import com.locus.core.domain.search.KeywordSearch
import com.locus.core.domain.search.RankedChunk
import com.locus.core.domain.search.SearchResult
import com.locus.core.domain.search.SearchScope
import com.locus.core.domain.settings.AgentSettingsStore
import com.locus.core.domain.time.Clock
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTurnUiTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
        }

    private lateinit var coordinator: AgentRunCoordinator
    private lateinit var fakeJournal: FakeAuditJournal
    private lateinit var fakeSettingsStore: FakeAgentSettingsStore
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        val clock = Clock { Instant.EPOCH }
        fakeSettingsStore = FakeAgentSettingsStore()
        fakeJournal = FakeAuditJournal()
        coordinator =
            AgentRunCoordinator(
                settingsStore = fakeSettingsStore,
                auditJournal = fakeJournal,
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
                chatRepository = FakeChatRepository(clock),
                ragAnswerUseCase = ragAnswer,
                noteRepository = FakeNoteRepository(),
                clock = clock,
                dispatchers = testDispatchers,
                activeModelRepository = FakeActiveModelRepository(),
                coordinator = coordinator,
                auditJournal = fakeJournal,
            )
    }

    @Test
    fun promptInjectionInNote_routesToInjectionGuardDialog_evenForReadTier() =
        runTest(testDispatcher) {
            val call =
                PendingToolCall(
                    tool = WriteToolName.SEARCH_NOTES,
                    origin = CallOrigin.RETRIEVED_NOTE_CONTENT,
                    argumentsJson = """{"query": "hidden instructions from note"}""",
                )

            var executionCompleted = false
            launch {
                coordinator.execute(call) {
                    executionCompleted = true
                    "search results"
                }
            }
            advanceUntilIdle()

            val pending = viewModel.pendingConfirmation.value
            assertNotNull(pending)
            val decision = pending!!.request.decision
            assertTrue(decision is SafetyDecision.AlwaysConfirm)
            assertEquals(
                ConfirmReason.PROMPT_INJECTION_GUARD,
                (decision as SafetyDecision.AlwaysConfirm).reason,
            )
            assertFalse(executionCompleted)

            viewModel.confirmPendingAction(true)
            advanceUntilIdle()

            assertTrue(executionCompleted)
            assertNull(viewModel.pendingConfirmation.value)
        }

    @Test
    @Suppress("SwallowedException")
    fun promptInjectionInNote_trashTool_routesToInjectionGuardDialog() =
        runTest(testDispatcher) {
            val call =
                PendingToolCall(
                    tool = WriteToolName.TRASH_NOTE,
                    origin = CallOrigin.RETRIEVED_NOTE_CONTENT,
                    argumentsJson = """{"noteId": "note-all"}""",
                )

            var executionCompleted = false
            var wasDenied = false
            launch {
                try {
                    coordinator.execute(call) {
                        executionCompleted = true
                        "trashed"
                    }
                } catch (e: ToolConfirmationDeniedException) {
                    wasDenied = true
                }
            }
            advanceUntilIdle()

            val pending = viewModel.pendingConfirmation.value
            assertNotNull(pending)
            val decision = pending!!.request.decision
            assertTrue(decision is SafetyDecision.AlwaysConfirm)
            assertEquals(
                ConfirmReason.PROMPT_INJECTION_GUARD,
                (decision as SafetyDecision.AlwaysConfirm).reason,
            )

            viewModel.confirmPendingAction(false)
            advanceUntilIdle()

            assertTrue(wasDenied)
            assertFalse(executionCompleted)
            assertNull(viewModel.pendingConfirmation.value)
        }

    @Test
    @Suppress("SwallowedException")
    fun dashboardActionItem_cannotAutoExecuteAsToolCall_andTriggersInjectionGuardWhenRetrieved() =
        runTest(testDispatcher) {
            val call =
                PendingToolCall(
                    tool = WriteToolName.TRASH_NOTE,
                    origin = CallOrigin.RETRIEVED_NOTE_CONTENT,
                    argumentsJson = """{"noteId": "note-injected"}""",
                )

            var executed = false
            launch {
                try {
                    coordinator.execute(call) {
                        executed = true
                        "trashed"
                    }
                } catch (_: ToolConfirmationDeniedException) {
                    // expected when denied
                }
            }
            advanceUntilIdle()

            val pending = viewModel.pendingConfirmation.value
            assertNotNull("Must surface confirmation dialog, never auto-execute", pending)
            val decision = pending!!.request.decision
            assertTrue(decision is SafetyDecision.AlwaysConfirm)
            assertEquals(
                ConfirmReason.PROMPT_INJECTION_GUARD,
                (decision as SafetyDecision.AlwaysConfirm).reason,
            )
            assertFalse("Tool call must not auto-execute without explicit user consent", executed)

            viewModel.confirmPendingAction(false)
            advanceUntilIdle()
            assertFalse(executed)
        }

    @Test
    fun undoAction_revertsViaAuditJournal() =
        runTest(testDispatcher) {
            val action =
                UndoableAction(
                    entryId = "entry-to-undo",
                    toolName = "update_note",
                    diff = "-old\n+new",
                    affectedNotes = listOf("note-1"),
                )

            viewModel.showUndoableAction(action)
            assertEquals(action, viewModel.undoableAction.value)

            viewModel.undoAction("entry-to-undo")
            advanceUntilIdle()

            assertEquals("entry-to-undo", fakeJournal.lastRevertedId)
            assertNull(viewModel.undoableAction.value)
        }

    private class FakeAuditJournal : AuditJournal {
        var lastRevertedId: String? = null

        override suspend fun record(entry: AuditEntry) {
            // no-op
        }

        override fun observeEntries(): Flow<List<AuditEntry>> = flowOf(emptyList())

        override suspend fun revert(entryId: String) {
            lastRevertedId = entryId
        }
    }

    private class FakeAgentSettingsStore : AgentSettingsStore {
        private val _bulkCap = MutableStateFlow(50)
        override val bulkCap: Flow<Int> = _bulkCap

        override suspend fun setBulkCap(value: Int) {
            _bulkCap.value = value
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

    private class FakeActiveModelRepository : ActiveModelRepository {
        private val flow = MutableStateFlow(ActiveModelInfo("test", ModelTier.LOCAL))

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
