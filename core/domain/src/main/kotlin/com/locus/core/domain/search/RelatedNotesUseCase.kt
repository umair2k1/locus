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

import com.locus.core.domain.notes.NoteRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class RelatedNote(
    val noteId: String,
    val title: String,
    val similarity: Float,
)

@Singleton
class RelatedNotesUseCase
    @Inject
    constructor(
        private val chunkRepository: ChunkRepository,
        private val noteRepository: NoteRepository,
    ) {
        @Suppress("ReturnCount")
        suspend fun execute(
            noteId: String,
            topN: Int = DEFAULT_TOP_N,
            threshold: Float = DEFAULT_SIMILARITY_THRESHOLD,
        ): List<RelatedNote> {
            val targetMean = chunkRepository.meanEmbeddingForNote(noteId) ?: return emptyList()
            val targetNorm = computeNorm(targetMean)
            if (targetNorm <= 0f) return emptyList()

            val allMeans = chunkRepository.allNoteMeanEmbeddings()
            val allNotes = noteRepository.observeAllNotes().first().associateBy { it.id }
            val matches = mutableListOf<RelatedNote>()
            for ((otherId, otherMean) in allMeans) {
                val title = allNotes[otherId]?.title?.ifBlank { "Untitled" } ?: "Untitled"
                val candidate =
                    computeMatch(noteId, targetMean, targetNorm, otherId, otherMean, threshold, title)
                if (candidate != null) {
                    matches.add(candidate)
                }
            }
            return matches.sortedByDescending { it.similarity }.take(topN)
        }

        @Suppress("LongParameterList", "ReturnCount")
        private fun computeMatch(
            targetId: String,
            targetMean: FloatArray,
            targetNorm: Float,
            otherId: String,
            otherMean: FloatArray,
            threshold: Float,
            title: String,
        ): RelatedNote? {
            if (otherId == targetId) return null
            val otherNorm = computeNorm(otherMean)
            if (otherNorm <= 0f) return null
            val sim = computeCosine(targetMean, targetNorm, otherMean, otherNorm)
            if (sim < threshold) return null
            return RelatedNote(noteId = otherId, title = title, similarity = sim)
        }

        private fun computeNorm(vector: FloatArray): Float {
            var sum = 0f
            for (v in vector) {
                sum += v * v
            }
            return sqrt(sum)
        }

        private fun computeCosine(
            v1: FloatArray,
            norm1: Float,
            v2: FloatArray,
            norm2: Float,
        ): Float {
            var dot = 0f
            val len = minOf(v1.size, v2.size)
            for (i in 0 until len) {
                dot += v1[i] * v2[i]
            }
            val denom = norm1 * norm2
            return if (denom > 0f) dot / denom else 0f
        }

        companion object {
            const val DEFAULT_TOP_N = 5
            const val DEFAULT_SIMILARITY_THRESHOLD = 0.65f
        }
    }
