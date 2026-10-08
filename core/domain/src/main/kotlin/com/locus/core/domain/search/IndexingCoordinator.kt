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

import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.NoteRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IndexingCoordinator(
    private val embeddingGateway: EmbeddingGateway,
    private val chunkRepository: ChunkRepository,
    private val noteRepository: NoteRepository,
    private val noteChunker: NoteChunker = NoteChunker(),
    initialModelId: String = DEFAULT_MODEL_ID,
) {
    @Inject
    constructor(
        embeddingGateway: EmbeddingGateway,
        chunkRepository: ChunkRepository,
        noteRepository: NoteRepository,
    ) : this(
        embeddingGateway = embeddingGateway,
        chunkRepository = chunkRepository,
        noteRepository = noteRepository,
        noteChunker = NoteChunker(),
        initialModelId = DEFAULT_MODEL_ID,
    )

    companion object {
        const val DEFAULT_MODEL_ID = "embeddinggemma-300M-Q8_0.gguf"
    }

    var currentModelId: String = initialModelId
        private set

    suspend fun reindexIfNeeded(
        note: Note,
        body: String,
    ) {
        val existing = chunkRepository.getMetadata(note.id)
        if (existing != null &&
            existing.sourceChecksum == note.checksum &&
            existing.embeddingModelId == currentModelId
        ) {
            return
        }

        val chunks = noteChunker.chunk(note.id, note.title, body)
        val embeddedChunks =
            chunks.map { chunk ->
                val embedding = embeddingGateway.embed(chunk.text)
                EmbeddedChunk(
                    chunkId = "${note.id}_${chunk.index}",
                    noteId = note.id,
                    headingPath = chunk.headingPath,
                    text = chunk.text,
                    embedding = embedding,
                    embeddingModelId = currentModelId,
                    sourceChecksum = note.checksum,
                )
            }

        chunkRepository.replaceChunksForNote(note.id, embeddedChunks)
    }

    suspend fun reindexAllForModelChange(newModelId: String) {
        currentModelId = newModelId
        val notes = noteRepository.observeAllNotes().first()
        for (note in notes) {
            val body = noteRepository.readBody(note.id)
            reindexIfNeeded(note, body)
        }
    }

    suspend fun getTotalChunkCount(): Int = chunkRepository.countChunks()

    suspend fun estimateReindexTime(tokPerSecond: Double): Double {
        val count = getTotalChunkCount()
        if (count <= 0 || tokPerSecond <= 0.0) return 0.0
        return count.toDouble() / tokPerSecond
    }
}
