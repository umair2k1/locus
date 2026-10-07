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
internal data class TagNoteArgs(
    val noteId: String? = null,
    @SerialName("note_id") val noteIdSnake: String? = null,
    val id: String? = null,
    val tags: List<String>? = null,
    val tag: String? = null,
    val action: String = "add",
)

@Serializable
data class TagNoteResult(
    val noteId: String,
    val tags: List<String>,
    val success: Boolean = true,
)

/** Write tool: tag_note (C-4). Adds, removes, or sets tags on a note. */
@Singleton
class TagNoteTool
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
                name = "tag_note",
                description = "Add, remove, or set tags on a note.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "noteId": { "type": "string", "description": "The unique ID of the note to tag" },
                        "tags": { "type": "array", "items": { "type": "string" }, "description": "List of tags to apply" },
                        "tag": { "type": "string", "description": "A single tag to apply" },
                        "action": { "type": "string", "enum": ["add", "remove", "set"], "description": "Action to perform (default: add)" }
                      },
                      "required": ["noteId"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(TagNoteArgs.serializer(), argumentsJson.trim().ifEmpty { "{}" })
                }.getOrElse {
                    throw IllegalArgumentException("Invalid arguments for tag_note: $argumentsJson")
                }

            val targetId = args.noteId ?: args.noteIdSnake ?: args.id
            require(!targetId.isNullOrBlank()) { "noteId parameter is required and must not be blank" }

            val rawTags = (args.tags ?: listOfNotNull(args.tag))
            val tagsToApply = rawTags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }

            val existingNote = noteRepository.getNote(targetId)
            val currentTags = existingNote?.tags ?: emptyList()

            val updatedTags =
                when (args.action.lowercase()) {
                    "remove" -> currentTags.filterNot { it in tagsToApply }
                    "set" -> tagsToApply.distinct()
                    else -> (currentTags + tagsToApply).distinct()
                }

            noteRepository.setTags(targetId, updatedTags)

            return json.encodeToString(
                TagNoteResult.serializer(),
                TagNoteResult(
                    noteId = targetId,
                    tags = updatedTags,
                ),
            )
        }
    }
