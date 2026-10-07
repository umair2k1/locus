package com.locus.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.core.domain.chat.ChatMessage
import com.locus.core.domain.chat.ChatRepository
import com.locus.core.domain.chat.ChatRole
import com.locus.core.domain.chat.ChatSession
import com.locus.core.domain.chat.RagAnswerUseCase
import com.locus.core.domain.chat.ThermalMonitor
import com.locus.core.domain.models.ModelRegistry
import com.locus.core.domain.models.RegistryEntry
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.UuidV7
import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.ProviderRole
import com.locus.core.domain.time.Clock
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val MAX_SESSION_NAME_LENGTH = 30
private const val SESSION_NAME_ELLIPSIS_LIMIT = 27
private const val MAX_TITLE_LENGTH = 50
private const val TITLE_ELLIPSIS_LIMIT = 47

data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val streamingText: String? = null,
    val activeModel: com.locus.core.domain.chat.ActiveModelInfo =
        com.locus.core.domain.chat.ActiveModelInfo(
            name = "gpt-4o",
            tier = com.locus.core.domain.chat.ModelTier.CLOUD,
        ),
    val availableModels: List<RegistryEntry> = emptyList(),
    val showThermalWarning: Boolean = false,
)

sealed interface ChatEffect {
    data object ThermalThrottlingWarning : ChatEffect
}

