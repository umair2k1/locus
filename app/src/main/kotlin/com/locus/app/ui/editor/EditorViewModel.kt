package com.locus.app.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locus.app.navigation.LocusDestinations
import com.locus.core.domain.notes.FlushTrigger
import com.locus.core.domain.notes.InlineAiAction
import com.locus.core.domain.notes.InlineAiUseCase
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.SuggestTagsUseCase
import com.locus.core.domain.search.RelatedNote
import com.locus.core.domain.search.RelatedNotesUseCase
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

data class EditorUiState(
    val body: String = "",
    val title: String = "",
    val isPreview: Boolean = false,
    val type: NoteType = NoteType.NOTE,
)

data class InlineAiReviewState(
    val action: InlineAiAction,
    val originalText: String,
    val resultText: String = "",
    val isSelection: Boolean = false,
    val rangeStart: Int = 0,
    val rangeEnd: Int = 0,
    val isLoading: Boolean = false,
)

data class TagSuggestionState(
    val suggestedTags: List<String>,
    val selectedTags: Set<String> = emptySet(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class EditorViewModel
    @Inject
    constructor(
        private val repo: NoteRepository,
        private val dispatchers: DispatcherProvider,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        private val inlineAiUseCase: InlineAiUseCase? = null,
        private val suggestTagsUseCase: SuggestTagsUseCase? = null,
        private val relatedNotesUseCase: RelatedNotesUseCase? = null,
    ) : ViewModel() {
        private val flushScope = CoroutineScope(SupervisorJob() + dispatchers.io)
        private var currentNoteId: String =
            savedStateHandle.get<String>(LocusDestinations.NOTE_ID_ARG).orEmpty()
        private val editMutex = Mutex()
        private var isCustomTitle: Boolean = false
        private var isTitleActivelyEditing: Boolean = false
        private var titleDebounceJob: Job? = null
        private var observeNotesJob: Job? = null
        private val _uiState = MutableStateFlow(EditorUiState())
        val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()
        private val _inlineAiState = MutableStateFlow<InlineAiReviewState?>(null)
        val inlineAiState: StateFlow<InlineAiReviewState?> = _inlineAiState.asStateFlow()
        private val _tagSuggestionState = MutableStateFlow<TagSuggestionState?>(null)
        val tagSuggestionState: StateFlow<TagSuggestionState?> = _tagSuggestionState.asStateFlow()
        private val _relatedNotes = MutableStateFlow<List<RelatedNote>>(emptyList())
        val relatedNotes: StateFlow<List<RelatedNote>> = _relatedNotes.asStateFlow()

        init {
            if (currentNoteId.isNotEmpty()) {
                loadNote(currentNoteId)
            }
        }

        fun loadNote(id: String) {
            currentNoteId = id
            if (id.isEmpty() || id == "new") {
                isCustomTitle = false
                _uiState.update { it.copy(body = "", title = "") }
                _relatedNotes.value = emptyList()
                return
            }
            isCustomTitle = true
            viewModelScope.launch(dispatchers.io) {
                val initialBody = runCatching { repo.readBody(id) }.getOrDefault("")
                _uiState.update { it.copy(body = initialBody) }
            }
            startObservingNote(id)
            viewModelScope.launch(dispatchers.io) {
                _relatedNotes.value = relatedNotesUseCase?.execute(id) ?: emptyList()
            }
        }

        private fun startObservingNote(id: String) {
            observeNotesJob?.cancel()
            observeNotesJob =
                viewModelScope.launch(dispatchers.io) {
                    repo.observeAllNotes().collect { notes ->
                        val note = notes.find { it.id == id }
                        if (note != null) {
                            _uiState.update { current ->
                                val updatedTitle =
                                    if (isTitleActivelyEditing) current.title else note.title
                                current.copy(title = updatedTitle, type = note.type)
                            }
                        }
                    }
                }
        }

        fun onTitleChange(newTitle: String) {
            isCustomTitle = true
            isTitleActivelyEditing = true
            _uiState.update { it.copy(title = newTitle) }
            titleDebounceJob?.cancel()
            titleDebounceJob =
                viewModelScope.launch(dispatchers.io) {
                    delay(TITLE_DEBOUNCE_MILLIS)
                    editMutex.withLock {
                        if (currentNoteId.isNotEmpty() && currentNoteId != "new") {
                            repo.setTitle(currentNoteId, newTitle)
                        } else if (newTitle.isNotBlank()) {
                            val noteType =
                                if (_uiState.value.body.contains("- [ ]") ||
                                    _uiState.value.body.contains("- [x]")
                                ) {
                                    NoteType.CHECKLIST
                                } else {
                                    NoteType.NOTE
                                }
                            val created =
                                repo.createNote(
                                    folderPath = "",
                                    title = newTitle,
                                    type = noteType,
                                )
                            currentNoteId = created.id
                            startObservingNote(created.id)
                            if (_uiState.value.body.isNotEmpty()) {
                                repo.edit(created.id, _uiState.value.body)
                            }
                        }
                        isTitleActivelyEditing = false
                    }
                }
        }

        fun onBodyChange(newBody: String) {
            val previousTitle = _uiState.value.title
            val derivedTitle = if (!isCustomTitle) deriveTitle(newBody) else previousTitle
            _uiState.update { current -> current.copy(body = newBody, title = derivedTitle) }
            viewModelScope.launch(dispatchers.io) {
                editMutex.withLock {
                    if (currentNoteId == "new" || currentNoteId.isEmpty()) {
                        val titleToUse =
                            if (isCustomTitle && previousTitle.isNotBlank()) {
                                previousTitle
                            } else {
                                derivedTitle
                            }
                        val noteType =
                            if (newBody.contains("- [ ]") || newBody.contains("- [x]")) {
                                NoteType.CHECKLIST
                            } else {
                                NoteType.NOTE
                            }
                        val created =
                            repo.createNote(
                                folderPath = "",
                                title = titleToUse,
                                type = noteType,
                            )
                        currentNoteId = created.id
                        startObservingNote(created.id)
                        if (newBody.isNotEmpty()) {
                            repo.edit(created.id, newBody)
                        }
                    } else {
                        repo.edit(currentNoteId, newBody)
                        if (!isCustomTitle &&
                            derivedTitle != previousTitle &&
                            derivedTitle != "Untitled"
                        ) {
                            repo.setTitle(currentNoteId, derivedTitle)
                        }
                    }
                }
            }
        }

        fun togglePreview() {
            _uiState.update { it.copy(isPreview = !it.isPreview) }
        }

        fun deleteNote(onDeleted: () -> Unit = {}) {
            viewModelScope.launch {
                val id = currentNoteId
                if (id.isNotEmpty() && id != "new") {
                    repo.deleteNote(id)
                    onDeleted()
                }
            }
        }

        fun onDispose() {
            flushScope.launch {
                editMutex.withLock {
                    val id = currentNoteId
                    val pendingTitle = _uiState.value.title
                    if (id.isNotEmpty() && id != "new") {
                        titleDebounceJob?.cancel()
                        if (isCustomTitle) {
                            repo.setTitle(id, pendingTitle)
                        }
                        runCatching { repo.forceFlush(id, FlushTrigger.EDITOR_CLOSE) }
                    }
                    isTitleActivelyEditing = false
                }
            }
        }

        fun onStop() {
            flushScope.launch {
                editMutex.withLock {
                    val id = currentNoteId
                    val pendingTitle = _uiState.value.title
                    if (id.isNotEmpty() && id != "new") {
                        titleDebounceJob?.cancel()
                        if (isCustomTitle) {
                            repo.setTitle(id, pendingTitle)
                        }
                        runCatching { repo.forceFlush(id, FlushTrigger.ON_STOP) }
                    }
                    isTitleActivelyEditing = false
                }
            }
        }

        override fun onCleared() {
            super.onCleared()
            onDispose()
        }

        fun runInlineAi(
            action: InlineAiAction,
            text: String,
            isSelection: Boolean = false,
            rangeStart: Int = 0,
            rangeEnd: Int = 0,
        ) {
            if (text.isBlank()) return
            _inlineAiState.value =
                InlineAiReviewState(
                    action = action,
                    originalText = text,
                    resultText = "",
                    isSelection = isSelection,
                    rangeStart = rangeStart,
                    rangeEnd = rangeEnd,
                    isLoading = true,
                )
            viewModelScope.launch(dispatchers.io) {
                val result = inlineAiUseCase?.execute(action, text) ?: ""
                _inlineAiState.update { it?.copy(resultText = result, isLoading = false) }
            }
        }

        fun acceptInlineAi() {
            val state = _inlineAiState.value ?: return
            val currentBody = _uiState.value.body
            val newBody =
                if (state.isSelection) {
                    val start = state.rangeStart
                    val end = state.rangeEnd
                    if (start in 0..currentBody.length && end in start..currentBody.length) {
                        currentBody.replaceRange(start, end, state.resultText)
                    } else {
                        currentBody.replace(state.originalText, state.resultText)
                    }
                } else {
                    if (state.action == InlineAiAction.SUMMARIZE ||
                        state.action == InlineAiAction.EXTRACT_TASKS
                    ) {
                        currentBody + "\n\n" + state.resultText
                    } else {
                        state.resultText
                    }
                }
            onBodyChange(newBody)
            _inlineAiState.value = null
        }

        fun dismissInlineAi() {
            _inlineAiState.value = null
        }

        fun suggestTags() {
            val body = _uiState.value.body
            if (body.isBlank()) return
            _tagSuggestionState.value =
                TagSuggestionState(
                    suggestedTags = emptyList(),
                    isLoading = true,
                )
            viewModelScope.launch(dispatchers.io) {
                val tags = suggestTagsUseCase?.execute(body) ?: emptyList()
                _tagSuggestionState.value =
                    TagSuggestionState(
                        suggestedTags = tags,
                        selectedTags = tags.toSet(),
                        isLoading = false,
                    )
            }
        }

        fun applySuggestedTags(approvedTags: List<String>) {
            val id = currentNoteId
            if (id.isEmpty() || id == "new") {
                _tagSuggestionState.value = null
                return
            }
            viewModelScope.launch(dispatchers.io) {
                val currentNote = repo.getNote(id)
                val existingTags = currentNote?.tags ?: emptyList()
                val merged = (existingTags + approvedTags).distinct()
                repo.setTags(id, merged)
                _tagSuggestionState.value = null
            }
        }

        fun dismissTagSuggestion() {
            _tagSuggestionState.value = null
        }

        private companion object {
            private const val TITLE_DEBOUNCE_MILLIS = 600L
        }
    }
