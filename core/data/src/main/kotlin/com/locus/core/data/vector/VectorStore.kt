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

package com.locus.core.data.vector

import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.search.ChunkMetadata
import com.locus.core.domain.search.ChunkRepository
import com.locus.core.domain.search.EmbeddedChunk
import com.locus.core.domain.search.RankedChunk
import com.locus.core.domain.search.SearchScope
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
@Suppress("TooManyFunctions")
class VectorStore
    @Inject
    constructor(
        private val chunkDao: ChunkDao,
        private val noteRepositoryProvider: Provider<NoteRepository>,
    ) : ChunkRepository {
        constructor(
            chunkDao: ChunkDao,
            noteRepository: NoteRepository? = null,
        ) : this(
            chunkDao = chunkDao,
            noteRepositoryProvider =
                Provider { noteRepository ?: error("NoteRepository not provided") },
        )

        var isRebuilding: Boolean = false
        var isModelLoaded: () -> Boolean = { true }

        override suspend fun isAvailable(): Boolean = !isRebuilding && isModelLoaded() && chunkDao.hasChunks()

        companion object {
            private const val MAX_SQL_NOTE_ID_PARAMS = 500
        }

        override suspend fun getMetadata(noteId: String): ChunkMetadata? =
            chunkDao.getMetadata(noteId)?.let {
                ChunkMetadata(
                    sourceChecksum = it.sourceChecksum,
                    embeddingModelId = it.embeddingModelId,
                )
            }

        override suspend fun replaceChunksForNote(
            noteId: String,
            chunks: List<EmbeddedChunk>,
        ) {
            val entities =
                chunks.map { chunk ->
                    ChunkEntity(
                        chunkId = chunk.chunkId,
                        noteId = chunk.noteId,
                        headingPathJson = JSONArray(chunk.headingPath).toString(),
                        text = chunk.text,
                        embedding = chunk.embedding,
                        embeddingModelId = chunk.embeddingModelId,
                        sourceChecksum = chunk.sourceChecksum,
                    )
                }
            chunkDao.replaceChunksForNote(noteId, entities)
        }

        override suspend fun deleteAll() {
            chunkDao.deleteAll()
        }

        override suspend fun countChunks(): Int = chunkDao.countChunks()

        override suspend fun meanEmbeddingForNote(noteId: String): FloatArray? {
            val chunks = chunkDao.getChunksByNoteId(noteId)
            if (chunks.isEmpty()) return null
            return computeMean(chunks.map { it.embedding })
        }

        override suspend fun allNoteMeanEmbeddings(): Map<String, FloatArray> {
            val rows = chunkDao.getAllEmbeddingRows()
            if (rows.isEmpty()) return emptyMap()
            val noteToEmbeddings = mutableMapOf<String, MutableList<FloatArray>>()
            for (row in rows) {
                noteToEmbeddings.getOrPut(row.noteId) { mutableListOf() }.add(row.embedding)
            }
            return noteToEmbeddings.mapValues { (_, embeddings) ->
                computeMean(embeddings)
            }
        }

        private fun computeMean(embeddings: List<FloatArray>): FloatArray {
            val dim = embeddings.first().size
            val mean = FloatArray(dim)
            for (emb in embeddings) {
                val len = minOf(dim, emb.size)
                for (i in 0 until len) {
                    mean[i] += emb[i]
                }
            }
            val count = embeddings.size.toFloat()
            for (i in 0 until dim) {
                mean[i] /= count
            }
            return mean
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            noteIds: Set<String>?,
        ): List<RankedChunk> {
            val candidates = fetchCandidates(noteIds)
            val queryNorm = computeNorm(queryVector)
            if (topK <= 0 || candidates.isEmpty() || queryNorm <= 0f) {
                return emptyList()
            }
            return rankCandidates(candidates, queryVector, queryNorm, topK)
        }

        override suspend fun search(
            queryVector: FloatArray,
            topK: Int,
            scope: SearchScope,
        ): List<RankedChunk> {
            val allowedNoteIds = resolveScopeToNoteIds(scope)
            return search(queryVector, topK, allowedNoteIds)
        }

        override suspend fun getChunksForNote(noteId: String): List<EmbeddedChunk> {
            val entities = chunkDao.getChunksByNoteId(noteId)
            return entities.map { it.toDomain() }
        }

        override suspend fun getChunk(chunkId: String): EmbeddedChunk? {
            val entity = chunkDao.getChunkById(chunkId)
            return entity?.toDomain()
        }

        private suspend fun resolveScopeToNoteIds(scope: SearchScope): Set<String>? {
            if (scope.isUnconstrained()) return null
            val noteRepo = runCatching { noteRepositoryProvider.get() }.getOrNull()
            return if (noteRepo == null) {
                scope.noteIds.ifEmpty { null }
            } else {
                noteRepo
                    .observeAllNotes()
                    .first()
                    .filter { matchesScope(it, scope) }
                    .map { it.id }
                    .toSet()
            }
        }

        private fun matchesScope(
            note: Note,
            scope: SearchScope,
        ): Boolean {
            val after = scope.after
            val before = scope.before
            val matchesNoteIds = scope.noteIds.isEmpty() || note.id in scope.noteIds
            val matchesFolder =
                scope.folderPaths.isEmpty() || matchesFolder(note.folderPath, scope.folderPaths)
            val matchesAfter = after == null || !note.modified.isBefore(after)
            val matchesBefore = before == null || !note.modified.isAfter(before)
            return matchesNoteIds && matchesFolder && matchesAfter && matchesBefore
        }

        private fun matchesFolder(
            entityFolder: String,
            scopedFolders: Set<String>,
        ): Boolean {
            val normalizedEntity = entityFolder.trim().trim('/')
            return scopedFolders.any { scoped ->
                val normalizedScoped = scoped.trim().trim('/')
                if (normalizedScoped.isEmpty()) {
                    normalizedEntity.isEmpty()
                } else {
                    normalizedEntity == normalizedScoped ||
                        normalizedEntity.startsWith("$normalizedScoped/")
                }
            }
        }

        private fun ChunkEntity.toDomain(): EmbeddedChunk =
            EmbeddedChunk(
                chunkId = chunkId,
                noteId = noteId,
                headingPath = parseHeadingPath(headingPathJson),
                text = text,
                embedding = embedding,
                embeddingModelId = embeddingModelId,
                sourceChecksum = sourceChecksum,
            )

        private fun parseHeadingPath(json: String): List<String> =
            try {
                val array = JSONArray(json)
                List(array.length()) { array.getString(it) }
            } catch (_: Exception) {
                emptyList()
            }

        private fun rankCandidates(
            candidates: List<ChunkEmbeddingTuple>,
            queryVector: FloatArray,
            queryNorm: Float,
            topK: Int,
        ): List<RankedChunk> {
            val pq = PriorityQueue<ScoredChunk>(topK, compareBy { it.score })
            var minScore = Float.NEGATIVE_INFINITY

            for (candidate in candidates) {
                if (candidate.embedding.size != queryVector.size) continue
                val score = computeCosineSimilarity(queryVector, queryNorm, candidate.embedding)
                if (pq.size < topK) {
                    pq.offer(ScoredChunk(candidate.chunkId, candidate.noteId, score))
                    if (pq.size == topK) {
                        minScore = pq.peek()?.score ?: Float.NEGATIVE_INFINITY
                    }
                } else if (score > minScore) {
                    pq.poll()
                    pq.offer(ScoredChunk(candidate.chunkId, candidate.noteId, score))
                    minScore = pq.peek()?.score ?: Float.NEGATIVE_INFINITY
                }
            }

            val sortedList = ArrayList<ScoredChunk>(pq.size)
            while (pq.isNotEmpty()) {
                val item = pq.poll() ?: break
                sortedList.add(item)
            }
            sortedList.reverse()

            return sortedList.mapIndexed { index, item ->
                RankedChunk(
                    chunkId = item.chunkId,
                    noteId = item.noteId,
                    rank = index + 1,
                )
            }
        }

        private suspend fun fetchCandidates(noteIds: Set<String>?): List<ChunkEmbeddingTuple> {
            if (noteIds != null && noteIds.isEmpty()) return emptyList()
            return when {
                noteIds == null -> chunkDao.getAllEmbeddingRows()
                noteIds.size <= MAX_SQL_NOTE_ID_PARAMS ->
                    chunkDao.getEmbeddingRowsForNotes(noteIds.toList())
                else -> chunkDao.getAllEmbeddingRows().filter { it.noteId in noteIds }
            }
        }

        private fun computeNorm(vector: FloatArray): Float {
            var sumSq = 0f
            for (v in vector) {
                sumSq += v * v
            }
            return if (sumSq > 0f) sqrt(sumSq) else 0f
        }

        private fun computeCosineSimilarity(
            query: FloatArray,
            queryNorm: Float,
            candidate: FloatArray,
        ): Float {
            var dot = 0f
            var chunkNormSq = 0f
            for (i in query.indices) {
                val qi = query[i]
                val ci = candidate[i]
                dot += qi * ci
                chunkNormSq += ci * ci
            }
            return if (chunkNormSq > 0f) {
                dot / (queryNorm * sqrt(chunkNormSq))
            } else {
                0f
            }
        }

        private class ScoredChunk(
            val chunkId: String,
            val noteId: String,
            val score: Float,
        )
    }
