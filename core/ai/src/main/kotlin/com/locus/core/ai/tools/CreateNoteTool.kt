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
import com.locus.core.domain.notes.NoteType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class CreateNoteArgs(
    val title: String = "",
    val folderPath: String? = null,
    @SerialName("folder_path") val folderPathSnake: String? = null,
    val folder: String? = null,
    val type: String? = null,
    val body: String? = null,
    val content: String? = null,
    val text: String? = null,
)

@Serializable
data class CreateNoteResult(
    val noteId: String,
    val title: String,
    val folderPath: String,
    val type: String,
    val success: Boolean = true,
)

/**
 * Write tool: create_note (C-4). Creates a new note in the specified folder with the given title
 * and optional initial body.
 */
@Singleton
class CreateNoteTool
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
                name = "create_note",
                description = "Create a new note in the note library.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "title": { "type": "string", "description": "The title of the new note" },
                        "folderPath": { "type": "string", "description": "Optional folder path where the note will be created (defaults to root)" },
                        "type": { "type": "string", "enum": ["NOTE", "CHECKLIST"], "description": "Optional note type (defaults to NOTE)" },
                        "body": { "type": "string", "description": "Optional initial text content for the note" }
                      },
                      "required": ["title"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(CreateNoteArgs.serializer(), argumentsJson.trim().ifEmpty { "{}" })
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Invalid arguments for create_note: $argumentsJson",
                    )
                }

            require(args.title.isNotBlank()) { "title parameter is required and must not be blank" }

            val folder = args.folderPath ?: args.folderPathSnake ?: args.folder ?: ""
            val noteType =
                if (args.type?.equals("CHECKLIST", ignoreCase = true) == true) {
                    NoteType.CHECKLIST
                } else {
                    NoteType.NOTE
                }

            val note =
                noteRepository.createNote(folderPath = folder, title = args.title.trim(), type = noteType)

            val initialBody = args.body ?: args.content ?: args.text
            if (!initialBody.isNullOrEmpty()) {
                noteRepository.edit(note.id, initialBody)
            }

            return json.encodeToString(
                CreateNoteResult.serializer(),
                CreateNoteResult(
                    noteId = note.id,
                    title = note.title,
                    folderPath = note.folderPath,
                    type = note.type.name,
                ),
            )
        }
    }
