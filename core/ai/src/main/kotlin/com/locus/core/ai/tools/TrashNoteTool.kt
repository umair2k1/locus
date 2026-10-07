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
internal data class TrashNoteArgs(
    val noteId: String? = null,
    @SerialName("note_id") val noteIdSnake: String? = null,
    val id: String? = null,
)

@Serializable
data class TrashNoteResult(
    val noteId: String,
    val success: Boolean = true,
)

/** Write tool: trash_note (C-4). Moves a note to the Trash folder (soft delete per N-8). */
@Singleton
class TrashNoteTool
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
                name = "trash_note",
                description = "Move a note to the Trash folder.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "noteId": { "type": "string", "description": "The unique ID of the note to move to Trash" }
                      },
                      "required": ["noteId"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(TrashNoteArgs.serializer(), argumentsJson.trim().ifEmpty { "{}" })
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Invalid arguments for trash_note: $argumentsJson",
                    )
                }

            val targetId = args.noteId ?: args.noteIdSnake ?: args.id
            require(!targetId.isNullOrBlank()) { "noteId parameter is required and must not be blank" }

            noteRepository.deleteNote(targetId)

            return json.encodeToString(
                TrashNoteResult.serializer(),
                TrashNoteResult(noteId = targetId),
            )
        }
    }
