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

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.ProviderRole
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

class AnthropicAdapter(
    private val baseUrl: String = DEFAULT_BASE_URL,
    // SEC-3: never log
    private val apiKey: String = "",
    private val model: String = DEFAULT_MODEL,
    override val capabilities: ProviderCapabilities = lookupCapabilities(model),
    private val client: OkHttpClient = OkHttpClient(),
    override val providerId: String = "anthropic",
) : ProviderAdapter,
    ChatModelClient {
    override fun streamChat(
        messages: List<ProviderMessage>,
        tools: List<ToolSchema>,
    ): Flow<StreamEvent> =
        flow {
            val request = buildRequest(messages, tools)
            try {
                client.newCall(request).execute().use { response -> processResponse(response) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                emit(StreamEvent.Error(e.message ?: "Network error", e))
            } catch (e: JSONException) {
                emit(StreamEvent.Error(e.message ?: "Invalid JSON", e))
            }
        }.flowOn(Dispatchers.IO)

    internal fun buildEndpointUrl(rawBaseUrl: String): String {
        var url = rawBaseUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return when {
            url.endsWith("/v1/messages") -> url
            url.endsWith("/messages") -> url
            url.endsWith("/v1") -> "$url/messages"
            else -> "$url/v1/messages"
        }
    }

    private fun buildRequest(
        messages: List<ProviderMessage>,
        tools: List<ToolSchema>,
    ): Request {
        val root = JSONObject()
        root.put("model", model)
        root.put("max_tokens", DEFAULT_MAX_TOKENS)
        root.put("stream", true)

        val systemMessages = messages.filter { it.role == ProviderRole.SYSTEM }
        if (systemMessages.isNotEmpty()) {
            root.put("system", systemMessages.joinToString("\n\n") { it.content })
        }

        root.put("messages", buildMessagesJson(messages.filter { it.role != ProviderRole.SYSTEM }))

        if (tools.isNotEmpty() && capabilities.supportsNativeTools) {
            root.put("tools", buildToolsJson(tools))
        }

        val requestBuilder =
            Request
                .Builder()
                .url(buildEndpointUrl(baseUrl))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .header("anthropic-version", DEFAULT_ANTHROPIC_VERSION)
                .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (apiKey.isNotBlank()) {
            requestBuilder.header("x-api-key", apiKey.trim())
        }

        return requestBuilder.build()
    }

    private fun buildMessagesJson(messages: List<ProviderMessage>): JSONArray {
        val array = JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            when (msg.role) {
                ProviderRole.USER -> {
                    obj.put("role", "user")
                    obj.put("content", msg.content)
                }
                ProviderRole.ASSISTANT -> {
                    obj.put("role", "assistant")
                    obj.put("content", msg.content)
                }
                ProviderRole.TOOL -> {
                    obj.put("role", "user")
                    val block = JSONObject()
                    block.put("type", "tool_result")
                    block.put("tool_use_id", msg.toolCallId.orEmpty())
                    block.put("content", msg.content)
                    val contentArray = JSONArray().apply { put(block) }
                    obj.put("content", contentArray)
                }
                ProviderRole.SYSTEM -> {
                    // Handled as top-level parameter
                }
            }
            array.put(obj)
        }
        return array
    }

    private fun buildToolsJson(tools: List<ToolSchema>): JSONArray {
        val array = JSONArray()
        for (tool in tools) {
            val toolObj = JSONObject()
            toolObj.put("name", tool.name)
            toolObj.put("description", tool.description)
            val inputSchema =
                runCatching { JSONObject(tool.parametersJsonSchema) }.getOrElse { JSONObject() }
            toolObj.put("input_schema", inputSchema)
            array.put(toolObj)
        }
        return array
    }

    private suspend fun FlowCollector<StreamEvent>.processResponse(response: Response) {
        if (!response.isSuccessful) {
            val errorBody = response.body?.string().orEmpty()
            emit(StreamEvent.Error("HTTP ${response.code}: $errorBody"))
        } else {
            val body = response.body
            if (body == null) {
                emit(StreamEvent.Error("Empty response body"))
            } else {
                consumeStream(body.source())
            }
        }
    }

    private suspend fun FlowCollector<StreamEvent>.consumeStream(source: BufferedSource) {
        var lastFinishReason: String? = null
        var doneEmitted = false

        for (sse in SseParsing.parseSource(source)) {
            val data = sse.data.trim()
            if (data.isNotEmpty() && sse.event != "ping") {
                val result = processSseEvent(sse.event, data, lastFinishReason)
                lastFinishReason = result.finishReason ?: lastFinishReason
                if (result.isComplete) {
                    doneEmitted = true
                    break
                }
            }
        }

        if (!doneEmitted) {
            emit(StreamEvent.Done(finishReason = lastFinishReason))
        }
    }

    private suspend fun FlowCollector<StreamEvent>.processSseEvent(
        rawEvent: String?,
        data: String,
        currentFinishReason: String?,
    ): EventProcessingResult {
        val json = runCatching { JSONObject(data) }.getOrNull()
        val eventType = rawEvent ?: json?.optString("type")

        if (eventType == "error" || (json != null && json.has("error"))) {
            val errorObj = json?.optJSONObject("error")
            val message =
                errorObj?.optString("message")
                    ?: json?.optString("error", "Unknown error") ?: "Unknown error"
            emit(StreamEvent.Error(message))
            return EventProcessingResult(isComplete = true)
        }

        return when (eventType) {
            "content_block_start" -> {
                handleContentBlockStart(json)
                EventProcessingResult(isComplete = false, finishReason = currentFinishReason)
            }
            "content_block_delta" -> {
                handleContentBlockDelta(json)
                EventProcessingResult(isComplete = false, finishReason = currentFinishReason)
            }
            "message_delta" -> {
                val updatedReason = handleMessageDelta(json) ?: currentFinishReason
                EventProcessingResult(isComplete = false, finishReason = updatedReason)
            }
            "message_stop" -> {
                emit(StreamEvent.Done(finishReason = currentFinishReason))
                EventProcessingResult(isComplete = true, finishReason = currentFinishReason)
            }
            else -> EventProcessingResult(isComplete = false, finishReason = currentFinishReason)
        }
    }

    private suspend fun FlowCollector<StreamEvent>.handleContentBlockStart(json: JSONObject?) {
        val block = json?.optJSONObject("content_block") ?: return
        if (block.optString("type") == "tool_use") {
            emit(
                StreamEvent.ToolCallDelta(
                    index = json.optInt("index", 0),
                    id = block.optString("id").takeIf { it.isNotBlank() },
                    name = block.optString("name").takeIf { it.isNotBlank() },
                    argumentsDelta = null,
                ),
            )
        }
    }

    private suspend fun FlowCollector<StreamEvent>.handleContentBlockDelta(json: JSONObject?) {
        val delta = json?.optJSONObject("delta") ?: return
        when (delta.optString("type")) {
            "text_delta" -> {
                val text = delta.optString("text")
                if (text.isNotEmpty()) {
                    emit(StreamEvent.TokenDelta(text))
                }
            }
            "input_json_delta" -> {
                val partialJson = delta.optString("partial_json")
                emit(
                    StreamEvent.ToolCallDelta(
                        index = json.optInt("index", 0),
                        id = null,
                        name = null,
                        argumentsDelta = partialJson,
                    ),
                )
            }
        }
    }

    private fun handleMessageDelta(json: JSONObject?): String? {
        val delta = json?.optJSONObject("delta")
        return delta?.optString("stop_reason")?.takeIf { it != "null" && it.isNotBlank() }
    }

    private data class EventProcessingResult(
        val isComplete: Boolean,
        val finishReason: String? = null,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.anthropic.com"
        const val DEFAULT_MODEL = "claude-3-5-sonnet-latest"
        const val DEFAULT_ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 4096
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Hardcoded capabilities table for Anthropic Claude model families.
         *
         * Supported models and context lengths:
         * - Claude 3.7 Sonnet: 200,000 tokens (Input: $3.00/Mtok, Output: $15.00/Mtok)
         * - Claude 3.5 Sonnet: 200,000 tokens (Input: $3.00/Mtok, Output: $15.00/Mtok)
         * - Claude 3.5 Haiku: 200,000 tokens (Input: $0.80/Mtok, Output: $4.00/Mtok)
         * - Claude 3 Opus: 200,000 tokens (Input: $15.00/Mtok, Output: $75.00/Mtok)
         * - Claude 3 Sonnet: 200,000 tokens (Input: $3.00/Mtok, Output: $15.00/Mtok)
         * - Claude 3 Haiku: 200,000 tokens (Input: $0.25/Mtok, Output: $1.25/Mtok)
         *
         * Source: https://docs.anthropic.com/en/docs/about-claude/models
         */
        fun lookupCapabilities(model: String): ProviderCapabilities {
            val normalized = model.lowercase().trim()
            return when {
                normalized.contains("3-7-sonnet") || normalized.contains("3.7-sonnet") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 200_000,
                        pricePerMillionInputTokens = 3.0,
                        pricePerMillionOutputTokens = 15.0,
                    )
                normalized.contains("3-5-sonnet") || normalized.contains("3.5-sonnet") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 200_000,
                        pricePerMillionInputTokens = 3.0,
                        pricePerMillionOutputTokens = 15.0,
                    )
                normalized.contains("3-5-haiku") || normalized.contains("3.5-haiku") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 200_000,
                        pricePerMillionInputTokens = 0.8,
                        pricePerMillionOutputTokens = 4.0,
                    )
                normalized.contains("3-opus") || normalized.contains("3.0-opus") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 200_000,
                        pricePerMillionInputTokens = 15.0,
                        pricePerMillionOutputTokens = 75.0,
                    )
                normalized.contains("3-haiku") || normalized.contains("3.0-haiku") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 200_000,
                        pricePerMillionInputTokens = 0.25,
                        pricePerMillionOutputTokens = 1.25,
                    )
                normalized.contains("3-sonnet") || normalized.contains("3.0-sonnet") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 200_000,
                        pricePerMillionInputTokens = 3.0,
                        pricePerMillionOutputTokens = 15.0,
                    )
                else ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 200_000,
                        pricePerMillionInputTokens = null,
                        pricePerMillionOutputTokens = null,
                    )
            }
        }
    }
}
