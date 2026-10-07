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
internal data class UpdateNoteArgs(
    val noteId: String? = null,
    @SerialName("note_id") val noteIdSnake: String? = null,
    val id: String? = null,
    val body: String? = null,
    val content: String? = null,
    val text: String? = null,
    val title: String? = null,
)

@Serializable
data class UpdateNoteResult(
    val noteId: String,
    val success: Boolean = true,
)

/** Write tool: update_note (C-4). Updates an existing note's body content or title. */
@Singleton
class UpdateNoteTool
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
                name = "update_note",
                description = "Update an existing note's body content or title.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "noteId": { "type": "string", "description": "The unique ID of the note to update" },
                        "body": { "type": "string", "description": "The new body content for the note" },
                        "title": { "type": "string", "description": "Optional new title for the note" }
                      },
                      "required": ["noteId"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(UpdateNoteArgs.serializer(), argumentsJson.trim().ifEmpty { "{}" })
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Invalid arguments for update_note: $argumentsJson",
                    )
                }

            val targetId = args.noteId ?: args.noteIdSnake ?: args.id
            require(!targetId.isNullOrBlank()) { "noteId parameter is required and must not be blank" }

            val newBody = args.body ?: args.content ?: args.text
            if (newBody != null) {
                noteRepository.edit(targetId, newBody)
            }

            if (!args.title.isNullOrBlank()) {
                noteRepository.setTitle(targetId, args.title.trim())
            }

            return json.encodeToString(
                UpdateNoteResult.serializer(),
                UpdateNoteResult(noteId = targetId),
            )
        }
    }
