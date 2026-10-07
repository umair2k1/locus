package com.locus.app.ui.editor

import androidx.lifecycle.SavedStateHandle
import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.InlineAiAction
import com.locus.core.domain.notes.InlineAiUseCase
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
class InlineAiTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testDispatchers =
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = testDispatcher
            override val mainImmediate: CoroutineDispatcher = testDispatcher
            override val io: CoroutineDispatcher = testDispatcher
            override val default: CoroutineDispatcher = testDispatcher
        }

    private lateinit var fakeClient: FakeChatModelClient
    private lateinit var useCase: InlineAiUseCase
    private lateinit var fakeRepo: FakeNoteRepository
    private lateinit var viewModel: EditorViewModel

    @Before
    fun setUp() {
        fakeClient = FakeChatModelClient()
        useCase = InlineAiUseCase(chatModelClient = fakeClient)
        fakeRepo = FakeNoteRepository()
        viewModel =
            EditorViewModel(
                repo = fakeRepo,
                dispatchers = testDispatchers,
                savedStateHandle = SavedStateHandle(),
                inlineAiUseCase = useCase,
            )
    }

    @Test
    fun promptBuilding_formatsCorrectlyForEveryAction() {
        val text = "Lorem ipsum dolor sit amet."

        val sumPrompt = useCase.buildPrompt(InlineAiAction.SUMMARIZE, text)
        assertTrue(sumPrompt.contains("Summarize"))
        assertTrue(sumPrompt.contains(text))

        val rewPrompt = useCase.buildPrompt(InlineAiAction.REWRITE, text)
        assertTrue(rewPrompt.contains("Rewrite"))
        assertTrue(rewPrompt.contains(text))

        val trPrompt = useCase.buildPrompt(InlineAiAction.TRANSLATE, text)
        assertTrue(trPrompt.contains("Translate"))
        assertTrue(trPrompt.contains("English")) // NF-4 requirement
        assertTrue(trPrompt.contains(text))

        val taskPrompt = useCase.buildPrompt(InlineAiAction.EXTRACT_TASKS, text)
        assertTrue(taskPrompt.contains("checklist"))
        assertTrue(taskPrompt.contains(text))
    }

    @Test
    fun selectionSummarize_acceptReplacesExactlySelectedRange() =
        runTest(testDispatcher) {
            val initialBody = "Start of text. Middle paragraph to summarize. End of text."
            viewModel.onBodyChange(initialBody)

            val selectionStart = initialBody.indexOf("Middle paragraph to summarize.")
            val selectionEnd = selectionStart + "Middle paragraph to summarize.".length
            val selectedText = initialBody.substring(selectionStart, selectionEnd)

            fakeClient.responseTokens = listOf("Summary bullet.")

            viewModel.runInlineAi(
                action = InlineAiAction.SUMMARIZE,
                text = selectedText,
                isSelection = true,
                rangeStart = selectionStart,
                rangeEnd = selectionEnd,
            )

            // Loading state
            assertTrue(viewModel.inlineAiState.value?.isLoading == true)
            advanceUntilIdle()

            // Result ready
            assertFalse(viewModel.inlineAiState.value?.isLoading == true)
            assertEquals("Summary bullet.", viewModel.inlineAiState.value?.resultText)

            // Accept replaces exactly the selected range
            viewModel.acceptInlineAi()
            advanceUntilIdle()

            assertNull(viewModel.inlineAiState.value)
            assertEquals("Start of text. Summary bullet. End of text.", viewModel.uiState.value.body)
        }

    @Test
    fun selectionAction_discardLeavesBodyUnchanged() =
        runTest(testDispatcher) {
            val initialBody = "Untouched body content."
            viewModel.onBodyChange(initialBody)

            fakeClient.responseTokens = listOf("Generated text.")
            viewModel.runInlineAi(
                action = InlineAiAction.REWRITE,
                text = initialBody,
                isSelection = true,
                rangeStart = 0,
                rangeEnd = initialBody.length,
            )
            advanceUntilIdle()

            assertNotNull(viewModel.inlineAiState.value)

            // Discard
            viewModel.dismissInlineAi()
            assertNull(viewModel.inlineAiState.value)
            assertEquals(initialBody, viewModel.uiState.value.body)
        }

    private class FakeChatModelClient : ChatModelClient {
        var responseTokens = listOf("AI response")

        override fun generate(
            prompt: String,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> =
            flow {
                for (token in responseTokens) {
                    emit(StreamEvent.TokenDelta(token))
                }
                emit(StreamEvent.Done())
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
}
