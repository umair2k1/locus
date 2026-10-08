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
class AnthropicAdapterTest {
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
    fun streamChat_completesStreamedResponse_withAnthropicFraming() =
        runBlocking {
            val sseBody =
                buildString {
                    append("event: message_start\n")
                    append("data: {\"type\":\"message_start\",\"message\":")
                    append("{\"id\":\"msg_1\",\"role\":\"assistant\"}}\n\n")
                    append("event: content_block_start\n")
                    append("data: {\"type\":\"content_block_start\",\"index\":0,")
                    append("\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n")
                    append("event: content_block_delta\n")
                    append("data: {\"type\":\"content_block_delta\",\"index\":0,")
                    append("\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello \"}}\n\n")
                    append("event: content_block_delta\n")
                    append("data: {\"type\":\"content_block_delta\",\"index\":0,")
                    append("\"delta\":{\"type\":\"text_delta\",\"text\":\"world!\"}}\n\n")
                    append("event: content_block_stop\n")
                    append("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
                    append("event: message_delta\n")
                    append("data: {\"type\":\"message_delta\",")
                    append("\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n")
                    append("event: message_stop\n")
                    append("data: {\"type\":\"message_stop\"}\n\n")
                }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val adapter =
                AnthropicAdapter(
                    baseUrl = server.url("/").toString(),
                    apiKey = "sk-ant-test-key",
                    model = "claude-3-5-sonnet-latest",
                )

            val messages =
                listOf(
                    ProviderMessage(
                        role = ProviderRole.SYSTEM,
                        content = "You are an assistant",
                    ),
                    ProviderMessage(role = ProviderRole.USER, content = "Hi"),
                )

            val events = adapter.streamChat(messages).toList()

            assertEquals(3, events.size)
            assertEquals(StreamEvent.TokenDelta("Hello "), events[0])
            assertEquals(StreamEvent.TokenDelta("world!"), events[1])
            assertEquals(StreamEvent.Done(finishReason = "end_turn"), events[2])

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("text/event-stream", recorded.getHeader("Accept"))
            assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
            assertEquals("sk-ant-test-key", recorded.getHeader("x-api-key"))

            val body = JSONObject(recorded.body.readUtf8())
            assertEquals("claude-3-5-sonnet-latest", body.getString("model"))
            assertEquals(true, body.getBoolean("stream"))
            assertEquals(4096, body.getInt("max_tokens"))
            assertEquals("You are an assistant", body.getString("system"))

            val msgs = body.getJSONArray("messages")
            assertEquals(1, msgs.length())
            val userMsg = msgs.getJSONObject(0)
            assertEquals("user", userMsg.getString("role"))
            assertEquals("Hi", userMsg.getString("content"))
        }

    @Test
    fun streamChat_handlesToolCallDeltas() =
        runBlocking {
            val sseBody = buildToolCallSseBody()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val adapter =
                AnthropicAdapter(
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
                        listOf(ProviderMessage(ProviderRole.USER, "search")),
                        listOf(tool),
                    ).toList()

            assertEquals(4, events.size)
            assertEquals(
                StreamEvent.ToolCallDelta(
                    index = 0,
                    id = "toolu_01",
                    name = "search_notes",
                    argumentsDelta = null,
                ),
                events[0],
            )
            assertEquals(
                StreamEvent.ToolCallDelta(
                    index = 0,
                    id = null,
                    name = null,
                    argumentsDelta = "{\"query\":\"",
                ),
                events[1],
            )
            assertEquals(
                StreamEvent.ToolCallDelta(
                    index = 0,
                    id = null,
                    name = null,
                    argumentsDelta = "kotlin\"}",
                ),
                events[2],
            )
            assertEquals(StreamEvent.Done(finishReason = "tool_use"), events[3])

            val recorded = server.takeRequest()
            val body = JSONObject(recorded.body.readUtf8())
            val tools = body.getJSONArray("tools")
            assertEquals(1, tools.length())
            val toolObj = tools.getJSONObject(0)
            assertEquals("search_notes", toolObj.getString("name"))
            assertEquals("Search local notes", toolObj.getString("description"))
            assertTrue(toolObj.has("input_schema"))
        }

    @Test
    fun streamChat_handlesToolResultInMessages() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"),
            )

            val adapter = AnthropicAdapter(baseUrl = server.url("/").toString(), apiKey = "test-key")
            val messages =
                listOf(
                    ProviderMessage(role = ProviderRole.USER, content = "Search notes"),
                    ProviderMessage(role = ProviderRole.ASSISTANT, content = "Calling tool"),
                    ProviderMessage(
                        role = ProviderRole.TOOL,
                        content = "found 2 notes",
                        toolCallId = "toolu_01",
                    ),
                )

            adapter.streamChat(messages).toList()

            val recorded = server.takeRequest()
            val body = JSONObject(recorded.body.readUtf8())
            val msgs = body.getJSONArray("messages")
            assertEquals(3, msgs.length())

