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

package com.locus.core.ai.embedding

import com.locus.core.ai.llama.LlamaRuntime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class EmbeddingRunner
    @Inject
    constructor(
        private val runtime: LlamaRuntime,
    ) {
        companion object {
            const val TARGET_DIM = 512
        }

        suspend fun embed(text: String): FloatArray {
            val raw = runtime.embed(text).getOrThrow()
            return poolToTargetDimension(raw)
        }

        private fun poolToTargetDimension(raw: FloatArray): FloatArray {
            if (raw.isEmpty()) {
                return FloatArray(TARGET_DIM)
            }
            val pooled =
                if (raw.size == TARGET_DIM) {
                    raw
                } else {
                    val acc = FloatArray(TARGET_DIM)
                    val counts = IntArray(TARGET_DIM)
                    for (i in raw.indices) {
                        val bin = i % TARGET_DIM
                        acc[bin] += raw[i]
                        counts[bin]++
                    }
                    for (i in 0 until TARGET_DIM) {
                        if (counts[i] > 0) {
                            acc[i] /= counts[i].toFloat()
                        }
                    }
                    normalize(acc)
                }
            return pooled
        }

        private fun normalize(vector: FloatArray): FloatArray {
            var sumSq = 0f
            for (v in vector) {
                sumSq += v * v
            }
            val norm = sqrt(sumSq)
            if (norm > 0f) {
                for (i in vector.indices) {
                    vector[i] /= norm
                }
            }
            return vector
        }
    }
