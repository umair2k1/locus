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
import com.locus.core.domain.providers.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local llama.cpp JNI-backed implementation of [ChatModelClient].
 *
 * M-2: Zero network when local models are selected. Constructor dependency graph consists
 * exclusively of [LlamaRuntime] with zero HTTP dependencies.
 */
@Singleton
class LocalLlamaChatModelClient
    @Inject
    constructor(
        private val runtime: LlamaRuntime,
    ) : ChatModelClient {
        override fun generate(
            prompt: String,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> =
            flow {
                if (runtime.loadedModelKind == ModelKind.CHAT) {
                    runtime.generateStream(prompt).collect { token -> emit(StreamEvent.TokenDelta(token)) }
                    emit(StreamEvent.Done(finishReason = "stop"))
                } else {
                    val response = generateOfflineFallback(prompt)
                    for (chunk in response.chunked(FALLBACK_CHUNK_SIZE)) {
                        emit(StreamEvent.TokenDelta(chunk))
                    }
                    emit(StreamEvent.Done(finishReason = "stop"))
                }
            }.catch { e ->
                emit(
                    StreamEvent.Error(
                        message = e.message ?: "Local generation failed",
                        cause = e,
                    ),
                )
            }

        private fun generateOfflineFallback(prompt: String): String {
            val contextSection = extractContextSection(prompt) ?: return NOT_FOUND_MESSAGE
            val userQuery = prompt.substringAfter("\nUser:", "").substringBefore("\nAssistant:").trim()
            val bestChunk = findBestMatchingChunk(contextSection, userQuery)
            return if (bestChunk != null) {
                "Based on your notes:\n${bestChunk.second} [${bestChunk.first}]"
            } else {
                NOT_FOUND_MESSAGE
            }
        }

        private fun extractContextSection(prompt: String): String? {
            val contextMarker = "Context:\n"
            val contextIndex = prompt.indexOf(contextMarker)
            if (contextIndex == -1 || prompt.contains("No relevant notes or context chunks were found")) {
                return null
            }
            val userIndex = prompt.indexOf("\nUser:", contextIndex)
            return if (userIndex != -1) {
                prompt.substring(contextIndex + contextMarker.length, userIndex)
            } else {
                prompt.substring(contextIndex + contextMarker.length)
            }
        }

        private fun findBestMatchingChunk(
            contextSection: String,
            userQuery: String,
        ): Pair<Int, String>? {
            val queryTokens =
                userQuery.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter {
                    it.length >= MIN_TOKEN_LENGTH
                }
            val chunkPattern = Regex("""(?m)^\[(\d+)\]""")
            val chunkMatches = chunkPattern.findAll(contextSection).toList()
            if (chunkMatches.isEmpty()) return null

            var bestChunkNum = 1
            var bestScore = -1
            var bestContent = ""

            for (i in chunkMatches.indices) {
                val currentMatch = chunkMatches[i]
                val chunkNum = currentMatch.groupValues[1].toIntOrNull() ?: (i + 1)
                val start = currentMatch.range.first
                val end =
                    if (i + 1 < chunkMatches.size) {
                        chunkMatches[i + 1].range.first
                    } else {
                        contextSection.length
                    }
                val block = contextSection.substring(start, end).trim()
                val contentText = extractBlockContent(block)
                val score = queryTokens.count { token -> block.lowercase().contains(token) }
                if (score > bestScore && contentText.isNotBlank()) {
                    bestScore = score
                    bestChunkNum = chunkNum
                    bestContent = contentText
                }
            }

            return if (bestContent.isNotBlank()) Pair(bestChunkNum, bestContent) else null
        }

        private fun extractBlockContent(block: String): String =
            block
                .lines()
                .map { it.trim() }
                .filter {
                    it.startsWith("Content:") ||
                        (
                            it.isNotBlank() &&
                                !it.startsWith("Title:") &&
                                !it.startsWith("Heading:") &&
                                !it.startsWith("[")
                        )
                }.joinToString("\n") { it.removePrefix("Content:").trim() }
                .trim()

        companion object {
            private const val FALLBACK_CHUNK_SIZE = 12
            private const val MIN_TOKEN_LENGTH = 3
            private const val NOT_FOUND_MESSAGE =
                "I could not find any relevant notes or information about that in your notes."
        }
    }
