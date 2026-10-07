@file:Suppress("MagicNumber", "EmptyFunctionBlock", "MaxLineLength")

package com.locus.core.ai.toolloop

import com.locus.core.ai.tools.ListFoldersTool
import com.locus.core.ai.tools.ReadNoteResult
import com.locus.core.ai.tools.ReadNoteTool
import com.locus.core.ai.tools.SearchNotesTool
import com.locus.core.ai.tools.SearchResultDto
import com.locus.core.ai.tools.UpdateNoteTool
import com.locus.core.domain.agent.AgentRunCoordinator
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.StreamEvent
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.locus.core.domain.providers.ToolSchema as DomainToolSchema

class JsonModeToolLoopTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Fake completion client that returns canned responses in sequence
    private class FakeCompletionClient(
        private val responses: List<String>,
    ) : JsonModeCompletionClient {
        private var callIndex = 0
        val recordedTranscripts = mutableListOf<List<String>>()

        override suspend fun complete(
            systemPrompt: String,
            transcript: List<String>,
        ): String {
            recordedTranscripts += transcript.toList()
            if (callIndex < responses.size) {
                return responses[callIndex++]
            }
            error("No more canned responses configured (called $callIndex times)")
        }
    }

    private class FakeNoteRepository(
        private val notes: Map<String, String> = emptyMap(),
        private val folders: List<String> = emptyList(),
    ) : NoteRepository {
        override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = emptyFlow()

        override fun observeAllNotes(): Flow<List<Note>> = emptyFlow()

        override suspend fun readBody(noteId: String): String = notes[noteId] ?: throw NoSuchElementException("Note not found: $noteId")

        override suspend fun listFolders(): List<String> = folders

        override suspend fun createFolder(
            parentPath: String,
            name: String,
        ) {}

        override suspend fun createNote(
            folderPath: String,
            title: String,
            type: NoteType,
        ): Note = error("Unused in test")

        override suspend fun edit(
            noteId: String,
            newBody: String,
        ) {}

        override suspend fun setPinned(
            noteId: String,
            pinned: Boolean,
        ) {}

        override suspend fun setColor(
            noteId: String,
            color: String?,
        ) {}

        override suspend fun rescan(): RescanReport = RescanReport(0, 0, 0)
    }

    private class FakeKeywordSearch(
        private val results: List<SearchResult>,
    ) : KeywordSearch {
        override suspend fun search(
            query: String,
            scope: SearchScope,
        ): List<SearchResult> = results
    }

    private class UnavailableChunkRepository : ChunkRepository {
        override suspend fun isAvailable(): Boolean = false

        override suspend fun getMetadata(noteId: String): ChunkMetadata? = null

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) {}

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> = emptyList()

        override suspend fun deleteAll() {}
    }

    private class DummyEmbeddingGateway : EmbeddingGateway {
        override suspend fun embed(text: String): FloatArray = floatArrayOf(0.0f)
    }

    private class FakeProviderAdapter(
        override val capabilities: ProviderCapabilities,
        private val streamHandler: (List<ProviderMessage>, List<DomainToolSchema>) -> List<StreamEvent>,
    ) : ProviderAdapter {
        val calls = mutableListOf<List<ProviderMessage>>()

        override fun streamChat(
            messages: List<ProviderMessage>,
            tools: List<DomainToolSchema>,
        ): Flow<StreamEvent> {
            calls += messages
            return flow {
                for (event in streamHandler(messages, tools)) {
                    emit(event)
                }
            }
        }
    }

    private fun createSeededTools(
        searchResults: List<SearchResult> = emptyList(),
        notes: Map<String, String> = emptyMap(),
        folders: List<String> = emptyList(),
    ): Triple<SearchNotesTool, ReadNoteTool, ListFoldersTool> {
        val hybridSearch =
            HybridSearchUseCase(
                keywordSearch = FakeKeywordSearch(searchResults),
                chunkRepository = UnavailableChunkRepository(),
                embeddingGateway = DummyEmbeddingGateway(),
            )
        val noteRepo = FakeNoteRepository(notes = notes, folders = folders)
        return Triple(
            SearchNotesTool(hybridSearch),
            ReadNoteTool(noteRepo),
            ListFoldersTool(noteRepo),
        )
    }

    @Test
    fun scriptedTwoStepToolThenAnswerSequenceCompletesCorrectly() =
        runTest {
            val seededResults =
                listOf(
                    SearchResult(
                        noteId = "note-project-1",
                        title = "Project Alpha",
                        snippet = "Key requirements for Alpha",
                        score = 1.0,
                    ),
                )
            val (searchTool, readTool, foldersTool) = createSeededTools(searchResults = seededResults)

            val turn1 =
                """{"thought":"Searching for project notes","toolCall":{"name":"search_notes","argumentsJson":"{\"query\":\"Alpha\"}"}}"""
            val turn2 = """{"thought":"Found note","finalAnswer":"Found 1 note for Project Alpha"}"""

            val client = FakeCompletionClient(listOf(turn1, turn2))
            val loop = JsonModeToolLoop(client, listOf(searchTool, readTool, foldersTool))

            val answer = loop.run("Tell me about Alpha")

            assertEquals("Found 1 note for Project Alpha", answer)
            assertEquals(2, client.recordedTranscripts.size)

            val secondTranscript = client.recordedTranscripts[1]
            assertTrue(secondTranscript.any { it.startsWith("ASSISTANT: $turn1") })
            assertTrue(
                secondTranscript.any {
                    it.startsWith("TOOL_RESULT(search_notes):") && it.contains("Project Alpha")
                },
            )
        }

    @Test
    fun unparseableTurnThrowsToolLoopException() =
        runTest {
            val (searchTool) = createSeededTools()
            val client = FakeCompletionClient(listOf("this is plain text not valid JSON"))
            val loop = JsonModeToolLoop(client, listOf(searchTool))

            try {
                loop.run("Hello")
                fail("Expected ToolLoopException")
            } catch (e: ToolLoopException) {
                assertTrue(e.message?.contains("model turn was not valid JSON") == true)
            }
        }

    @Test
    fun turnWithNeitherToolCallNorFinalAnswerThrowsToolLoopException() =
        runTest {
            val (searchTool) = createSeededTools()
            val client = FakeCompletionClient(listOf("""{"thought":"Just wandering thoughts"}"""))
            val loop = JsonModeToolLoop(client, listOf(searchTool))

            try {
                loop.run("Hello")
                fail("Expected ToolLoopException")
            } catch (e: ToolLoopException) {
                assertTrue(e.message?.contains("neither toolCall nor finalAnswer") == true)
            }
        }

    @Test
    fun exceedingMaxIterationsThrowsToolLoopException() =
        runTest {
            val (_, _, foldersTool) = createSeededTools(folders = listOf("FolderA"))
            val repeatTurn =
                """{"thought":"looping","toolCall":{"name":"list_folders","argumentsJson":"{}"}}"""
            val client =
                FakeCompletionClient(
                    List(5) { repeatTurn },
                )
            val loop = JsonModeToolLoop(client, listOf(foldersTool), maxIterations = 3)

            try {
                loop.run("Start")
                fail("Expected ToolLoopException")
            } catch (e: ToolLoopException) {
                assertTrue(e.message?.contains("exceeded 3 iterations without a final answer") == true)
            }
        }

    @Test
    fun unknownToolAppendsToolErrorAndRecoversOnNextTurn() =
        runTest {
            val (_, _, foldersTool) = createSeededTools()
            val turn1 =
                """{"thought":"Calling nonexistent tool","toolCall":{"name":"nonexistent_tool","argumentsJson":"{}"}}"""
            val turn2 = """{"thought":"Recovered","finalAnswer":"I recovered from unknown tool"}"""
            val client = FakeCompletionClient(listOf(turn1, turn2))
            val loop = JsonModeToolLoop(client, listOf(foldersTool))

            val answer = loop.run("Run unknown")

            assertEquals("I recovered from unknown tool", answer)
            val transcript2 = client.recordedTranscripts[1]
            assertTrue(transcript2.any { it == "TOOL_ERROR: unknown tool 'nonexistent_tool'" })
        }

    @Test
    fun toolExecutionExceptionFormattedAsErrorJsonInTranscript() =
        runTest {
            val (_, readTool) =
                createSeededTools(
                    notes = emptyMap(),
                ) // note missing -> throws NoSuchElementException
            val turn1 =
                """{"thought":"Reading missing note","toolCall":{"name":"read_note","argumentsJson":"{\"noteId\":\"missing-id\"}"}}"""
            val turn2 = """{"thought":"Handled error","finalAnswer":"Note was not found"}"""
            val client = FakeCompletionClient(listOf(turn1, turn2))
            val loop = JsonModeToolLoop(client, listOf(readTool))

            val answer = loop.run("Read missing")

            assertEquals("Note was not found", answer)
            val transcript2 = client.recordedTranscripts[1]
            assertTrue(
                transcript2.any {
                    it.startsWith("TOOL_RESULT(read_note):") && it.contains("error")
                },
            )
        }

    @Test
    fun readToolsReturnRealRepositoryDataAgainstSeededFixtures() =
        runTest {
            val seededResults =
                listOf(
                    SearchResult(
                        noteId = "note-test-1",
                        title = "Architecture Guide",
                        snippet = "Clean architecture in Kotlin",
                        score = 0.98,
                        headingPath = listOf("Architecture"),
                    ),
                )
            val seededNotes = mapOf("note-test-1" to "# Architecture Guide\n\nContent here.")
            val seededFolders = listOf("Notes/Work", "Notes/Archive")

            val (searchTool, readTool, foldersTool) =
                createSeededTools(
                    searchResults = seededResults,
                    notes = seededNotes,
                    folders = seededFolders,
                )

            // 1. SearchNotesTool
            val searchOutputJson = searchTool.execute("""{"query":"Architecture"}""")
            val searchResults: List<SearchResultDto> =
                json.decodeFromString(
                    ListSerializer(SearchResultDto.serializer()),
                    searchOutputJson,
                )
            assertEquals(1, searchResults.size)
            assertEquals("note-test-1", searchResults[0].noteId)
            assertEquals("Architecture Guide", searchResults[0].title)

            // 2. ReadNoteTool
            val readOutputJson = readTool.execute("""{"noteId":"note-test-1"}""")
            val readResult: ReadNoteResult =
                json.decodeFromString(ReadNoteResult.serializer(), readOutputJson)
            assertEquals("note-test-1", readResult.noteId)
            assertEquals("# Architecture Guide\n\nContent here.", readResult.body)

            // 3. ListFoldersTool
            val listOutputJson = foldersTool.execute("{}")
            val foldersList: List<String> =
                json.decodeFromString(ListSerializer(String.serializer()), listOutputJson)
            assertEquals(2, foldersList.size)
            assertEquals("Notes/Work", foldersList[0])
            assertEquals("Notes/Archive", foldersList[1])
        }

    @Test
    fun toolOrchestratorRoutesNativeVsJsonMode() =
        runTest {
            val (searchTool, readTool, foldersTool) =
                createSeededTools(folders = listOf("FolderA", "FolderB"))
            val orchestrator =
                ToolOrchestrator(
                    searchNotesTool = searchTool,
                    readNoteTool = readTool,
                    listFoldersTool = foldersTool,
                )

            // 1. Native tools supported -> uses NativeFunctionCallingBridge
            val nativeAdapter =
                FakeProviderAdapter(
                    capabilities =
                        ProviderCapabilities(
                            supportsNativeTools = true,
                            contextLength = 100000,
                            pricePerMillionInputTokens = null,
                            pricePerMillionOutputTokens = null,
                        ),
                    streamHandler = { messages, _ ->
                        if (messages.size == 1) {
                            // First turn: model calls list_folders
                            listOf(
                                StreamEvent.ToolCallDelta(
                                    index = 0,
                                    id = "call-1",
                                    name = "list_folders",
                                    argumentsDelta = "{}",
                                ),
                                StreamEvent.Done(),
                            )
                        } else {
                            // Second turn: model sees tool result and answers
                            listOf(
                                StreamEvent.TokenDelta("Found 2 folders natively"),
                                StreamEvent.Done(),
                            )
                        }
                    },
                )

            val nativeResult = orchestrator.run("List my folders", nativeAdapter)
            assertEquals("Found 2 folders natively", nativeResult)

            // 2. Native tools not supported -> uses JsonModeToolLoop via AdapterJsonModeClient
            var jsonCallCount = 0
            val nonNativeAdapter =
                FakeProviderAdapter(
                    capabilities =
                        ProviderCapabilities(
                            supportsNativeTools = false,
                            contextLength = 100000,
                            pricePerMillionInputTokens = null,
                            pricePerMillionOutputTokens = null,
                        ),
                    streamHandler = { _, _ ->
                        jsonCallCount++
                        if (jsonCallCount == 1) {
                            listOf(
                                StreamEvent.TokenDelta(
                                    """{"thought":"listing","toolCall":{"name":"list_folders","argumentsJson":"{}"}}""",
                                ),
                                StreamEvent.Done(),
                            )
                        } else {
                            listOf(
                                StreamEvent.TokenDelta(
                                    """{"thought":"done","finalAnswer":"Found 2 folders in json mode"}""",
                                ),
                                StreamEvent.Done(),
                            )
                        }
                    },
                )

            val jsonResult = orchestrator.run("List my folders", nonNativeAdapter)
            assertEquals("Found 2 folders in json mode", jsonResult)
        }

    private class FakeTestAgentSettingsStore(
        initialCap: Int = 1,
    ) : AgentSettingsStore {
        private val _bulkCap = MutableStateFlow(initialCap)
        override val bulkCap: Flow<Int> = _bulkCap

        override suspend fun setBulkCap(value: Int) {
            _bulkCap.value = value
        }
    }

    @Test
    fun toolOrchestrator_routesWriteToolsThroughCoordinatorAndEnforcesBulkCap() =
        runTest {
            val (searchTool, readTool, foldersTool) =
                createSeededTools(folders = listOf("Personal"))
            val fakeRepo = FakeNoteRepository()
            val updateTool = UpdateNoteTool(fakeRepo)
            val settingsStore = FakeTestAgentSettingsStore(initialCap = 1)
            val coordinator = AgentRunCoordinator(settingsStore = settingsStore, confirmationCallback = { true })

            val orchestrator =
                ToolOrchestrator(
                    tools = listOf(searchTool, readTool, foldersTool, updateTool),
                    agentRunCoordinator = coordinator,
                )

            val client =
                FakeCompletionClient(
                    listOf(
                        // Turn 1: update note-1 (within cap = 1)
                        """{"thought":"updating 1","toolCall":{"name":"update_note","argumentsJson":"{\"noteId\":\"note-1\",\"body\":\"body 1\"}"}}""",
                        // Turn 2: update note-2 (exceeds cap = 1) -> coordinator throws BulkCapExceededException -> TOOL_ERROR in transcript
                        """{"thought":"updating 2","toolCall":{"name":"update_note","argumentsJson":"{\"noteId\":\"note-2\",\"body\":\"body 2\"}"}}""",
                        // Turn 3: sees TOOL_ERROR and answers
                        """{"thought":"cap hit","finalAnswer":"Stopped because bulk cap exceeded"}""",
                    ),
                )

            val finalResult = orchestrator.runWithJsonClient("Update both notes", client)
            assertEquals("Stopped because bulk cap exceeded", finalResult)

            // Verify transcript recorded TOOL_ERROR for the second call
            val lastTranscript = client.recordedTranscripts.last()
            assertTrue(
                "Transcript should record TOOL_ERROR when bulk cap is exceeded",
                lastTranscript.any { it.startsWith("TOOL_ERROR: Bulk operation cap exceeded") },
            )
        }
}
