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
class GeminiAdapterTest {
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
    fun streamChat_completesStreamedResponse() =
        runBlocking {
            val sseBody =
                buildString {
                    append("data: {\"candidates\":[{\"content\":")
                    append("{\"parts\":[{\"text\":\"Hello \"}],\"role\":\"model\"}}]}\n\n")
                    append("data: {\"candidates\":[{\"content\":")
                    append("{\"parts\":[{\"text\":\"world!\"}],\"role\":\"model\"},")
                    append("\"finishReason\":\"STOP\"}]}\n\n")
                }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val adapter =
                GeminiAdapter(
                    baseUrl = server.url("/").toString(),
                    apiKey = "goog-test-key",
                    model = "gemini-1.5-flash",
                )

            val messages =
                listOf(
                    ProviderMessage(role = ProviderRole.SYSTEM, content = "Be concise"),
                    ProviderMessage(role = ProviderRole.USER, content = "Hello"),
                )

            val events = adapter.streamChat(messages).toList()

            assertEquals(3, events.size)
            assertEquals(StreamEvent.TokenDelta("Hello "), events[0])
            assertEquals(StreamEvent.TokenDelta("world!"), events[1])
            assertEquals(StreamEvent.Done(finishReason = "STOP"), events[2])

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("text/event-stream", recorded.getHeader("Accept"))
            assertEquals("goog-test-key", recorded.getHeader("x-goog-api-key"))
            assertTrue(recorded.path!!.contains(":streamGenerateContent?alt=sse"))

            val body = JSONObject(recorded.body.readUtf8())
            assertTrue(body.has("systemInstruction"))
            val sysParts = body.getJSONObject("systemInstruction").getJSONArray("parts")
            assertEquals("Be concise", sysParts.getJSONObject(0).getString("text"))

            val contents = body.getJSONArray("contents")
            assertEquals(1, contents.length())
            val userContent = contents.getJSONObject(0)
            assertEquals("user", userContent.getString("role"))
            val userParts = userContent.getJSONArray("parts")
            assertEquals("Hello", userParts.getJSONObject(0).getString("text"))
        }

    @Test
    fun streamChat_handlesToolCallsAndDeclarations() =
        runBlocking {
            val sseBody =
                buildString {
                    append("data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":")
                    append("{\"name\":\"search_notes\",\"args\":{\"query\":\"kotlin\"}}}],")
                    append("\"role\":\"model\"}}]}\n\n")
                    append("data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n")
                }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val adapter =
                GeminiAdapter(
                    baseUrl = server.url("/").toString(),
                    apiKey = "test-key",
                )

            val tool =
                ToolSchema(
                    name = "search_notes",
                    description = "Search local notes",
                    parametersJsonSchema =
                        """{"type":"object","properties":{"query":{"type":"string"}}}""",
                )

            val events =
                adapter
                    .streamChat(
                        listOf(ProviderMessage(ProviderRole.USER, "search notes")),
                        listOf(tool),
                    ).toList()

            assertEquals(2, events.size)
            val toolCall = events[0] as StreamEvent.ToolCallDelta
            assertEquals(0, toolCall.index)
            assertNull(toolCall.id)
            assertEquals("search_notes", toolCall.name)
            assertTrue(toolCall.argumentsDelta!!.contains("kotlin"))
            assertEquals(StreamEvent.Done(finishReason = "STOP"), events[1])

            val recorded = server.takeRequest()
            val body = JSONObject(recorded.body.readUtf8())
            assertTrue(body.has("tools"))
            val tools = body.getJSONArray("tools")
            val toolWrapper = tools.getJSONObject(0)
            val fnDeclarations = toolWrapper.getJSONArray("functionDeclarations")
            assertEquals(1, fnDeclarations.length())
            val fn = fnDeclarations.getJSONObject(0)
            assertEquals("search_notes", fn.getString("name"))
            assertEquals("Search local notes", fn.getString("description"))
            assertTrue(fn.has("parameters"))
        }

    @Test
    fun streamChat_handlesToolResultInMessages() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n"),
            )

            val adapter = GeminiAdapter(baseUrl = server.url("/").toString(), apiKey = "test-key")
            val messages =
                listOf(
                    ProviderMessage(role = ProviderRole.USER, content = "Search notes"),
                    ProviderMessage(role = ProviderRole.ASSISTANT, content = "Calling tool"),
                    ProviderMessage(
                        role = ProviderRole.TOOL,
                        name = "search_notes",
                        content = "found notes",
                    ),
                )

            adapter.streamChat(messages).toList()

            val recorded = server.takeRequest()
            val body = JSONObject(recorded.body.readUtf8())
            val contents = body.getJSONArray("contents")
            assertEquals(3, contents.length())

