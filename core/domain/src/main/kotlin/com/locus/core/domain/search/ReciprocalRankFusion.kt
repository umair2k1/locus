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

data class RankedChunk(
    val chunkId: String,
    val noteId: String,
    val rank: Int,
)

data class FusedResult(
    val chunkId: String,
    val noteId: String,
    val score: Double,
)

/**
 * Reciprocal Rank Fusion (S-1): score = sum over each ranked list the chunk appears in of 1 / (k +
 * rank). A chunk absent from a list contributes nothing for that list. k=60 is RRF's standard
 * smoothing constant.
 */
@Singleton
class ReciprocalRankFusion(
    private val k: Int,
) {
    @Inject
    constructor() : this(DEFAULT_K)

    companion object {
        const val DEFAULT_K = 60
    }

    fun fuse(vararg rankedLists: List<RankedChunk>): List<FusedResult> {
        val scores = linkedMapOf<String, Double>()
        val noteOf = mutableMapOf<String, String>()
        for (list in rankedLists) {
            for (item in list) {
                noteOf[item.chunkId] = item.noteId
                scores[item.chunkId] = (scores[item.chunkId] ?: 0.0) + 1.0 / (k + item.rank)
            }
        }
        return scores.entries.sortedByDescending { it.value }.map {
            FusedResult(it.key, noteOf.getValue(it.key), it.value)
        }
    }
}