@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions")
class ChatViewModel
    @Inject
    constructor(
        private val chatRepository: ChatRepository,
        private val ragAnswerUseCase: RagAnswerUseCase,
        private val noteRepository: NoteRepository,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
        private val activeModelRepository: com.locus.core.domain.chat.ActiveModelRepository,
        private val modelRegistry: ModelRegistry? = null,
        private val thermalMonitor: ThermalMonitor? = null,
        private val coordinator: com.locus.core.domain.agent.AgentRunCoordinator? = null,
        private val auditJournal: com.locus.core.domain.agent.AuditJournal? = null,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(ChatUiState())
        val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
        private val _effects = Channel<ChatEffect>(Channel.BUFFERED)
        val effects: Flow<ChatEffect> = _effects.receiveAsFlow()
        private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
        val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

        private val _undoableAction = MutableStateFlow<UndoableAction?>(null)
        val undoableAction: StateFlow<UndoableAction?> = _undoableAction.asStateFlow()

        private var messagesJob: Job? = null
        private var generationJob: Job? = null

        init {
            observeSessions()
            observeActiveModel()
            observeModelRegistry()
            observeThermalStatus()
            setupAgentCoordinator()
        }

        private fun observeSessions() {
            viewModelScope.launch(dispatchers.io) {
                chatRepository.observeSessions().collectLatest { sessionsList ->
                    _uiState.update { current ->
                        val newActiveId =
                            when {
                                current.activeSessionId != null &&
                                    sessionsList.any { it.id == current.activeSessionId } ->
                                    current.activeSessionId
                                sessionsList.isNotEmpty() -> sessionsList.first().id
                                else -> null
                            }
                        current.copy(
                            sessions = sessionsList,
                            activeSessionId = newActiveId,
                        )
                    }
                    updateMessagesObservation(_uiState.value.activeSessionId)
                }
            }
        }

        private fun observeActiveModel() {
            viewModelScope.launch(dispatchers.io) {
                activeModelRepository.observeActiveModel().collectLatest { modelInfo ->
                    _uiState.update { it.copy(activeModel = modelInfo) }
                }
            }
        }

        fun selectActiveModel(model: com.locus.core.domain.chat.ActiveModelInfo) {
            viewModelScope.launch(dispatchers.io) { activeModelRepository.setActiveModel(model) }
        }

        private fun observeModelRegistry() {
            modelRegistry?.let { registry ->
                viewModelScope.launch(dispatchers.io) {
                    registry.observeModels().collectLatest { models ->
                        _uiState.update { it.copy(availableModels = models) }
                    }
                }
            }
        }

        private fun observeThermalStatus() {
            val monitor = thermalMonitor ?: return
            viewModelScope.launch(dispatchers.io) {
                monitor.isThrottlingLikely.collectLatest { isThrottling ->
                    if (isThrottling && !_uiState.value.showThermalWarning) {
                        _uiState.update { it.copy(showThermalWarning = true) }
                        _effects.trySend(ChatEffect.ThermalThrottlingWarning)
                    }
                }
            }
        }

        fun dismissThermalWarning() {
            _uiState.update { it.copy(showThermalWarning = false) }
            thermalMonitor?.dismissWarning()
        }

        fun selectModel(entry: RegistryEntry) {
            val modelInfo =
                com.locus.core.domain.chat.ActiveModelInfo(
                    name = entry.ref.id,
                    tier = entry.ref.tier,
                    contextLength = entry.contextLength,
                )
            selectActiveModel(modelInfo)
        }

        private fun updateMessagesObservation(sessionId: String?) {
            messagesJob?.cancel()
            if (sessionId == null) {
                _uiState.update { it.copy(messages = emptyList()) }
                return
            }
            messagesJob =
                viewModelScope.launch(dispatchers.io) {
                    chatRepository.observeMessages(sessionId).collectLatest { messageList ->
                        _uiState.update { it.copy(messages = messageList) }
                    }
                }
        }

        fun selectSession(sessionId: String) {
            if (_uiState.value.activeSessionId == sessionId) return
            _uiState.update { it.copy(activeSessionId = sessionId) }
            updateMessagesObservation(sessionId)
        }

        fun createNewSession(name: String = "New Chat") {
            viewModelScope.launch(dispatchers.io) {
                val session = chatRepository.createSession(name)
                _uiState.update { it.copy(activeSessionId = session.id) }
                updateMessagesObservation(session.id)
            }
        }

        fun sendMessage(content: String) {
            val trimmed = content.trim()
            if (trimmed.isBlank() || _uiState.value.streamingText != null) return

            generationJob =
                viewModelScope.launch(dispatchers.io) {
                    val sessionId =
                        _uiState.value.activeSessionId
                            ?: run {
                                val sessionName = deriveSessionName(trimmed)
                                val newSession = chatRepository.createSession(sessionName)
                                _uiState.update { it.copy(activeSessionId = newSession.id) }
                                updateMessagesObservation(newSession.id)
                                newSession.id
                            }

                    val userMsg =
                        ChatMessage(
                            id = UuidV7.generate(clock),
                            sessionId = sessionId,
                            role = ChatRole.USER,
                            content = trimmed,
                            citations = emptyList(),
                            timestamp = clock.now(),
                        )
                    chatRepository.appendMessage(sessionId, userMsg)

                    val history =
                        _uiState.value.messages.map { msg ->
                            ProviderMessage(
                                role =
                                    when (msg.role) {
                                        ChatRole.USER -> ProviderRole.USER
                                        ChatRole.ASSISTANT -> ProviderRole.ASSISTANT
                                        ChatRole.SYSTEM -> ProviderRole.SYSTEM
                                    },
                                content = msg.content,
                            )
                        }

                    executeStreamingQuery(sessionId, trimmed, history)
                }
        }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun executeStreamingQuery(
            sessionId: String,
            query: String,
            history: List<ProviderMessage>,
        ) {
            _uiState.update { it.copy(streamingText = "") }
            try {
                val answer =
                    ragAnswerUseCase(
                        query = query,
                        history = history,
                        onTokenDelta = { delta ->
                            _uiState.update { current ->
                                current.copy(
                                    streamingText = (current.streamingText ?: "") + delta,
                                )
                            }
                        },
                    )
                val assistantMsg =
                    ChatMessage(
                        id = UuidV7.generate(clock),
                        sessionId = sessionId,
                        role = ChatRole.ASSISTANT,
                        content = answer.text,
                        citations = answer.sources,
                        timestamp = clock.now(),
                    )
                chatRepository.appendMessage(sessionId, assistantMsg)
            } catch (e: CancellationException) {
                val partialText =
                    _uiState.value.streamingText
                        ?.trim()
                        .orEmpty()
                if (partialText.isNotBlank()) {
                    val partialMsg =
                        ChatMessage(
                            id = UuidV7.generate(clock),
                            sessionId = sessionId,
                            role = ChatRole.ASSISTANT,
                            content = partialText,
                            citations = emptyList(),
                            timestamp = clock.now(),
                        )
                    chatRepository.appendMessage(sessionId, partialMsg)
                }
                throw e
            } catch (e: Exception) {
                val errorMsg =
                    ChatMessage(
                        id = UuidV7.generate(clock),
                        sessionId = sessionId,
                        role = ChatRole.ASSISTANT,
                        content = "Error: ${e.message ?: "Failed to generate answer"}",
                        citations = emptyList(),
                        timestamp = clock.now(),
                    )
                chatRepository.appendMessage(sessionId, errorMsg)
            } finally {
                _uiState.update { it.copy(streamingText = null) }
                generationJob = null
            }
        }

        fun stopGeneration() {
            generationJob?.cancel()
            generationJob = null
        }

        fun pinAsNote(
            message: ChatMessage,
            onComplete: ((Note) -> Unit)? = null,
        ) {
            viewModelScope.launch(dispatchers.io) {
                val title = deriveTitle(message.content)
                val note =
                    noteRepository.createNote(
                        folderPath = "",
                        title = title,
                        type = NoteType.NOTE,
                    )
                val fullBody =
                    buildString {
                        append(message.content.trim())
                        if (message.citations.isNotEmpty()) {
                            append("\n\n## Sources\n")
                            for (citation in message.citations) {
                                val displayTitle = citation.noteTitle.ifBlank { "Untitled" }
                                append(
                                    "- [[${citation.noteId}]] [$displayTitle](locus://note/${citation.noteId})\n",
                                )
                            }
                        }
                    }
                noteRepository.edit(note.id, fullBody)
                withContext(dispatchers.main) { onComplete?.invoke(note) }
            }
        }

        private fun deriveSessionName(content: String): String {
            val firstLine = content.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "New Chat"
            val clean = firstLine.removePrefix("#").trim()
            return if (clean.length > MAX_SESSION_NAME_LENGTH) {
                clean.take(SESSION_NAME_ELLIPSIS_LIMIT) + "…"
            } else {
                clean.ifBlank { "New Chat" }
            }
        }

        private fun deriveTitle(content: String): String {
            val firstLine = content.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "Chat Note"
            val clean = firstLine.removePrefix("#").trim()
            return if (clean.length > MAX_TITLE_LENGTH) {
                clean.take(TITLE_ELLIPSIS_LIMIT) + "…"
            } else {
                clean.ifBlank { "Chat Note" }
            }
        }

        private fun setupAgentCoordinator() {
            coordinator?.setConfirmationCallback { request ->
                val deferred = CompletableDeferred<Boolean>()
                _pendingConfirmation.value = PendingConfirmation(request, deferred)
                try {
                    deferred.await()
                } finally {
                    _pendingConfirmation.value = null
                }
            }
        }

        fun confirmPendingAction(confirmed: Boolean) {
            _pendingConfirmation.value?.deferred?.complete(confirmed)
            _pendingConfirmation.value = null
        }

        fun undoAction(entryId: String) {
            viewModelScope.launch(dispatchers.io) {
                auditJournal?.revert(entryId)
                _undoableAction.value = null
            }
        }

        fun dismissUndo() {
            _undoableAction.value = null
        }

        fun showUndoableAction(action: UndoableAction) {
            _undoableAction.value = action
        }
    }
