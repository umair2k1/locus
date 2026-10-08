package com.locus.core.domain.search

data class ChunkMetadata(
    val sourceChecksum: String,
    val embeddingModelId: String,
)

data class EmbeddedChunk(
    val chunkId: String,
    val noteId: String,
    val headingPath: List<String>,
    val text: String,
    val embedding: FloatArray,
    val embeddingModelId: String,
    val sourceChecksum: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbeddedChunk

        if (chunkId != other.chunkId) return false
        if (noteId != other.noteId) return false
        if (headingPath != other.headingPath) return false
        if (text != other.text) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (embeddingModelId != other.embeddingModelId) return false
        if (sourceChecksum != other.sourceChecksum) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + noteId.hashCode()
        result = 31 * result + headingPath.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + embeddingModelId.hashCode()
        result = 31 * result + sourceChecksum.hashCode()
        return result
    }
}

interface ChunkRepository {
    suspend fun isAvailable(): Boolean = true

    suspend fun getMetadata(noteId: String): ChunkMetadata?

    suspend fun replaceChunksForNote(
        noteId: String,
        chunks: List<EmbeddedChunk>,
    )

    suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        noteIds: Set<String>? = null,
    ): List<RankedChunk>

    suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        scope: SearchScope,
    ): List<RankedChunk> = search(queryVector, topK, scope.noteIds.ifEmpty { null })

    suspend fun getChunksForNote(noteId: String): List<EmbeddedChunk> = emptyList()

    suspend fun getChunk(chunkId: String): EmbeddedChunk? = null

    suspend fun deleteAll()

    suspend fun countChunks(): Int = 0

    suspend fun meanEmbeddingForNote(noteId: String): FloatArray? = null

    suspend fun allNoteMeanEmbeddings(): Map<String, FloatArray> = emptyMap()
}
