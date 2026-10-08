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
internal data class ReadNoteArgs(
    val noteId: String? = null,
    @SerialName("note_id") val snakeNoteId: String? = null,
    val id: String? = null,
)

@Serializable
data class ReadNoteResult(
    val noteId: String,
    val body: String,
)

/** Read tool: read_note (C-3). Wraps [NoteRepository.readBody], JSON-encoding the note body. */
@Singleton
class ReadNoteTool
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
                name = "read_note",
                description = "Read the full text content of a note by its ID.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "noteId": { "type": "string", "description": "The unique ID of the note to read" }
                      },
                      "required": ["noteId"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(ReadNoteArgs.serializer(), argumentsJson.trim().ifEmpty { "{}" })
                }.getOrElse {
                    throw IllegalArgumentException("Invalid arguments for read_note: $argumentsJson")
                }

            val targetId = args.noteId ?: args.snakeNoteId ?: args.id
            require(!targetId.isNullOrBlank()) { "noteId parameter is required and must not be blank" }

            val body = noteRepository.readBody(targetId)
            return json.encodeToString(
                ReadNoteResult.serializer(),
                ReadNoteResult(noteId = targetId, body = body),
            )
        }
    }
