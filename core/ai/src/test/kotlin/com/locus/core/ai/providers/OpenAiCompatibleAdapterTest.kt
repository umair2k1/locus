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

package com.locus.core.ai.providers

import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.ProviderRole
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenAiCompatibleAdapterTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun streamChat_completesStreamedResponse_withNonOpenAiBaseUrl() =
        runBlocking {
            val sseBody =
                """
                data: {"id":"1","object":"chat.completion.chunk","choices":[{"delta":{"content":"Hello"}}]}

                data: {"id":"2","object":"chat.completion.chunk","choices":[{"delta":{"content":" world!"}}]}

                data: [DONE]

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            // Non-OpenAI-branded base URL: http://localhost:PORT/custom/v1
            val customBaseUrl = server.url("/custom/v1").toString()
            val adapter =
                OpenAiCompatibleAdapter(
                    baseUrl = customBaseUrl,
                    apiKey = "sk-custom-12345",
                    model = "custom-model",
                )

            val messages =
                listOf(
                    ProviderMessage(role = ProviderRole.USER, content = "Hi"),
                )

            val events = adapter.streamChat(messages).toList()

            assertEquals(3, events.size)
            assertEquals(StreamEvent.TokenDelta("Hello"), events[0])
            assertEquals(StreamEvent.TokenDelta(" world!"), events[1])
            assertTrue(events[2] is StreamEvent.Done)

            val recordedRequest = server.takeRequest()
            assertEquals("/custom/v1/chat/completions", recordedRequest.path)
            assertEquals("Bearer sk-custom-12345", recordedRequest.getHeader("Authorization"))
            assertEquals("text/event-stream", recordedRequest.getHeader("Accept"))

            val recordedBody = recordedRequest.body.readUtf8()
            val requestJson = JSONObject(recordedBody)
            assertEquals("custom-model", requestJson.getString("model"))
            assertTrue(requestJson.getBoolean("stream"))
            val msgsJson = requestJson.getJSONArray("messages")
            assertEquals(1, msgsJson.length())
            assertEquals("user", msgsJson.getJSONObject(0).getString("role"))
            assertEquals("Hi", msgsJson.getJSONObject(0).getString("content"))
        }

    @Test
    fun streamChat_handlesToolCallDeltas() =
        runBlocking {
            val sseBody =
                """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","function":{"name":"search_notes","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"query\":\"recipes\"}"}}]}}]}

                data: {"choices":[{"finish_reason":"tool_calls","delta":{}}]}

                data: [DONE]

                """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val adapter =
                OpenAiCompatibleAdapter(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "key",
                )

            val tools =
                listOf(
                    ToolSchema(
                        name = "search_notes",
                        description = "Search user notes",
                        parametersJsonSchema =
                            "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}}}",
                    ),
                )

            val events =
                adapter
                    .streamChat(
                        messages =
                            listOf(
                                ProviderMessage(
                                    role = ProviderRole.USER,
                                    content = "find recipes",
                                ),
                            ),
                        tools = tools,
                    ).toList()

            assertEquals(3, events.size)
            val firstDelta = events[0] as StreamEvent.ToolCallDelta
            assertEquals(0, firstDelta.index)
            assertEquals("call_abc", firstDelta.id)
            assertEquals("search_notes", firstDelta.name)
            assertEquals("", firstDelta.argumentsDelta)

            val secondDelta = events[1] as StreamEvent.ToolCallDelta
            assertEquals(0, secondDelta.index)
            assertNull(secondDelta.id)
            assertNull(secondDelta.name)
            assertEquals("{\"query\":\"recipes\"}", secondDelta.argumentsDelta)

            val doneEvent = events[2] as StreamEvent.Done
            assertEquals("tool_calls", doneEvent.finishReason)
        }

    @Test
    fun streamChat_handlesHttpError() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"error":{"message":"Invalid API key provided"}}"""),
            )

            val adapter =
                OpenAiCompatibleAdapter(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "invalid-key",
                )

            val events =
                adapter
                    .streamChat(
                        messages =
                            listOf(
                                ProviderMessage(
                                    role = ProviderRole.USER,
                                    content = "Hello",
                                ),
                            ),
                    ).toList()

            assertEquals(1, events.size)
            val errorEvent = events[0] as StreamEvent.Error
            assertTrue(errorEvent.message.contains("401"))
            assertTrue(errorEvent.message.contains("Invalid API key provided"))
        }

    @Test
    fun streamChat_withoutApiKey_omitsAuthorizationHeader() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: [DONE]\n\n"),
            )

            val adapter =
                OpenAiCompatibleAdapter(
                    baseUrl = server.url("/ollama").toString(),
                    apiKey = "",
                )

            adapter
                .streamChat(
                    messages =
                        listOf(
                            ProviderMessage(role = ProviderRole.USER, content = "Hello"),
                        ),
                ).toList()

            val recordedRequest = server.takeRequest()
            assertNull(recordedRequest.getHeader("Authorization"))
        }

    @Test
    fun buildEndpointUrl_handlesVariousFormats() {
        val adapter = OpenAiCompatibleAdapter(baseUrl = "http://localhost:11434")

        assertEquals(
            "http://localhost:11434/chat/completions",
            adapter.buildEndpointUrl("http://localhost:11434"),
        )
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            adapter.buildEndpointUrl("http://localhost:11434/v1/"),
        )
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            adapter.buildEndpointUrl("http://localhost:11434/v1/chat/completions"),
        )
        assertEquals(
            "https://api.together.xyz/v1/chat/completions",
            adapter.buildEndpointUrl("api.together.xyz/v1"),
        )
    }
}
