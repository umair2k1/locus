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

package com.locus.core.domain.search

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridSearchUseCaseTest {
    private class FakeKeywordSearch(
        val resultsProvider: (query: String, scope: SearchScope) -> List<SearchResult> =
            { _, _ ->
                emptyList()
            },
    ) : KeywordSearch {
        override suspend fun search(
            query: String,
            scope: SearchScope,
        ): List<SearchResult> = resultsProvider(query, scope)
    }

    private class FakeChunkRepository(
        var isAvailable: Boolean = true,
        private val chunksMap: MutableMap<String, EmbeddedChunk> = mutableMapOf(),
        private val searchProvider: (queryVector: FloatArray, topK: Int, scope: SearchScope) -> List<RankedChunk> =
            { _, _, _ ->
                emptyList()
            },
    ) : ChunkRepository {
        override suspend fun isAvailable(): Boolean = isAvailable

        fun addChunk(chunk: EmbeddedChunk) {
            chunksMap[chunk.chunkId] = chunk
        }

        override suspend fun getMetadata(noteId: String): ChunkMetadata? = null

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) {
            for (c in chunks) chunksMap[c.chunkId] = c
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> = emptyList()

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            scope: SearchScope,
        ): List<RankedChunk> = searchProvider(queryVector, topK, scope)

        override suspend fun getChunksForNote(noteId: String): List<EmbeddedChunk> {
            val matching = chunksMap.values.filter { it.noteId == noteId }
            return matching
        }

        override suspend fun getChunk(chunkId: String): EmbeddedChunk? = chunksMap[chunkId]

        override suspend fun deleteAll() {
            chunksMap.clear()
        }
    }

    private class FakeEmbeddingGateway : EmbeddingGateway {
        override suspend fun embed(text: String): FloatArray = floatArrayOf(1.0f, 0.0f)
    }

    private fun createChunk(
        chunkId: String,
        noteId: String,
        text: String,
    ): EmbeddedChunk =
        EmbeddedChunk(
            chunkId = chunkId,
            noteId = noteId,
            headingPath = listOf("Heading"),
            text = text,
            embedding = floatArrayOf(1.0f, 0.0f),
            embeddingModelId = "test-model",
            sourceChecksum = "checksum",
        )

    @Test
    fun scopeLimitedToFolderAReturnsZeroHitsFromFolderB_evenWhenContentMatchesIdentically() =
        runTest {
            val chunkA = createChunk("chunk-A_0", "note-A", "Identical query text content in A")
            val chunkB = createChunk("chunk-B_0", "note-B", "Identical query text content in B")

            val chunkRepo =
                FakeChunkRepository(
                    searchProvider = { _, _, scope ->
                        if (scope.folderPaths.contains("folderA")) {
                            listOf(RankedChunk("chunk-A_0", "note-A", 1))
                        } else if (scope.folderPaths.contains("folderB")) {
                            listOf(RankedChunk("chunk-B_0", "note-B", 1))
                        } else {
                            listOf(
                                RankedChunk("chunk-A_0", "note-A", 1),
                                RankedChunk("chunk-B_0", "note-B", 2),
                            )
                        }
                    },
                ).apply {
                    addChunk(chunkA)
                    addChunk(chunkB)
                }

            val keywordSearch =
                FakeKeywordSearch { _, scope ->
                    if (scope.folderPaths.contains("folderA")) {
                        listOf(SearchResult("note-A", "Note A", chunkA.text, 1.0))
                    } else if (scope.folderPaths.contains("folderB")) {
                        listOf(SearchResult("note-B", "Note B", chunkB.text, 1.0))
                    } else {
                        listOf(
                            SearchResult("note-A", "Note A", chunkA.text, 1.0),
                            SearchResult("note-B", "Note B", chunkB.text, 0.5),
                        )
                    }
                }

            val useCase =
                HybridSearchUseCase(
                    keywordSearch = keywordSearch,
                    chunkRepository = chunkRepo,
                    embeddingGateway = FakeEmbeddingGateway(),
                )

            val hybridResult =
                useCase(
                    query = "query",
                    scope = SearchScope(folderPaths = setOf("folderA")),
                )

            // Assert: Only hits from folder A returned, 0 hits from folder B
            assertFalse(hybridResult.degraded)
            val results = hybridResult.results
            assertEquals(1, results.size)
            assertEquals("note-A", results[0].noteId)
            assertTrue(results.none { it.noteId == "note-B" })
        }

    @Test
    fun contextPackingDropsLowestScoredChunk_whenTokenBudgetIsExceeded() =
        runTest {
            // 3 chunks with 5 whitespace words each (5 tokens each)
            val chunkHigh = createChunk("c_high", "note-1", "word1 word2 word3 word4 word5") // 5 tokens
            val chunkMid = createChunk("c_mid", "note-2", "alpha beta gamma delta epsilon") // 5 tokens
            val chunkLow = createChunk("c_low", "note-3", "red blue green yellow orange") // 5 tokens

            val chunkRepo =
                FakeChunkRepository(
                    searchProvider = { _, _, _ ->
                        listOf(
                            RankedChunk("c_high", "note-1", 1),
                            RankedChunk("c_mid", "note-2", 2),
                            RankedChunk("c_low", "note-3", 3),
                        )
                    },
                ).apply {
                    addChunk(chunkHigh)
                    addChunk(chunkMid)
                    addChunk(chunkLow)
                }

            val keywordSearch = FakeKeywordSearch()

            val useCase =
                HybridSearchUseCase(
                    keywordSearch = keywordSearch,
                    chunkRepository = chunkRepo,
                    embeddingGateway = FakeEmbeddingGateway(),
                )

            // Total tokens = 15. Set maxContextTokens = 12 so only top 2 chunks (10 tokens) fit.
            val hybridResult = useCase(query = "test", maxContextTokens = 12)

            assertFalse(hybridResult.degraded)
            val results = hybridResult.results
            assertEquals(2, results.size)
            assertEquals("note-1", results[0].noteId)
            assertEquals("note-2", results[1].noteId)
            // c_low (lowest fused score from note-3) must be dropped
            assertTrue(results.none { it.noteId == "note-3" })
        }

    @Test
    fun perNoteDedupCapsAtConfiguredN() =
        runTest {
            // Note-1 has 5 chunks
            val chunks = (0..4).map { i -> createChunk("note-1_$i", "note-1", "Chunk $i content text") }

            val chunkRepo =
                FakeChunkRepository(
                    searchProvider = { _, _, _ ->
                        chunks.mapIndexed { index, chunk ->
                            RankedChunk(chunk.chunkId, chunk.noteId, index + 1)
                        }
                    },
                ).apply { chunks.forEach { addChunk(it) } }

            val keywordSearch = FakeKeywordSearch()

            val useCase =
                HybridSearchUseCase(
                    keywordSearch = keywordSearch,
                    chunkRepository = chunkRepo,
                    embeddingGateway = FakeEmbeddingGateway(),
                )

            // Cap at N = 2 chunks per note
            val hybridResult = useCase(query = "test", maxChunksPerNote = 2)

            assertFalse(hybridResult.degraded)
            val results = hybridResult.results
            assertEquals(2, results.size)
            assertEquals("Chunk 0 content text", results[0].snippet)
            assertEquals("Chunk 1 content text", results[1].snippet)
            assertEquals(2, results.count { it.noteId == "note-1" })
        }

    @Test
    fun emptyOrBlankQueryReturnsEmptyList() =
        runTest {
            val useCase =
                HybridSearchUseCase(
                    keywordSearch = FakeKeywordSearch(),
                    chunkRepository = FakeChunkRepository(),
                    embeddingGateway = FakeEmbeddingGateway(),
                )

            val result1 = useCase("")
            assertTrue(result1.results.isEmpty())
            assertFalse(result1.degraded)
            val result2 = useCase("   ")
            assertTrue(result2.results.isEmpty())
            assertFalse(result2.degraded)
        }

    @Test
    fun bothKeywordAndVectorContributeToFusedScore() =
        runTest {
            val chunkBoth = createChunk("chunk-both", "note-1", "both content")
            val chunkSingle = createChunk("chunk-single", "note-2", "single content")

            val chunkRepo =
                FakeChunkRepository(
                    searchProvider = { _, _, _ ->
                        listOf(
                            RankedChunk("chunk-both", "note-1", 1),
                            RankedChunk("chunk-single", "note-2", 2),
                        )
                    },
                ).apply {
                    addChunk(chunkBoth)
                    addChunk(chunkSingle)
                }

            // Keyword search also ranks chunk-both #1, but does NOT include chunk-single
            val keywordSearch =
                FakeKeywordSearch { _, _ ->
                    listOf(
                        SearchResult("note-1", "Note 1", "both content", 1.0),
                    )
                }

            val useCase =
                HybridSearchUseCase(
                    keywordSearch = keywordSearch,
                    chunkRepository = chunkRepo,
                    embeddingGateway = FakeEmbeddingGateway(),
                )

            val hybridResult = useCase(query = "test")

            assertFalse(hybridResult.degraded)
            val results = hybridResult.results
            assertEquals(2, results.size)
            assertEquals("note-1", results[0].noteId)
            assertTrue(
                "Chunk in both paths should outrank chunk in single path",
                results[0].score > results[1].score,
            )
        }

    @Test
    fun vectorStoreUnavailableFallsBackToKeywordOnlyWithDegradedTrue() =
        runTest {
            val keywordResults =
                listOf(
                    SearchResult("note-kw-1", "Title 1", "Keyword snippet 1", 1.0),
                    SearchResult("note-kw-2", "Title 2", "Keyword snippet 2", 0.8),
                )
            var keywordSearchCalled = false
            val keywordSearch =
                FakeKeywordSearch { _, _ ->
                    keywordSearchCalled = true
                    keywordResults
                }

            var vectorSearchCalled = false
            var embedCalled = false
            val chunkRepo =
                FakeChunkRepository(
                    isAvailable = false,
                    searchProvider = { _, _, _ ->
                        vectorSearchCalled = true
                        emptyList()
                    },
                )
            val embeddingGateway =
                object : EmbeddingGateway {
                    override suspend fun embed(text: String): FloatArray {
                        embedCalled = true
                        return floatArrayOf(1.0f, 0.0f)
                    }
                }

            val useCase =
                HybridSearchUseCase(
                    keywordSearch = keywordSearch,
                    chunkRepository = chunkRepo,
                    embeddingGateway = embeddingGateway,
                )

            val result = useCase(query = "important topic")

            assertTrue("Expected degraded to be true when vector index is unavailable", result.degraded)
            assertEquals(keywordResults, result.results)
            assertTrue("Keyword search should have been invoked", keywordSearchCalled)
            assertFalse("Vector search should NOT be attempted when unavailable", vectorSearchCalled)
            assertFalse(
                "Embedding gateway should NOT be called when vector leg is skipped",
                embedCalled,
            )
        }
}
