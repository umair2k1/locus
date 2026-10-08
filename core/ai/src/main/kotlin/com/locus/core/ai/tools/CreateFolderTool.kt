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
import com.locus.core.domain.notes.NoteRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class CreateFolderArgs(
    val parentPath: String? = null,
    @SerialName("parent_path") val parentPathSnake: String? = null,
    val name: String? = null,
    val folderName: String? = null,
    @SerialName("folder_name") val folderNameSnake: String? = null,
)

@Serializable
data class CreateFolderResult(
    val parentPath: String,
    val folderName: String,
    val success: Boolean = true,
)

/** Write tool: create_folder (C-4). Creates a new folder in the note library. */
@Singleton
class CreateFolderTool
    @Inject
    constructor(
        private val noteRepository: NoteRepository,
        private val json: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
    ) : ToolExecutor {
        override val schema: ToolSchema =
            ToolSchema(
                name = "create_folder",
                description = "Create a new folder in the note library.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "name": { "type": "string", "description": "The name of the new folder" },
                        "parentPath": { "type": "string", "description": "Optional parent folder path (defaults to root)" }
                      },
                      "required": ["name"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(
                        CreateFolderArgs.serializer(),
                        argumentsJson.trim().ifEmpty { "{}" },
                    )
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Invalid arguments for create_folder: $argumentsJson",
                    )
                }

            val folderName = args.name ?: args.folderName ?: args.folderNameSnake
            require(!folderName.isNullOrBlank()) { "name parameter is required and must not be blank" }

            val parent = args.parentPath ?: args.parentPathSnake ?: ""

            noteRepository.createFolder(parentPath = parent, name = folderName.trim())

            return json.encodeToString(
                CreateFolderResult.serializer(),
                CreateFolderResult(
                    parentPath = parent,
                    folderName = folderName.trim(),
                ),
            )
        }
    }
