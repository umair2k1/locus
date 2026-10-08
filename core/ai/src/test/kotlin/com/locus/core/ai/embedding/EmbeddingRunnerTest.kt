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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class EmbeddingRunnerTest {
    private class FakeLlamaRuntime(
        private val mockResult: Result<FloatArray>,
    ) : LlamaRuntime() {
        override suspend fun embed(text: String): Result<FloatArray> = mockResult
    }

    @Test
    fun embed_whenNativeDimensionIs512_returnsUnchangedVector() =
        runTest {
            val original = FloatArray(512) { (it + 1).toFloat() }
            val fakeRuntime = FakeLlamaRuntime(Result.success(original))
            val runner = EmbeddingRunner(fakeRuntime)

            val result = runner.embed("sample input")

            assertEquals(512, result.size)
            for (i in 0 until 512) {
                assertEquals(original[i], result[i], 0.0001f)
            }
        }

    @Test
    fun embed_whenNativeDimensionIs768_poolsToFixed512dAndNormalizes() =
        runTest {
            val native768 = FloatArray(768) { 1.0f }
            val fakeRuntime = FakeLlamaRuntime(Result.success(native768))
            val runner = EmbeddingRunner(fakeRuntime)

            val result = runner.embed("sample text")

            assertEquals(512, result.size)
            var sumSq = 0f
            for (v in result) {
                sumSq += v * v
            }
            val norm = sqrt(sumSq)
            assertTrue("Expected unit L2 norm but got $norm", abs(norm - 1.0f) < 0.001f)

            val expectedVal = 1.0f / sqrt(512.0f)
            for (v in result) {
                assertTrue(abs(v - expectedVal) < 0.001f)
            }
        }

    @Test
    fun embed_whenEmptyInput_returnsFixed512dZeroVector() =
        runTest {
            val fakeRuntime = FakeLlamaRuntime(Result.success(FloatArray(0)))
            val runner = EmbeddingRunner(fakeRuntime)

            val result = runner.embed("")

            assertEquals(512, result.size)
            for (v in result) {
                assertEquals(0.0f, v, 0.0f)
            }
        }

    @Test
    fun embed_whenRuntimeFails_propagatesException() =
        runTest {
            val fakeRuntime =
                FakeLlamaRuntime(Result.failure(IllegalStateException("Native JNI failed")))
            val runner = EmbeddingRunner(fakeRuntime)

            val result = runCatching { runner.embed("test") }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }
}