            val toolResultMsg = msgs.getJSONObject(2)
            assertEquals("user", toolResultMsg.getString("role"))
            val contentArray = toolResultMsg.getJSONArray("content")
            assertEquals(1, contentArray.length())
            val block = contentArray.getJSONObject(0)
            assertEquals("tool_result", block.getString("type"))
            assertEquals("toolu_01", block.getString("tool_use_id"))
            assertEquals("found 2 notes", block.getString("content"))
        }

    @Test
    fun streamChat_handlesHttpError() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody(
                        "{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid key\"}}",
                    ),
            )

            val adapter = AnthropicAdapter(baseUrl = server.url("/").toString(), apiKey = "bad-key")
            val events = adapter.streamChat(listOf(ProviderMessage(ProviderRole.USER, "hi"))).toList()

            assertEquals(1, events.size)
            val error = events[0] as StreamEvent.Error
            assertTrue(error.message.contains("HTTP 401"))
            assertTrue(error.message.contains("invalid key"))
        }

    @Test
    fun streamChat_handlesStreamErrorEvent() =
        runBlocking {
            val sseBody =
                buildString {
                    append("event: error\n")
                    append("data: {\"type\":\"error\",")
                    append("\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n")
                }

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(sseBody),
            )

            val adapter = AnthropicAdapter(baseUrl = server.url("/").toString(), apiKey = "test-key")
            val events = adapter.streamChat(listOf(ProviderMessage(ProviderRole.USER, "hi"))).toList()

            assertEquals(1, events.size)
            val error = events[0] as StreamEvent.Error
            assertEquals("Overloaded", error.message)
        }

    @Test
    fun streamChat_withoutApiKey_omitsXApiKeyHeader() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"),
            )

            val adapter = AnthropicAdapter(baseUrl = server.url("/").toString(), apiKey = "")
            adapter.streamChat(listOf(ProviderMessage(ProviderRole.USER, "hi"))).toList()

            val recorded = server.takeRequest()
            assertNull(recorded.getHeader("x-api-key"))
            assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        }

    @Test
    fun buildEndpointUrl_handlesVariousFormats() {
        val adapter = AnthropicAdapter()
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            adapter.buildEndpointUrl("https://api.anthropic.com"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            adapter.buildEndpointUrl("https://api.anthropic.com/"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            adapter.buildEndpointUrl("https://api.anthropic.com/v1"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            adapter.buildEndpointUrl("https://api.anthropic.com/v1/messages"),
        )
        assertEquals(
            "http://localhost:8080/v1/messages",
            adapter.buildEndpointUrl("http://localhost:8080"),
        )
        assertEquals(
            "http://localhost:8080/custom/messages",
            adapter.buildEndpointUrl("http://localhost:8080/custom/messages"),
        )
    }

    @Test
    fun lookupCapabilities_mapsKnownModelsAccurately() {
        val sonnet37 = AnthropicAdapter.lookupCapabilities("claude-3-7-sonnet-20250219")
        assertTrue(sonnet37.supportsNativeTools)
        assertEquals(200_000, sonnet37.contextLength)
        assertEquals(3.0, sonnet37.pricePerMillionInputTokens!!, 0.001)
        assertEquals(15.0, sonnet37.pricePerMillionOutputTokens!!, 0.001)

        val sonnet35 = AnthropicAdapter.lookupCapabilities("claude-3-5-sonnet-latest")
        assertTrue(sonnet35.supportsNativeTools)
        assertEquals(200_000, sonnet35.contextLength)
        assertEquals(3.0, sonnet35.pricePerMillionInputTokens!!, 0.001)
        assertEquals(15.0, sonnet35.pricePerMillionOutputTokens!!, 0.001)

        val haiku35 = AnthropicAdapter.lookupCapabilities("claude-3-5-haiku-20241022")
        assertTrue(haiku35.supportsNativeTools)
        assertEquals(200_000, haiku35.contextLength)
        assertEquals(0.8, haiku35.pricePerMillionInputTokens!!, 0.001)
        assertEquals(4.0, haiku35.pricePerMillionOutputTokens!!, 0.001)

        val opus = AnthropicAdapter.lookupCapabilities("claude-3-opus-latest")
        assertTrue(opus.supportsNativeTools)
        assertEquals(200_000, opus.contextLength)
        assertEquals(15.0, opus.pricePerMillionInputTokens!!, 0.001)
        assertEquals(75.0, opus.pricePerMillionOutputTokens!!, 0.001)

        val haiku3 = AnthropicAdapter.lookupCapabilities("claude-3-haiku-20240307")
        assertTrue(haiku3.supportsNativeTools)
        assertEquals(200_000, haiku3.contextLength)
        assertEquals(0.25, haiku3.pricePerMillionInputTokens!!, 0.001)
        assertEquals(1.25, haiku3.pricePerMillionOutputTokens!!, 0.001)

        val unknown = AnthropicAdapter.lookupCapabilities("custom-claude-model")
        assertTrue(unknown.supportsNativeTools)
        assertEquals(200_000, unknown.contextLength)
        assertNull(unknown.pricePerMillionInputTokens)
        assertNull(unknown.pricePerMillionOutputTokens)
    }

    private fun buildToolCallSseBody(): String =
        buildString {
            append("event: message_start\n")
            append("data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_2\"}}\n\n")
            append("event: content_block_start\n")
            append("data: {\"type\":\"content_block_start\",\"index\":0,")
            append(
                "\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"search_notes\"}}\n\n",
            )
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"index\":0,")
            append(
                "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"\"}}\n\n",
            )
            append("event: content_block_delta\n")
            append("data: {\"type\":\"content_block_delta\",\"index\":0,")
            append("\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"kotlin\\\"}\"}}\n\n")
            append("event: message_delta\n")
            append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}\n\n")
            append("event: message_stop\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
}
