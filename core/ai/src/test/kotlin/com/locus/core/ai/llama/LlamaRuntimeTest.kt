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

package com.locus.core.ai.llama

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaRuntimeTest {
    @Test
    fun embedWithoutLoadedModelReturnsFailure() =
        runTest {
            val runtime = LlamaRuntime()
            val result = runtime.embed("hello")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun loadModelWithNonExistentPathReturnsFailure() =
        runTest {
            val runtime = LlamaRuntime()
            val result = runtime.loadModel("/non/existent/model.gguf")
            assertTrue(result.isFailure)
        }

    @Test
    fun unloadWhenNotLoadedIsIdempotentAndSafe() =
        runTest {
            val runtime = LlamaRuntime()
            runtime.unload()
            val result = runtime.embed("test")
            assertTrue(result.isFailure)
        }

    @Test
    fun generateStreamWithoutLoadedModelThrowsIllegalStateException() =
        runTest {
            val runtime = LlamaRuntime()
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    runtime.generateStream("hello").toList()
                }
            }
        }

    @Test
    fun modelKindEnumAndInitialState() {
        val runtime = LlamaRuntime()
        assertNull(runtime.loadedModelKind)
        assertEquals(2, ModelKind.entries.size)
        assertTrue(ModelKind.entries.contains(ModelKind.CHAT))
        assertTrue(ModelKind.entries.contains(ModelKind.EMBEDDING))
    }

    @Test
    fun samplingParamsDefaults() {
        val params = SamplingParams()
        assertEquals(0.7, params.temperature, 1e-6)
        assertEquals(0.9, params.topP, 1e-6)
        assertEquals(1024, params.maxTokens)
    }

    @Test
    fun subclassRuntimeAllowsMockingGenerateStream() =
        runTest {
            val fakeRuntime =
                object : LlamaRuntime() {
                    override fun generateStream(
                        prompt: String,
                        samplingParams: SamplingParams,
                    ): Flow<String> = flowOf("Hello", " ", "world")
                }
            val tokens = fakeRuntime.generateStream("test").toList()
            assertEquals(listOf("Hello", " ", "world"), tokens)
        }
}
