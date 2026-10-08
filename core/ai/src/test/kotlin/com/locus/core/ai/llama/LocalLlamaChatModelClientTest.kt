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

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.providers.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlamaChatModelClientTest {
    private class FakeLlamaRuntime(
        private val tokenFlow: Flow<String>,
    ) : LlamaRuntime() {
        override val loadedModelKind: ModelKind = ModelKind.CHAT

        override fun generateStream(
            prompt: String,
            samplingParams: SamplingParams,
        ): Flow<String> = tokenFlow
    }

    @Suppress("USELESS_IS_CHECK")
    @Test
    fun implementsChatModelClient() {
        val runtime = LlamaRuntime()
        val client = LocalLlamaChatModelClient(runtime)
        assertTrue(client is ChatModelClient)
    }

    @Test
    fun generate_emitsTokenDeltasAndDoneEvent() =
        runTest {
            val tokens = listOf("Hello", ", ", "offline", " world!")
            val runtime = FakeLlamaRuntime(flowOf(*tokens.toTypedArray()))
            val client = LocalLlamaChatModelClient(runtime)

            val events = client.generate("test prompt").toList()

            assertEquals(5, events.size)
            assertEquals(StreamEvent.TokenDelta("Hello"), events[0])
            assertEquals(StreamEvent.TokenDelta(", "), events[1])
            assertEquals(StreamEvent.TokenDelta("offline"), events[2])
            assertEquals(StreamEvent.TokenDelta(" world!"), events[3])
            assertEquals(StreamEvent.Done("stop"), events[4])
        }

    @Test
    fun generate_whenRuntimeThrows_catchesAndEmitsError() =
        runTest {
            val runtime =
                FakeLlamaRuntime(
                    flow {
                        emit("partial")
                        error("Llama JNI crashed")
                    },
                )
            val client = LocalLlamaChatModelClient(runtime)

            val events = client.generate("test prompt").toList()

            assertEquals(2, events.size)
            assertEquals(StreamEvent.TokenDelta("partial"), events[0])
            assertTrue(events[1] is StreamEvent.Error)
            assertEquals("Llama JNI crashed", (events[1] as StreamEvent.Error).message)
        }

    @Test
    fun generate_whenRuntimeNotLoaded_generatesOfflineFallbackFromContext() =
        runTest {
            val runtime = LlamaRuntime()
            val client = LocalLlamaChatModelClient(runtime)
            val prompt =
                """
                You are an assistant answering questions based on the user's notes.
                Context:
                [1] Title: bike refuel date
                Content: bike refueled - 8 sept 2026 - 1241.43 INR

                User: what was the cost
                Assistant:
                """.trimIndent()

            val events = client.generate(prompt).toList()

            assertTrue(events.isNotEmpty())
            val fullText =
                events.filterIsInstance<StreamEvent.TokenDelta>().joinToString("") { it.text }
            assertTrue("Must contain note content", fullText.contains("1241.43 INR"))
            assertTrue("Must contain citation [1]", fullText.contains("[1]"))
            assertTrue("Last event must be Done", events.last() is StreamEvent.Done)
        }

    @Test
    fun generate_whenRuntimeNotLoaded_citesMatchingChunkNumber() =
        runTest {
            val runtime = LlamaRuntime()
            val client = LocalLlamaChatModelClient(runtime)
            val prompt =
                """
                You are an assistant answering questions based on the user's notes.
                Context:
                [1] Title: Hotel Booking
                Content: Hotel reservation in Chicago for 3 nights.

                [2] Title: Fuel Receipt
                Content: Fuel cost was 45 dollars at Shell station.

                User: what was the cost of the fuel
                Assistant:
                """.trimIndent()

            val events = client.generate(prompt).toList()

            assertTrue(events.isNotEmpty())
            val fullText =
                events.filterIsInstance<StreamEvent.TokenDelta>().joinToString("") { it.text }
            assertTrue("Must contain fuel note content", fullText.contains("Fuel cost was 45 dollars"))
            assertTrue("Must cite chunk [2]", fullText.contains("[2]"))
            assertFalse("Must not cite chunk [1]", fullText.contains("[1]"))
        }

    @Test
    fun generate_whenRuntimeNotLoadedAndNoChunks_reportsNotFound() =
        runTest {
            val runtime = LlamaRuntime()
            val client = LocalLlamaChatModelClient(runtime)
            val prompt =
                """
                You are an assistant answering questions based on the user's notes.
                No relevant notes or context chunks were found for this user query.
                User: what is my passport number?
                Assistant:
                """.trimIndent()

            val events = client.generate(prompt).toList()

            val fullText =
                events.filterIsInstance<StreamEvent.TokenDelta>().joinToString("") { it.text }
            assertTrue("Must report not found", fullText.contains("could not find any relevant notes"))
            assertTrue("Last event must be Done", events.last() is StreamEvent.Done)
        }
}
