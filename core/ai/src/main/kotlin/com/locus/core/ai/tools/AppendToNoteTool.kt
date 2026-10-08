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
internal data class AppendToNoteArgs(
    val noteId: String? = null,
    @SerialName("note_id") val noteIdSnake: String? = null,
    val id: String? = null,
    val text: String? = null,
    val content: String? = null,
    val body: String? = null,
)

@Serializable
data class AppendToNoteResult(
    val noteId: String,
    val success: Boolean = true,
)

/** Write tool: append_to_note (C-4). Appends text content to the end of an existing note. */
@Singleton
class AppendToNoteTool
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
                name = "append_to_note",
                description = "Append text content to the end of an existing note.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "noteId": { "type": "string", "description": "The unique ID of the note to append to" },
                        "text": { "type": "string", "description": "The text content to append" }
                      },
                      "required": ["noteId", "text"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(
                        AppendToNoteArgs.serializer(),
                        argumentsJson.trim().ifEmpty { "{}" },
                    )
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Invalid arguments for append_to_note: $argumentsJson",
                    )
                }

            val targetId = args.noteId ?: args.noteIdSnake ?: args.id
            require(!targetId.isNullOrBlank()) { "noteId parameter is required and must not be blank" }

            val textToAppend = args.text ?: args.content ?: args.body
            require(!textToAppend.isNullOrEmpty()) { "text parameter is required and must not be empty" }

            val currentBody = noteRepository.readBody(targetId)
            val updatedBody =
                if (currentBody.isEmpty()) {
                    textToAppend
                } else if (currentBody.endsWith("\n")) {
                    currentBody + textToAppend
                } else {
                    currentBody + "\n\n" + textToAppend
                }

            noteRepository.edit(targetId, updatedBody)

            return json.encodeToString(
                AppendToNoteResult.serializer(),
                AppendToNoteResult(noteId = targetId),
            )
        }
    }
