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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chunks",
    indices =
        [
            Index(value = ["noteId"]),
        ],
)
data class ChunkEntity(
    @PrimaryKey val chunkId: String,
    val noteId: String,
    val headingPathJson: String,
    val text: String,
    val embedding: FloatArray,
    val embeddingModelId: String,
    val sourceChecksum: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChunkEntity

        if (chunkId != other.chunkId) return false
        if (noteId != other.noteId) return false
        if (headingPathJson != other.headingPathJson) return false
        if (text != other.text) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (embeddingModelId != other.embeddingModelId) return false
        if (sourceChecksum != other.sourceChecksum) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + noteId.hashCode()
        result = 31 * result + headingPathJson.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + embeddingModelId.hashCode()
        result = 31 * result + sourceChecksum.hashCode()
        return result
    }
}

data class ChunkMetadataTuple(
    val sourceChecksum: String,
    val embeddingModelId: String,
)

data class ChunkEmbeddingTuple(
    val chunkId: String,
    val noteId: String,
    val embedding: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChunkEmbeddingTuple

        if (chunkId != other.chunkId) return false
        if (noteId != other.noteId) return false
        if (!embedding.contentEquals(other.embedding)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkId.hashCode()
        result = 31 * result + noteId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
