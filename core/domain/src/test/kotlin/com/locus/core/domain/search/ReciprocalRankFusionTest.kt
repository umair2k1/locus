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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReciprocalRankFusionTest {
    private val rrf = ReciprocalRankFusion()

    @Test
    fun chunkRankedFirstInBothListsOutranksChunkRankedFirstInOnlyOneList() {
        val list1 =
            listOf(
                RankedChunk(chunkId = "chunk-both", noteId = "note-1", rank = 1),
                RankedChunk(chunkId = "chunk-one", noteId = "note-2", rank = 2),
            )
        val list2 =
            listOf(
                RankedChunk(chunkId = "chunk-both", noteId = "note-1", rank = 1),
                RankedChunk(chunkId = "chunk-other", noteId = "note-3", rank = 2),
            )

        val results = rrf.fuse(list1, list2)

        assertEquals(3, results.size)
        assertEquals("chunk-both", results[0].chunkId)
        assertEquals("note-1", results[0].noteId)
        val expectedBothScore = (1.0 / (60 + 1)) + (1.0 / (60 + 1))
        assertEquals(expectedBothScore, results[0].score, 1e-9)

        val expectedSingleScore = 1.0 / (60 + 2)
        assertTrue(results[0].score > results[1].score)
        assertEquals(expectedSingleScore, results[1].score, 1e-9)
        assertEquals(expectedSingleScore, results[2].score, 1e-9)
    }

    @Test
    fun chunkPresentInOnlyVectorListStillAppearsInFusedOutput() {
        val keywordList =
            listOf(
                RankedChunk(chunkId = "chunk-kw", noteId = "note-kw", rank = 1),
            )
        val vectorList =
            listOf(
                RankedChunk(chunkId = "chunk-vec", noteId = "note-vec", rank = 1),
            )

        val results = rrf.fuse(keywordList, vectorList)

        assertEquals(2, results.size)
        val chunkIds = results.map { it.chunkId }.toSet()
        assertTrue("chunk-vec" in chunkIds)
        assertTrue("chunk-kw" in chunkIds)

        val vecResult = results.first { it.chunkId == "chunk-vec" }
        assertEquals("note-vec", vecResult.noteId)
        assertEquals(1.0 / (60 + 1), vecResult.score, 1e-9)
    }

    @Test
    fun customKParameterAdjustsSmoothing() {
        val customRrf = ReciprocalRankFusion(k = 10)
        val list =
            listOf(
                RankedChunk(chunkId = "chunk-1", noteId = "note-1", rank = 1),
            )

        val results = customRrf.fuse(list)

        assertEquals(1, results.size)
        assertEquals(1.0 / (10 + 1), results[0].score, 1e-9)
    }

    @Test
    fun emptyInputsYieldEmptyResults() {
        val results = rrf.fuse(emptyList(), emptyList())
        assertTrue(results.isEmpty())
    }
}
