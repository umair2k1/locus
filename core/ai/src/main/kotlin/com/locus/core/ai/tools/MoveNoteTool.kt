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
internal data class MoveNoteArgs(
    val noteId: String? = null,
    @SerialName("note_id") val noteIdSnake: String? = null,
    val id: String? = null,
    val targetFolderPath: String? = null,
    @SerialName("target_folder_path") val targetFolderPathSnake: String? = null,
    val folderPath: String? = null,
    @SerialName("folder_path") val folderPathSnake: String? = null,
    val folder: String? = null,
)

@Serializable
data class MoveNoteResult(
    val noteId: String,
    val targetFolderPath: String,
    val success: Boolean = true,
)

/** Write tool: move_note (C-4). Moves a note to a different folder in the note library. */
@Singleton
class MoveNoteTool
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
                name = "move_note",
                description = "Move a note to a different folder in the note library.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "noteId": { "type": "string", "description": "The unique ID of the note to move" },
                        "targetFolderPath": { "type": "string", "description": "The target folder path (use empty string for root)" }
                      },
                      "required": ["noteId", "targetFolderPath"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(MoveNoteArgs.serializer(), argumentsJson.trim().ifEmpty { "{}" })
                }.getOrElse {
                    throw IllegalArgumentException("Invalid arguments for move_note: $argumentsJson")
                }

            val targetId = args.noteId ?: args.noteIdSnake ?: args.id
            require(!targetId.isNullOrBlank()) { "noteId parameter is required and must not be blank" }

            val destinationFolder =
                args.targetFolderPath
                    ?: args.targetFolderPathSnake ?: args.folderPath ?: args.folderPathSnake
                    ?: args.folder ?: ""

            noteRepository.moveNote(targetId, destinationFolder)

            return json.encodeToString(
                MoveNoteResult.serializer(),
                MoveNoteResult(
                    noteId = targetId,
                    targetFolderPath = destinationFolder,
                ),
            )
        }
    }
