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

package com.locus.core.ai.toolloop

import com.locus.core.domain.agent.BulkCapExceededException
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.ProviderRole
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema as DomainToolSchema

/**
 * Routes tool use through the provider's native function calling mechanism when
 * [com.locus.core.domain.providers.ProviderCapabilities.supportsNativeTools] is true.
 */
@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
class NativeFunctionCallingBridge(
    private val adapter: ProviderAdapter,
    private val tools: List<ToolExecutor>,
    private val maxIterations: Int = 8,
) {
    private val toolsByName = tools.associateBy { it.schema.name }
    private val domainTools =
        tools.map {
            DomainToolSchema(
                name = it.schema.name,
                description = it.schema.description,
                parametersJsonSchema = it.schema.parametersJsonSchema,
            )
        }

    suspend fun run(userMessage: String): String {
        val messages =
            mutableListOf(
                ProviderMessage(role = ProviderRole.USER, content = userMessage),
            )

        repeat(maxIterations) {
            val assistantText = StringBuilder()
            val toolCallsByIndex = mutableMapOf<Int, AccumulatedToolCall>()

            adapter.streamChat(messages, domainTools).collect { event ->
                when (event) {
                    is StreamEvent.TokenDelta -> assistantText.append(event.text)
                    is StreamEvent.ToolCallDelta -> {
                        val tc = toolCallsByIndex.getOrPut(event.index) { AccumulatedToolCall(index = event.index) }
                        if (!event.id.isNullOrBlank()) tc.id = event.id
                        if (!event.name.isNullOrBlank()) tc.name = event.name
                        if (!event.argumentsDelta.isNullOrEmpty()) tc.arguments.append(event.argumentsDelta)
                    }
                    is StreamEvent.Error -> throw ToolLoopException("Provider stream error: ${event.message}")
                    is StreamEvent.Done -> {}
                    is StreamEvent.Usage -> {}
                }
            }

            if (toolCallsByIndex.isEmpty()) {
                return assistantText.toString()
            }

            messages +=
                ProviderMessage(
                    role = ProviderRole.ASSISTANT,
                    content = assistantText.toString(),
                )

            for (call in toolCallsByIndex.values) {
                val toolName = call.name.orEmpty()
                val tool = toolsByName[toolName]
                val result =
                    if (tool == null) {
                        """{"error":"unknown tool '$toolName'"}"""
                    } else {
                        try {
                            tool.execute(call.arguments.toString())
                        } catch (e: BulkCapExceededException) {
                            """{"error":"TOOL_ERROR: ${e.message}"}"""
                        } catch (e: Exception) {
                            """{"error":"${(e.message ?: "tool failed").replace("\"", "'")}"}"""
                        }
                    }
                messages +=
                    ProviderMessage(
                        role = ProviderRole.TOOL,
                        content = result,
                        name = toolName,
                        toolCallId = call.id,
                    )
            }
        }
        throw ToolLoopException("exceeded $maxIterations iterations without a final answer")
    }

    private data class AccumulatedToolCall(
        val index: Int,
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )
}
