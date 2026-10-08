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

class OpenAiCompatibleAdapter(
    private val baseUrl: String,
    // SEC-3: never log
    private val apiKey: String = "",
    private val model: String = "gpt-4o",
    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            supportsNativeTools = true,
            contextLength = DEFAULT_CONTEXT_LENGTH,
            pricePerMillionInputTokens = null,
            pricePerMillionOutputTokens = null,
        ),
    private val client: OkHttpClient = OkHttpClient(),
    override val providerId: String = "openai",
) : ProviderAdapter,
    ChatModelClient {
    override fun streamChat(
        messages: List<ProviderMessage>,
        tools: List<ToolSchema>,
    ): Flow<StreamEvent> =
        flow {
            val request = buildRequest(messages, tools)
            try {
                client.newCall(request).execute().use { response ->
                    processResponse(response)
                }
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
        return if (url.endsWith("/chat/completions")) {
            url
        } else {
            "$url/chat/completions"
        }
    }

    private fun buildRequest(
        messages: List<ProviderMessage>,
        tools: List<ToolSchema>,
    ): Request {
        val root = JSONObject()
        root.put("model", model)
        root.put("stream", true)
        root.put("messages", buildMessagesJson(messages))

        if (tools.isNotEmpty() && capabilities.supportsNativeTools) {
            root.put("tools", buildToolsJson(tools))
        }

        val requestBuilder =
            Request
                .Builder()
                .url(buildEndpointUrl(baseUrl))
                .header("Accept", "text/event-stream")
                .header("Content-Type", "application/json")
                .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${apiKey.trim()}")
        }

        return requestBuilder.build()
    }

    private fun buildMessagesJson(messages: List<ProviderMessage>): JSONArray {
        val array = JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            obj.put(
                "role",
                when (msg.role) {
                    ProviderRole.SYSTEM -> "system"
                    ProviderRole.USER -> "user"
                    ProviderRole.ASSISTANT -> "assistant"
                    ProviderRole.TOOL -> "tool"
                },
            )
            obj.put("content", msg.content)
            if (!msg.name.isNullOrBlank()) {
                obj.put("name", msg.name)
            }
            if (!msg.toolCallId.isNullOrBlank()) {
                obj.put("tool_call_id", msg.toolCallId)
            }
            array.put(obj)
        }
        return array
    }

    private fun buildToolsJson(tools: List<ToolSchema>): JSONArray {
        val array = JSONArray()
        for (tool in tools) {
            val toolObj = JSONObject()
            toolObj.put("type", "function")
            val fnObj = JSONObject()
            fnObj.put("name", tool.name)
            fnObj.put("description", tool.description)
            val params =
                runCatching { JSONObject(tool.parametersJsonSchema) }.getOrElse { JSONObject() }
            fnObj.put("parameters", params)
            toolObj.put("function", fnObj)
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

        for (event in SseParsing.parseSource(source)) {
            val data = event.data.trim()
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
        if (data == "[DONE]") {
            emit(StreamEvent.Done(finishReason = currentFinishReason))
            return EventProcessingResult(isComplete = true, finishReason = currentFinishReason)
        }
        val json = runCatching { JSONObject(data) }.getOrNull()
        return if (json != null && json.has("error")) {
            val errorObj = json.optJSONObject("error")
            val message = errorObj?.optString("message") ?: json.optString("error", "Unknown error")
            emit(StreamEvent.Error(message))
            EventProcessingResult(isComplete = true)
        } else if (json != null) {
            val updatedReason = parseChoiceDelta(json, currentFinishReason)
            EventProcessingResult(isComplete = false, finishReason = updatedReason)
        } else {
            EventProcessingResult(isComplete = false)
        }
    }

    private data class EventProcessingResult(
        val isComplete: Boolean,
        val finishReason: String? = null,
    )

    private suspend fun FlowCollector<StreamEvent>.parseChoiceDelta(
        json: JSONObject,
        currentFinishReason: String?,
    ): String? {
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) return currentFinishReason

        val choice = choices.getJSONObject(0)
        val finishReason =
            if (choice.has("finish_reason") && !choice.isNull("finish_reason")) {
                choice.optString("finish_reason").takeIf { it != "null" && it.isNotBlank() }
            } else {
                null
            }

        val delta = choice.optJSONObject("delta")
        if (delta != null) {
            if (delta.has("content") && !delta.isNull("content")) {
                val content = delta.optString("content").takeIf { it != "null" && it.isNotEmpty() }
                if (content != null) {
                    emit(StreamEvent.TokenDelta(content))
                }
            }
            parseToolCallsDelta(delta)
        }

        return finishReason ?: currentFinishReason
    }

    private suspend fun FlowCollector<StreamEvent>.parseToolCallsDelta(delta: JSONObject) {
        val toolCalls = delta.optJSONArray("tool_calls") ?: return
        for (i in 0 until toolCalls.length()) {
            val tc = toolCalls.getJSONObject(i)
            val index = tc.optInt("index", i)
            val id =
                if (tc.has("id") && !tc.isNull("id")) {
                    tc.optString("id").takeIf { it != "null" }
                } else {
                    null
                }
            val fn = tc.optJSONObject("function")
            val name =
                if (fn != null && fn.has("name") && !fn.isNull("name")) {
                    fn.optString("name").takeIf { it != "null" }
                } else {
                    null
                }
            val args =
                if (fn != null && fn.has("arguments") && !fn.isNull("arguments")) {
                    fn.optString("arguments").takeIf { it != "null" }
                } else {
                    null
                }
            emit(
                StreamEvent.ToolCallDelta(
                    index = index,
                    id = id,
                    name = name,
                    argumentsDelta = args,
                ),
            )
        }
    }

    companion object {
        private const val DEFAULT_CONTEXT_LENGTH = 128_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
