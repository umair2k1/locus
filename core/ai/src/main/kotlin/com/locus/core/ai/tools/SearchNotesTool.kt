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

package com.locus.core.ai.tools

import com.locus.core.ai.toolloop.ToolExecutor
import com.locus.core.ai.toolloop.ToolSchema
import com.locus.core.domain.search.HybridSearchUseCase
import com.locus.core.domain.search.SearchScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class SearchNotesArgs(
    val query: String = "",
    val folder: String? = null,
    @SerialName("folderPath")
    val folderPathCamel: String? = null,
    @SerialName("folder_path")
    val folderPathSnake: String? = null,
    val limit: Int? = null,
)

@Serializable
data class SearchResultDto(
    val noteId: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val headingPath: List<String> = emptyList(),
)

/**
 * Read tool: search_notes (C-3).
 * Wraps [HybridSearchUseCase], JSON-encoding the results.
 */
@Singleton
class SearchNotesTool
    @Inject
    constructor(
        private val hybridSearch: HybridSearchUseCase,
        private val json: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
    ) : ToolExecutor {
        override val schema: ToolSchema =
            ToolSchema(
                name = "search_notes",
                description = "Search notes using hybrid (keyword + semantic) search across note titles and content.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "query": { "type": "string", "description": "Search query text" },
                        "folder": { "type": "string", "description": "Optional folder path to scope the search to" },
                        "limit": { "type": "integer", "description": "Maximum number of results to return" }
                      },
                      "required": ["query"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(SearchNotesArgs.serializer(), argumentsJson.trim().ifEmpty { "{}" })
                }.getOrElse {
                    throw IllegalArgumentException("Invalid arguments for search_notes: $argumentsJson")
                }

            require(args.query.isNotBlank()) { "query parameter is required and must not be blank" }

            val effectiveFolder = args.folder ?: args.folderPathCamel ?: args.folderPathSnake
            val scope =
                if (effectiveFolder.isNullOrBlank()) {
                    SearchScope()
                } else {
                    SearchScope(folderPaths = setOf(effectiveFolder))
                }

            val topK = args.limit ?: DEFAULT_TOP_K
            val searchResult =
                hybridSearch(
                    query = args.query,
                    scope = scope,
                    topK = topK,
                )

            val dtoList =
                searchResult.results.map { res ->
                    SearchResultDto(
                        noteId = res.noteId,
                        title = res.title,
                        snippet = res.snippet,
                        score = res.score,
                        headingPath = res.headingPath,
                    )
                }

            return json.encodeToString(
                ListSerializer(SearchResultDto.serializer()),
                dtoList,
            )
        }

        companion object {
            private const val DEFAULT_TOP_K = 10
        }
    }
