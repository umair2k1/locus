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

package com.locus.core.domain.chat

import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.ProviderRole
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RagAnswerUseCaseTest {
    private class FakeProviderAdapter(
        private val eventsProvider: (List<ProviderMessage>) -> List<StreamEvent>,
    ) : ProviderAdapter {
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                supportsNativeTools = false,
                contextLength = 8192,
                pricePerMillionInputTokens = null,
                pricePerMillionOutputTokens = null,
            )
        var lastRecordedMessages: List<ProviderMessage> = emptyList()

        override fun streamChat(
            messages: List<ProviderMessage>,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> {
            lastRecordedMessages = messages
            return flow {
                for (event in eventsProvider(messages)) {
                    emit(event)
                }
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

    private class UnavailableChunkRepository : ChunkRepository {
        override suspend fun isAvailable(): Boolean = false

        override suspend fun getMetadata(noteId: String): ChunkMetadata? = null

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) {
            error("Unused in RAG answer test")
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> = emptyList()

        override suspend fun deleteAll() {
            error("Unused in RAG answer test")
        }
    }

    private class DummyEmbeddingGateway : EmbeddingGateway {
        override suspend fun embed(text: String): FloatArray = floatArrayOf(0.0f)
    }

    private fun createUseCase(
        searchResults: List<SearchResult>,
        cannedEvents: List<StreamEvent>,
    ): Pair<RagAnswerUseCase, FakeProviderAdapter> {
        val hybridSearch =
            HybridSearchUseCase(
                keywordSearch = FakeKeywordSearch(searchResults),
                chunkRepository = UnavailableChunkRepository(),
                embeddingGateway = DummyEmbeddingGateway(),
            )
        val providerAdapter = FakeProviderAdapter { cannedEvents }
        val useCase =
            RagAnswerUseCase(
                hybridSearch = hybridSearch,
                providerAdapter = providerAdapter,
            )
        return Pair(useCase, providerAdapter)
    }

    private fun createCannedTokens(vararg tokens: String): List<StreamEvent> =
        tokens.map { StreamEvent.TokenDelta(it) } + listOf(StreamEvent.Done())

    @Test
    fun resultingRagAnswerSourcesAlwaysHasAtLeastOneEntryWhenSearchReturnsAtLeastOneChunk() =
        runTest {
            val chunk =
                SearchResult(
                    noteId = "note-geo-1",
                    title = "Geography of France",
                    snippet = "Paris is the capital and most populous city of France.",
                    headingPath = listOf("Overview"),
                )
            val (useCase, _) =
                createUseCase(
                    searchResults = listOf(chunk),
                    cannedEvents =
                        createCannedTokens("Paris is the capital of France [1]."),
                )

            val answer = useCase(query = "What is the capital of France?")

            assertTrue("Sources should have at least 1 entry", answer.sources.isNotEmpty())
            assertEquals(1, answer.sources.size)
            assertEquals("note-geo-1", answer.sources[0].noteId)
            assertEquals("Geography of France", answer.sources[0].noteTitle)
            assertEquals(listOf("Overview"), answer.sources[0].headingPath)
            assertTrue(answer.text.contains("[1]"))
        }

    @Test
    fun outOfRangeCitationInModelOutputIsStrippedFromText() =
        runTest {
            val chunk =
                SearchResult(
                    noteId = "note-math-1",
                    title = "Number Theory",
                    snippet = "Prime numbers have exactly two distinct positive divisors.",
                )
            val (useCase, _) =
                createUseCase(
                    searchResults = listOf(chunk),
                    cannedEvents =
                        createCannedTokens(
                            "Prime numbers have two divisors [1]",
                            " and magic numbers have infinite divisors [99].",
                        ),
                )

            val answer = useCase(query = "Explain primes")

            assertFalse("Out-of-range citation [99] must be stripped", answer.text.contains("[99]"))
            assertTrue("Valid citation [1] must remain", answer.text.contains("[1]"))
            assertEquals(1, answer.sources.size)
        }

    @Test
    fun outOfRangeCitationStrippedWhenOnlyOutOfRangeCitationProvidedAndMandatoryCitationEnsured() =
        runTest {
            val chunk =
                SearchResult(
                    noteId = "note-history-1",
                    title = "French Revolution",
                    snippet = "The Bastille was stormed on July 14, 1789.",
                )
            val (useCase, _) =
                createUseCase(
                    searchResults = listOf(chunk),
                    cannedEvents =
                        createCannedTokens(
                            "The Bastille was stormed in 1789 [42].",
                        ),
                )

            val answer = useCase(query = "When was the Bastille stormed?")

            assertFalse(
                "Out-of-range citation [42] must be stripped",
                answer.text.contains("[42]"),
            )
            assertTrue("Mandatory citation [1] must be appended", answer.text.contains("[1]"))
            assertTrue("Sources should have at least 1 entry", answer.sources.isNotEmpty())
            assertEquals("note-history-1", answer.sources[0].noteId)
        }

    @Test
    fun modelWithoutCitationsGetsMandatoryCitationMarkerWhenChunksExist() =
        runTest {
            val chunk =
                SearchResult(
                    noteId = "note-physics-1",
                    title = "Relativity",
                    snippet = "Speed of light is approximately 300,000 km/s.",
                )
            val (useCase, _) =
                createUseCase(
                    searchResults = listOf(chunk),
                    cannedEvents =
                        createCannedTokens(
                            "The speed of light is 300,000 km/s in a vacuum.",
                        ),
                )

            val answer = useCase(query = "Speed of light?")

            assertTrue("Non-trivial answer must contain citation marker", answer.text.contains("[1]"))
            assertTrue("Sources must have at least 1 entry", answer.sources.isNotEmpty())
            assertEquals("note-physics-1", answer.sources[0].noteId)
        }

    @Test
    fun modelWithoutCitationsSelectsMatchingChunkIndexInsteadOfDefaultingToFirstChunk() =
        runTest {
            val chunk1 =
                SearchResult(
                    noteId = "note-hotel",
                    title = "Hotel Booking",
                    snippet = "Hotel reservation in Chicago for three nights.",
                )
            val chunk2 =
                SearchResult(
                    noteId = "note-fuel",
                    title = "Car Expenses",
                    snippet = "Car rental fuel cost was 45 dollars at the station.",
                )
            val (useCase, _) =
                createUseCase(
                    searchResults = listOf(chunk1, chunk2),
                    cannedEvents =
                        createCannedTokens(
                            "The cost of the fuel was 45 dollars.",
                        ),
                )

            val answer = useCase(query = "what was the cost of the fuel")

            assertTrue("Answer must cite matching chunk [2]", answer.text.contains("[2]"))
            assertFalse("Answer must not incorrectly cite chunk [1]", answer.text.contains("[1]"))
            assertEquals(2, answer.sources.size)
            assertEquals("note-fuel", answer.sources[1].noteId)
        }

    @Test
    fun searchReturningZeroChunksYieldsZeroSourcesAndStripsHallucinatedCitations() =
        runTest {
            val (useCase, _) =
                createUseCase(
                    searchResults = emptyList(),
                    cannedEvents = createCannedTokens("I found no notes about that [1]."),
                )

            val answer = useCase(query = "Unknown topic")

            assertTrue(
                "Sources must be empty when search returned zero chunks",
                answer.sources.isEmpty(),
            )
            assertFalse("Hallucinated citation [1] must be stripped", answer.text.contains("[1]"))
        }

    @Test
    fun multipleChunksAreAllPreservedInStructuredSources() =
        runTest {
            val chunks =
                listOf(
                    SearchResult(
                        noteId = "note-1",
                        title = "Architecture",
                        snippet = "Clean architecture separates concerns.",
                        headingPath = listOf("Design", "Clean"),
                    ),
                    SearchResult(
                        noteId = "note-2",
                        title = "Patterns",
                        snippet = "Dependency inversion decouples modules.",
                        headingPath = listOf("SOLID"),
                    ),
                )
            val (useCase, _) =
                createUseCase(
                    searchResults = chunks,
                    cannedEvents =
                        createCannedTokens(
                            "Architecture separates concerns [1] and decouples modules [2].",
                        ),
                )

            val answer = useCase(query = "Software design principles")

            assertEquals(2, answer.sources.size)
            assertEquals("note-1", answer.sources[0].noteId)
            assertEquals("Architecture", answer.sources[0].noteTitle)
            assertEquals(listOf("Design", "Clean"), answer.sources[0].headingPath)
            assertEquals("note-2", answer.sources[1].noteId)
            assertEquals("Patterns", answer.sources[1].noteTitle)
            assertEquals(listOf("SOLID"), answer.sources[1].headingPath)
        }

    @Test
    fun onTokenDeltaReceivesStreamedDeltas() =
        runTest {
            val chunk =
                SearchResult(
                    noteId = "note-stream-1",
                    title = "Streaming Guide",
                    snippet = "Tokens stream in real time.",
                )
            val (useCase, _) =
                createUseCase(
                    searchResults = listOf(chunk),
                    cannedEvents = createCannedTokens("Real ", "time ", "streaming [1]."),
                )

            val receivedDeltas = mutableListOf<String>()
            val answer =
                useCase(
                    query = "How does streaming work?",
                    onTokenDelta = { receivedDeltas.add(it) },
                )

            assertEquals(listOf("Real ", "time ", "streaming [1]."), receivedDeltas)
            assertTrue(answer.text.contains("[1]"))
        }

    @Test
    fun ragAnswerUseCaseUsesFullBodyFromNoteRepositoryWhenAvailable() =
        runTest {
            val chunk =
                SearchResult(
                    noteId = "note-full-1",
                    title = "Bike Maintenance",
                    snippet = "bike refueled...",
                )
            val hybridSearch =
                HybridSearchUseCase(
                    keywordSearch = FakeKeywordSearch(listOf(chunk)),
                    chunkRepository = UnavailableChunkRepository(),
                    embeddingGateway = DummyEmbeddingGateway(),
                )
            val fakeNoteRepo =
                object : NoteRepository {
                    override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = error("unused")

                    override fun observeAllNotes(): Flow<List<Note>> = error("unused")

                    override suspend fun readBody(noteId: String): String {
                        val content = "bike refueled on 8 sept 2026 for 1241.43 rs at shell station"
                        return content
                    }

                    override suspend fun listFolders(): List<String> = emptyList()

                    override suspend fun createFolder(
                        parentPath: String,
                        name: String,
                    ) = Unit

                    override suspend fun createNote(
                        folderPath: String,
                        title: String,
                        type: NoteType,
                    ): Note = error("unused")

                    override suspend fun edit(
                        noteId: String,
                        newBody: String,
                    ) = Unit

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
            val adapter =
                FakeProviderAdapter {
                    createCannedTokens("Your bike was refueled on 8 Sept 2026 [1].")
                }
            val useCase =
                RagAnswerUseCase(
                    hybridSearch = hybridSearch,
                    providerAdapter = adapter,
                    noteRepository = fakeNoteRepo,
                )

            val answer = useCase(query = "when was my bike refueled?")

            assertTrue(answer.text.contains("[1]"))
            val systemPrompt =
                adapter.lastRecordedMessages.firstOrNull { it.role == ProviderRole.SYSTEM }?.content
            assertTrue(
                "System prompt should contain full body from NoteRepository",
                systemPrompt?.contains("1241.43 rs at shell station") == true,
            )
        }

    private class FakeChatModelClient(
        private val cannedText: String,
    ) : ChatModelClient {
        var generateCalled = false
        var lastPrompt: String? = null

        override fun generate(
            prompt: String,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> {
            generateCalled = true
            lastPrompt = prompt
            return flow {
                emit(StreamEvent.TokenDelta(cannedText))
                emit(StreamEvent.Done("stop"))
            }
        }
    }

    private class FakeActiveModelRepository(
        private var currentModel: ActiveModelInfo,
    ) : ActiveModelRepository {
        override fun observeActiveModel(): Flow<ActiveModelInfo> = flow { emit(currentModel) }

        override suspend fun setActiveModel(model: ActiveModelInfo) {
            currentModel = model
        }

        override fun getActiveModel(): ActiveModelInfo = currentModel
    }

    @Test
    fun ragAnswerUseCase_whenActiveModelIsLocal_routesToLocalChatClient() =
        runTest {
            val searchResult =
                SearchResult(
                    noteId = "n1",
                    title = "Offline Note",
                    snippet = "Offline local note text.",
                    headingPath = emptyList(),
                )
            val hybridSearch =
                HybridSearchUseCase(
                    keywordSearch = FakeKeywordSearch(listOf(searchResult)),
                    chunkRepository = UnavailableChunkRepository(),
                    embeddingGateway = DummyEmbeddingGateway(),
                )
            val cloudAdapter = FakeProviderAdapter { error("Cloud adapter should not be called") }
            val localClient = FakeChatModelClient("Local generated response [1].")
            val activeModelRepo =
                FakeActiveModelRepository(
                    ActiveModelInfo(name = "qwen-4b", tier = ModelTier.LOCAL),
                )

            val useCase =
                RagAnswerUseCase(
                    hybridSearch = hybridSearch,
                    providerAdapter = cloudAdapter,
                    noteRepository = null,
                    activeModelRepository = activeModelRepo,
                    localChatClient = localClient,
                )

            val answer = useCase(query = "local query")

            assertTrue("Local client should have been called", localClient.generateCalled)
            assertEquals("Local generated response [1].", answer.text)
            assertEquals(1, answer.sources.size)
            assertEquals(0, cloudAdapter.lastRecordedMessages.size)
        }

    @Test
    fun ragAnswerUseCase_whenChunksEmpty_includesNegativeGroundingInPrompt() =
        runTest {
            val (useCase, adapter) =
                createUseCase(
                    searchResults = emptyList(),
                    cannedEvents =
                        listOf(StreamEvent.TokenDelta("I cannot find that in your notes.")),
                )

            val answer = useCase(query = "what is my passport number?")

            val systemMsg =
                adapter.lastRecordedMessages.firstOrNull { it.role == ProviderRole.SYSTEM }?.content
            assertTrue(
                "System prompt must include negative grounding",
                systemMsg?.contains("DO NOT guess, fabricate") == true,
            )
            assertTrue(
                "System prompt must note no chunks found",
                systemMsg?.contains("No relevant notes or context chunks were found") == true,
            )
            assertEquals("I cannot find that in your notes.", answer.text)
            assertEquals(0, answer.sources.size)
        }

    @Test
    fun ragAnswerUseCase_whenFollowUpQueryReturnsZeroChunks_retriesWithContextualQuery() =
        runTest {
            val noteResult =
                SearchResult(
                    noteId = "bike-1",
                    title = "bike refuel date",
                    snippet = "bike refueled - 8 sept 2026 - 1241.43 INR",
                    headingPath = emptyList(),
                )

            val dynamicSearch =
                object : KeywordSearch {
                    override suspend fun search(
                        query: String,
                        scope: SearchScope,
                    ): List<SearchResult> =
                        if (query.contains("bike refuel")) {
                            listOf(noteResult)
                        } else {
                            emptyList()
                        }
                }

            val hybridSearch =
                HybridSearchUseCase(
                    keywordSearch = dynamicSearch,
                    chunkRepository = UnavailableChunkRepository(),
                    embeddingGateway = DummyEmbeddingGateway(),
                )

            var recordedPrompt: String? = null
            val adapter =
                FakeProviderAdapter { msgs ->
                    recordedPrompt = msgs.firstOrNull { it.role == ProviderRole.SYSTEM }?.content
                    listOf(StreamEvent.TokenDelta("The cost was 1241.43 INR [1]."))
                }

            val useCase =
                RagAnswerUseCase(
                    hybridSearch = hybridSearch,
                    providerAdapter = adapter,
                )

            val history =
                listOf(
                    ProviderMessage(role = ProviderRole.USER, content = "bike refuel date"),
                    ProviderMessage(
                        role = ProviderRole.ASSISTANT,
                        content = "Your bike was refueled on 8 Sept 2026.",
                    ),
                )

            val answer = useCase(query = "what was the cost", history = history)

            assertEquals("The cost was 1241.43 INR [1].", answer.text)
            assertEquals(1, answer.sources.size)
            assertEquals("bike refuel date", answer.sources[0].noteTitle)
            assertTrue(
                "Prompt must contain the retrieved note chunk",
                recordedPrompt?.contains("1241.43 INR") == true,
            )
        }

    @Test
    fun ragAnswerUseCase_whenUserAsksToSummarizeNotes_retrievesAllNotesAndProvidesContextToModel() =
        runTest {
            val note1 =
                Note(
                    id = "n1",
                    title = "Last Prompt Ran",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = java.time.Instant.ofEpochMilli(1000L),
                    modified = java.time.Instant.ofEpochMilli(1000L),
                    checksum = "c1",
                )
            val note2 =
                Note(
                    id = "n2",
                    title = "bike refuel date",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = java.time.Instant.ofEpochMilli(2000L),
                    modified = java.time.Instant.ofEpochMilli(2000L),
                    checksum = "c2",
                )

            val fakeNoteRepo =
                object : NoteRepository {
                    override fun observeNotesInFolder(folderPath: String): Flow<List<Note>> = error("unused")

                    override fun observeAllNotes(): Flow<List<Note>> {
                        val list = listOf(note1, note2)
                        return kotlinx.coroutines.flow.flowOf(list)
                    }

                    override suspend fun readBody(noteId: String): String =
                        if (noteId == "n1") {
                            "Prompt #14 - 17th sept"
                        } else {
                            "bike refueled 1241.43 INR"
                        }

                    override suspend fun listFolders(): List<String> = emptyList()

                    override suspend fun createFolder(
                        parentPath: String,
                        name: String,
                    ) = Unit

                    override suspend fun createNote(
                        folderPath: String,
                        title: String,
                        type: NoteType,
                    ): Note = error("unused")

                    override suspend fun edit(
                        noteId: String,
                        newBody: String,
                    ) = Unit

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

            val emptySearch =
                HybridSearchUseCase(
                    keywordSearch = FakeKeywordSearch(emptyList()),
                    chunkRepository = UnavailableChunkRepository(),
                    embeddingGateway = DummyEmbeddingGateway(),
                )

            var recordedPrompt: String? = null
            val adapter =
                FakeProviderAdapter { messages ->
                    recordedPrompt =
                        messages.firstOrNull { it.role == ProviderRole.SYSTEM }?.content
                    listOf(
                        StreamEvent.TokenDelta("Here is a summary of your notes [1] [2]."),
                        StreamEvent.Done(),
                    )
                }

            val useCase =
                RagAnswerUseCase(
                    hybridSearch = emptySearch,
                    providerAdapter = adapter,
                    noteRepository = fakeNoteRepo,
                )

            val answer = useCase(query = "summarize my notes")

            assertEquals(2, answer.sources.size)
            assertEquals("n1", answer.sources[0].noteId)
            assertEquals("n2", answer.sources[1].noteId)
            assertTrue(recordedPrompt?.contains("Last Prompt Ran") == true)
            assertTrue(recordedPrompt?.contains("Prompt #14") == true)
            assertTrue(recordedPrompt?.contains("bike refuel date") == true)
            assertTrue(recordedPrompt?.contains("1241.43 INR") == true)
        }

    @Test
    fun conversationalGreetingQuery_yieldsZeroSources_andDoesNotForceCitation() =
        runTest {
            val chunks =
                listOf(
                    SearchResult(
                        noteId = "note-1",
                        title = "Personal Apps",
                        snippet = "TurboTransfer and locus.",
                    ),
                    SearchResult(
                        noteId = "note-2",
                        title = "Last Prompt Ran",
                        snippet = "Prompt #14 completed.",
                    ),
                    SearchResult(
                        noteId = "note-3",
                        title = "bike refuel date",
                        snippet = "1241.43 INR fuel.",
                    ),
                )
            var systemPrompt: String? = null
            val adapter =
                FakeProviderAdapter { messages ->
                    systemPrompt = messages.firstOrNull { it.role == ProviderRole.SYSTEM }?.content
                    listOf(
                        StreamEvent.TokenDelta("Hello! How can I help you with your notes today?"),
                        StreamEvent.Done(),
                    )
                }

            val hybridSearch =
                HybridSearchUseCase(
                    keywordSearch = FakeKeywordSearch(chunks),
                    chunkRepository = UnavailableChunkRepository(),
                    embeddingGateway = DummyEmbeddingGateway(),
                )
            val useCase =
                RagAnswerUseCase(
                    hybridSearch = hybridSearch,
                    providerAdapter = adapter,
                )

            val answer = useCase(query = "hi")

            assertEquals("Hello! How can I help you with your notes today?", answer.text)
            assertFalse("Greeting must not have citation marker appended", answer.text.contains("["))
            assertTrue("Sources must be empty for conversational query", answer.sources.isEmpty())
            assertTrue(
                "System prompt should use conversational instruction without chunks",
                systemPrompt?.contains("If the user is merely greeting you") == true,
            )
            assertFalse(
                "System prompt must not contain irrelevant chunks",
                systemPrompt?.contains("bike refuel date") == true,
            )
        }

    @Test
    fun refusalAnswer_whenChunksRetrieved_doesNotForceCitation_andYieldsZeroSources() =
        runTest {
            val chunks =
                listOf(
                    SearchResult(
                        noteId = "note-1",
                        title = "Personal Apps",
                        snippet = "TurboTransfer and locus.",
                    ),
                    SearchResult(
                        noteId = "note-2",
                        title = "bike refuel date",
                        snippet = "1241.43 INR fuel.",
                    ),
                )
            val (useCase, _) =
                createUseCase(
                    searchResults = chunks,
                    cannedEvents =
                        createCannedTokens(
                            "I could not find any information about your passport number in your notes.",
                        ),
                )

            val answer = useCase(query = "what is my passport number?")

            assertEquals(
                "I could not find any information about your passport number in your notes.",
                answer.text,
            )
            assertFalse(
                "Refusal answer must not have citation marker appended",
                answer.text.contains("["),
            )
            assertTrue(
                "Sources must be empty when no citations exist in text",
                answer.sources.isEmpty(),
            )
        }

    @Test
    fun conversationalGreetingWithQuestion_stillPerformsRetrieval() =
        runTest {
            val chunk =
                SearchResult(
                    noteId = "note-fuel",
                    title = "bike refuel date",
                    snippet = "1241.43 INR fuel cost.",
                )
            var searchCalled = false
            val search =
                object : KeywordSearch {
                    override suspend fun search(
                        query: String,
                        scope: SearchScope,
                    ): List<SearchResult> {
                        searchCalled = true
                        return listOf(chunk)
                    }
                }
            val adapter =
                FakeProviderAdapter {
                    listOf(
                        StreamEvent.TokenDelta("The bike was refueled for 1241.43 INR [1]."),
                        StreamEvent.Done(),
                    )
                }
            val hybridSearch =
                HybridSearchUseCase(
                    keywordSearch = search,
                    chunkRepository = UnavailableChunkRepository(),
                    embeddingGateway = DummyEmbeddingGateway(),
                )
            val useCase = RagAnswerUseCase(hybridSearch = hybridSearch, providerAdapter = adapter)

            val answer = useCase(query = "hi, what do my notes say about the bike refuel?")

            assertTrue("Search must be called for question with greeting prefix", searchCalled)
            assertEquals(1, answer.sources.size)
            assertEquals("note-fuel", answer.sources[0].noteId)
            assertTrue(answer.text.contains("[1]"))
        }
}
