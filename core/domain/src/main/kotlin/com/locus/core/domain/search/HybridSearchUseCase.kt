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

import javax.inject.Inject
import javax.inject.Singleton

data class PackedChunk(
    val chunkId: String,
    val noteId: String,
    val title: String,
    val headingPath: List<String>,
    val text: String,
    val score: Double,
) {
    fun toSearchResult(): SearchResult =
        SearchResult(
            noteId = noteId,
            title = title,
            snippet = text,
            score = score,
            headingPath = headingPath,
        )
}

data class HybridSearchResult(
    val results: List<SearchResult>,
    val degraded: Boolean,
)

/**
 * Hybrid search use case (S-1, S-5, S-8):
 * 1. Runs keyword search and semantic vector search under the same [SearchScope].
 * 2. Fuses results via [ReciprocalRankFusion].
 * 3. Dedupes to at most [maxChunksPerNote] chunks per note (default 3).
 * 4. Packs the final chunk list so total token estimate fits within [maxContextTokens],
 * ```
 *    dropping lowest-fused-score chunks first.
 * ```
 */
@Singleton
class HybridSearchUseCase
    @Inject
    constructor(
        private val keywordSearch: KeywordSearch,
        private val chunkRepository: ChunkRepository,
        private val embeddingGateway: EmbeddingGateway,
        private val rrf: ReciprocalRankFusion = ReciprocalRankFusion(),
    ) {
        companion object {
            const val DEFAULT_MAX_CONTEXT_TOKENS = 4096
            const val DEFAULT_MAX_CHUNKS_PER_NOTE = 3
            const val DEFAULT_TOP_K = 20
        }

        suspend operator fun invoke(
            query: String,
            scope: SearchScope = SearchScope(),
            maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
            maxChunksPerNote: Int = DEFAULT_MAX_CHUNKS_PER_NOTE,
            topK: Int = DEFAULT_TOP_K,
        ): HybridSearchResult {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return HybridSearchResult(results = emptyList(), degraded = false)

            return if (!chunkRepository.isAvailable()) {
                val keywordResults = keywordSearch.search(trimmed, scope)
                HybridSearchResult(results = keywordResults, degraded = true)
            } else {
                val (keywordResults, keywordRanked) = runKeywordPath(trimmed, scope)
                val vectorRanked = runVectorPath(trimmed, scope, topK)

                val fusedResults = rrf.fuse(keywordRanked, vectorRanked)
                val dedupedFused = deduplicateByNote(fusedResults, maxChunksPerNote)
                val materialized = materializeContent(dedupedFused, keywordResults)
                val packed = packContext(materialized, maxContextTokens)

                HybridSearchResult(
                    results = packed.map { it.toSearchResult() },
                    degraded = false,
                )
            }
        }

        private suspend fun runKeywordPath(
            query: String,
            scope: SearchScope,
        ): Pair<List<SearchResult>, List<RankedChunk>> {
            val keywordResults = keywordSearch.search(query, scope)
            val keywordRanked = mutableListOf<RankedChunk>()
            for ((index, result) in keywordResults.withIndex()) {
                val rank = index + 1
                val noteChunks = chunkRepository.getChunksForNote(result.noteId)
                if (noteChunks.isNotEmpty()) {
                    for (chunk in noteChunks) {
                        keywordRanked.add(RankedChunk(chunk.chunkId, result.noteId, rank))
                    }
                } else {
                    keywordRanked.add(RankedChunk("${result.noteId}_0", result.noteId, rank))
                }
            }
            return Pair(keywordResults, keywordRanked)
        }

        private suspend fun runVectorPath(
            query: String,
            scope: SearchScope,
            topK: Int,
        ): List<RankedChunk> =
            try {
                val queryVector = embeddingGateway.embed(query)
                chunkRepository.search(queryVector, topK, scope)
            } catch (_: Exception) {
                emptyList()
            }

        private fun deduplicateByNote(
            fusedResults: List<FusedResult>,
            maxChunksPerNote: Int,
        ): List<FusedResult> {
            val noteChunkCounts = mutableMapOf<String, Int>()
            val deduped = mutableListOf<FusedResult>()
            for (fused in fusedResults) {
                val count = noteChunkCounts.getOrDefault(fused.noteId, 0)
                if (count < maxChunksPerNote) {
                    deduped.add(fused)
                    noteChunkCounts[fused.noteId] = count + 1
                }
            }
            return deduped
        }

        private suspend fun materializeContent(
            fusedResults: List<FusedResult>,
            keywordResults: List<SearchResult>,
        ): List<PackedChunk> =
            fusedResults.map { fused ->
                val embedded = chunkRepository.getChunk(fused.chunkId)
                val keywordMatch = keywordResults.find { it.noteId == fused.noteId }
                val text = embedded?.text ?: keywordMatch?.snippet ?: ""
                val title = keywordMatch?.title ?: ""
                val headingPath = embedded?.headingPath ?: emptyList()

                PackedChunk(
                    chunkId = fused.chunkId,
                    noteId = fused.noteId,
                    title = title,
                    headingPath = headingPath,
                    text = text,
                    score = fused.score,
                )
            }

        private fun packContext(
            chunks: List<PackedChunk>,
            maxContextTokens: Int,
        ): List<PackedChunk> {
            val packed = chunks.toMutableList()
            var totalTokens = packed.sumOf { estimateTokens(it.text) }
            while (totalTokens > maxContextTokens && packed.isNotEmpty()) {
                val removed = packed.removeAt(packed.lastIndex)
                totalTokens -= estimateTokens(removed.text)
            }
            return packed
        }

        private fun estimateTokens(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
    }