            val toolResultContent = contents.getJSONObject(2)
            assertEquals("user", toolResultContent.getString("role"))
            val parts = toolResultContent.getJSONArray("parts")
            assertEquals(1, parts.length())
            val fnResponsePart = parts.getJSONObject(0)
            assertTrue(fnResponsePart.has("functionResponse"))
            val fnResponse = fnResponsePart.getJSONObject("functionResponse")
            assertEquals("search_notes", fnResponse.getString("name"))
            assertEquals("found notes", fnResponse.getJSONObject("response").getString("content"))
        }

    @Test
    fun streamChat_handlesHttpError() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody(
                        "{\"error\":{\"code\":400,\"message\":\"Invalid key\",\"status\":\"INVALID_ARGUMENT\"}}",
                    ),
            )

            val adapter = GeminiAdapter(baseUrl = server.url("/").toString(), apiKey = "bad-key")
            val events = adapter.streamChat(listOf(ProviderMessage(ProviderRole.USER, "hi"))).toList()

            assertEquals(1, events.size)
            val error = events[0] as StreamEvent.Error
            assertTrue(error.message.contains("HTTP 400"))
            assertTrue(error.message.contains("Invalid key"))
        }

    @Test
    fun streamChat_handlesStreamErrorJson() =
        runBlocking {
            val sseBody =
                buildString {
                    append("data: {\"error\":{\"code\":403,\"message\":\"API key expired\"}}\n\n")
                }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val adapter = GeminiAdapter(baseUrl = server.url("/").toString(), apiKey = "test-key")
            val events = adapter.streamChat(listOf(ProviderMessage(ProviderRole.USER, "hi"))).toList()

            assertEquals(1, events.size)
            val error = events[0] as StreamEvent.Error
            assertEquals("API key expired", error.message)
        }

    @Test
    fun streamChat_withoutApiKey_omitsGoogleApiKeyHeader() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n"),
            )

            val adapter = GeminiAdapter(baseUrl = server.url("/").toString(), apiKey = "")
            adapter.streamChat(listOf(ProviderMessage(ProviderRole.USER, "hi"))).toList()

            val recorded = server.takeRequest()
            assertNull(recorded.getHeader("x-goog-api-key"))
        }

    @Test
    fun buildEndpointUrl_handlesVariousFormats() {
        val adapter = GeminiAdapter(model = "gemini-1.5-flash")
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse",
            adapter.buildEndpointUrl("https://generativelanguage.googleapis.com"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse",
            adapter.buildEndpointUrl("https://generativelanguage.googleapis.com/"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse",
            adapter.buildEndpointUrl("https://generativelanguage.googleapis.com/v1beta"),
        )
        assertEquals(
            "http://localhost:8080/v1beta/models/gemini-1.5-flash:streamGenerateContent?alt=sse",
            adapter.buildEndpointUrl("http://localhost:8080"),
        )
        assertEquals(
            "http://localhost:8080/v1beta/models/gemini-pro:streamGenerateContent?alt=sse",
            adapter.buildEndpointUrl(
                "http://localhost:8080/v1beta/models/gemini-pro:streamGenerateContent",
            ),
        )
        assertEquals(
            "http://localhost:8080/v1beta/models/gemini-pro:streamGenerateContent?alt=sse",
            adapter.buildEndpointUrl(
                "http://localhost:8080/v1beta/models/gemini-pro:streamGenerateContent?alt=sse",
            ),
        )
    }

    @Test
    fun lookupCapabilities_mapsKnownModelsAccurately() {
        val pro15 = GeminiAdapter.lookupCapabilities("gemini-1.5-pro-latest")
        assertTrue(pro15.supportsNativeTools)
        assertEquals(2_097_152, pro15.contextLength)
        assertEquals(1.25, pro15.pricePerMillionInputTokens!!, 0.001)
        assertEquals(5.0, pro15.pricePerMillionOutputTokens!!, 0.001)

        val flash15 = GeminiAdapter.lookupCapabilities("gemini-1.5-flash")
        assertTrue(flash15.supportsNativeTools)
        assertEquals(1_048_576, flash15.contextLength)
        assertEquals(0.075, flash15.pricePerMillionInputTokens!!, 0.001)
        assertEquals(0.30, flash15.pricePerMillionOutputTokens!!, 0.001)

        val flash8b = GeminiAdapter.lookupCapabilities("gemini-1.5-flash-8b")
        assertTrue(flash8b.supportsNativeTools)
        assertEquals(1_048_576, flash8b.contextLength)
        assertEquals(0.0375, flash8b.pricePerMillionInputTokens!!, 0.001)
        assertEquals(0.15, flash8b.pricePerMillionOutputTokens!!, 0.001)

        val flash20 = GeminiAdapter.lookupCapabilities("gemini-2.0-flash-exp")
        assertTrue(flash20.supportsNativeTools)
        assertEquals(1_048_576, flash20.contextLength)
        assertEquals(0.10, flash20.pricePerMillionInputTokens!!, 0.001)
        assertEquals(0.40, flash20.pricePerMillionOutputTokens!!, 0.001)

        val pro20 = GeminiAdapter.lookupCapabilities("gemini-2.0-pro-exp")
        assertTrue(pro20.supportsNativeTools)
        assertEquals(2_097_152, pro20.contextLength)
        assertNull(pro20.pricePerMillionInputTokens)
        assertNull(pro20.pricePerMillionOutputTokens)

        val pro10 = GeminiAdapter.lookupCapabilities("gemini-1.0-pro")
        assertTrue(pro10.supportsNativeTools)
        assertEquals(32_768, pro10.contextLength)
        assertEquals(0.50, pro10.pricePerMillionInputTokens!!, 0.001)
        assertEquals(1.50, pro10.pricePerMillionOutputTokens!!, 0.001)

        val unknown = GeminiAdapter.lookupCapabilities("custom-gemini-model")
        assertTrue(unknown.supportsNativeTools)
        assertEquals(1_048_576, unknown.contextLength)
        assertNull(unknown.pricePerMillionInputTokens)
        assertNull(unknown.pricePerMillionOutputTokens)
    }
}
