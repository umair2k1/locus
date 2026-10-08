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

class GeminiAdapter(
    private val baseUrl: String = DEFAULT_BASE_URL,
    // SEC-3: never log
    private val apiKey: String = "",
    private val model: String = DEFAULT_MODEL,
    override val capabilities: ProviderCapabilities = lookupCapabilities(model),
    private val client: OkHttpClient = OkHttpClient(),
    override val providerId: String = "gemini",
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

    internal fun buildEndpointUrl(
        rawBaseUrl: String,
        modelName: String = model,
    ): String {
        var url = rawBaseUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        val endpoint =
            when {
                url.contains(":streamGenerateContent") -> url
                url.contains("/models/") -> "$url:streamGenerateContent"
                url.endsWith("/v1beta") || url.endsWith("/v1") ->
                    "$url/models/$modelName:streamGenerateContent"
                else -> "$url/v1beta/models/$modelName:streamGenerateContent"
            }
        return if (!endpoint.contains("alt=sse")) {
            if (endpoint.contains("?")) "$endpoint&alt=sse" else "$endpoint?alt=sse"
        } else {
            endpoint
        }
    }

    private fun buildRequest(
        messages: List<ProviderMessage>,
        tools: List<ToolSchema>,
    ): Request {
        val root = JSONObject()

        val systemMessages = messages.filter { it.role == ProviderRole.SYSTEM }
        if (systemMessages.isNotEmpty()) {
            val systemInstruction = JSONObject()
            val parts = JSONArray()
            val textPart = JSONObject()
            textPart.put("text", systemMessages.joinToString("\n\n") { it.content })
            parts.put(textPart)
            systemInstruction.put("parts", parts)
            root.put("systemInstruction", systemInstruction)
        }

        root.put("contents", buildContentsJson(messages.filter { it.role != ProviderRole.SYSTEM }))

        if (tools.isNotEmpty() && capabilities.supportsNativeTools) {
            root.put("tools", buildToolsJson(tools))
        }

        val requestBuilder =
            Request
                .Builder()
                .url(buildEndpointUrl(baseUrl, model))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (apiKey.isNotBlank()) {
            requestBuilder.header("x-goog-api-key", apiKey.trim())
        }

        return requestBuilder.build()
    }

    private fun buildContentsJson(messages: List<ProviderMessage>): JSONArray {
        val contents = JSONArray()
        for (msg in messages) {
            val contentObj = JSONObject()
            when (msg.role) {
                ProviderRole.USER -> {
                    contentObj.put("role", "user")
                    val parts = JSONArray()
                    val textPart = JSONObject().apply { put("text", msg.content) }
                    parts.put(textPart)
                    contentObj.put("parts", parts)
                }
                ProviderRole.ASSISTANT -> {
                    contentObj.put("role", "model")
                    val parts = JSONArray()
                    val textPart = JSONObject().apply { put("text", msg.content) }
                    parts.put(textPart)
                    contentObj.put("parts", parts)
                }
                ProviderRole.TOOL -> {
                    contentObj.put("role", "user")
                    val parts = JSONArray()
                    val fnResponsePart = JSONObject()
                    val fnResponse = JSONObject()
                    fnResponse.put("name", msg.name ?: "tool")
                    val responseBody = JSONObject().apply { put("content", msg.content) }
                    fnResponse.put("response", responseBody)
                    fnResponsePart.put("functionResponse", fnResponse)
                    parts.put(fnResponsePart)
                    contentObj.put("parts", parts)
                }
                ProviderRole.SYSTEM -> {
                    // Handled as systemInstruction
                }
            }
            contents.put(contentObj)
        }
        return contents
    }

    private fun buildToolsJson(tools: List<ToolSchema>): JSONArray {
        val toolsArray = JSONArray()
        val toolWrapper = JSONObject()
        val fnDeclarations = JSONArray()

        for (tool in tools) {
            val fnObj = JSONObject()
            fnObj.put("name", tool.name)
            fnObj.put("description", tool.description)
            val params = runCatching { JSONObject(tool.parametersJsonSchema) }.getOrElse { JSONObject() }
            fnObj.put("parameters", params)
            fnDeclarations.put(fnObj)
        }

        toolWrapper.put("functionDeclarations", fnDeclarations)
        toolsArray.put(toolWrapper)
        return toolsArray
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
            if (data.isNotEmpty()) {
                val result = processSseEvent(data, lastFinishReason)
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
        data: String,
        currentFinishReason: String?,
    ): EventProcessingResult {
        val json = runCatching { JSONObject(data) }.getOrNull()
        if (json != null && json.has("error")) {
            val errorObj = json.optJSONObject("error")
            val message = errorObj?.optString("message") ?: json.optString("error", "Unknown error")
            emit(StreamEvent.Error(message))
            return EventProcessingResult(isComplete = true)
        }

        val candidates = json?.optJSONArray("candidates")
        val updatedReason =
            if (candidates != null) {
                parseCandidates(candidates) ?: currentFinishReason
            } else {
                currentFinishReason
            }

        return EventProcessingResult(isComplete = false, finishReason = updatedReason)
    }

    private suspend fun FlowCollector<StreamEvent>.parseCandidates(candidates: JSONArray): String? {
        var finishReason: String? = null
        for (i in 0 until candidates.length()) {
            val candidate = candidates.getJSONObject(i)
            val reason = candidate.optString("finishReason").takeIf { it.isNotBlank() && it != "null" }
            if (reason != null) {
                finishReason = reason
            }

            val content = candidate.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null) {
                parseParts(parts)
            }
        }
        return finishReason
    }

    private suspend fun FlowCollector<StreamEvent>.parseParts(parts: JSONArray) {
        for (p in 0 until parts.length()) {
            val part = parts.getJSONObject(p)
            if (part.has("text")) {
                val text = part.optString("text")
                if (text.isNotEmpty()) {
                    emit(StreamEvent.TokenDelta(text))
                }
            }
            if (part.has("functionCall")) {
                val fnCall = part.getJSONObject("functionCall")
                val name = fnCall.optString("name").takeIf { it.isNotBlank() }
                val args = fnCall.optJSONObject("args")?.toString() ?: "{}"
                emit(
                    StreamEvent.ToolCallDelta(
                        index = p,
                        id = null,
                        name = name,
                        argumentsDelta = args,
                    ),
                )
            }
        }
    }

    private data class EventProcessingResult(
        val isComplete: Boolean,
        val finishReason: String? = null,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        const val DEFAULT_MODEL = "gemini-1.5-flash"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Hardcoded capabilities table for Google Gemini model families.
         *
         * Supported models and context lengths:
         * - Gemini 2.0 Flash: 1,048,576 tokens (Input: $0.10/Mtok, Output: $0.40/Mtok)
         * - Gemini 2.0 Flash Thinking: 1,048,576 tokens (Input: $0.10/Mtok, Output: $0.40/Mtok)
         * - Gemini 2.0 Pro: 2,097,152 tokens (Experimental pricing)
         * - Gemini 1.5 Pro: 2,097,152 tokens (Input: $1.25/Mtok, Output: $5.00/Mtok)
         * - Gemini 1.5 Flash: 1,048,576 tokens (Input: $0.075/Mtok, Output: $0.30/Mtok)
         * - Gemini 1.5 Flash-8B: 1,048,576 tokens (Input: $0.0375/Mtok, Output: $0.15/Mtok)
         * - Gemini 1.0 Pro: 32,768 tokens (Input: $0.50/Mtok, Output: $1.50/Mtok)
         *
         * Source: https://ai.google.dev/pricing and https://ai.google.dev/gemini-api/docs/models/gemini
         */
        fun lookupCapabilities(model: String): ProviderCapabilities {
            val normalized = model.lowercase().trim()
            return when {
                normalized.contains("1.5-pro") || normalized.contains("1-5-pro") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 2_097_152,
                        pricePerMillionInputTokens = 1.25,
                        pricePerMillionOutputTokens = 5.0,
                    )
                normalized.contains("1.5-flash-8b") || normalized.contains("1-5-flash-8b") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 1_048_576,
                        pricePerMillionInputTokens = 0.0375,
                        pricePerMillionOutputTokens = 0.15,
                    )
                normalized.contains("1.5-flash") || normalized.contains("1-5-flash") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 1_048_576,
                        pricePerMillionInputTokens = 0.075,
                        pricePerMillionOutputTokens = 0.30,
                    )
                normalized.contains("2.0-flash") || normalized.contains("2-0-flash") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 1_048_576,
                        pricePerMillionInputTokens = 0.10,
                        pricePerMillionOutputTokens = 0.40,
                    )
                normalized.contains("2.0-pro") || normalized.contains("2-0-pro") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 2_097_152,
                        pricePerMillionInputTokens = null,
                        pricePerMillionOutputTokens = null,
                    )
                normalized.contains("1.0-pro") || normalized.contains("gemini-pro") ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 32_768,
                        pricePerMillionInputTokens = 0.50,
                        pricePerMillionOutputTokens = 1.50,
                    )
                else ->
                    ProviderCapabilities(
                        supportsNativeTools = true,
                        contextLength = 1_048_576,
                        pricePerMillionInputTokens = null,
                        pricePerMillionOutputTokens = null,
                    )
            }
        }
    }
}
