package com.locus.core.ai.tools

import com.locus.core.ai.toolloop.ToolExecutor
import com.locus.core.ai.toolloop.ToolSchema
import com.locus.core.domain.notes.MergeNotesUseCase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class MergeNotesArgs(
    val destinationNoteId: String? = null,
    @SerialName("destination_note_id") val destinationNoteIdSnake: String? = null,
    val targetNoteId: String? = null,
    @SerialName("target_note_id") val targetNoteIdSnake: String? = null,
    val sourceNoteId: String? = null,
    @SerialName("source_note_id") val sourceNoteIdSnake: String? = null,
    val sourceNoteIds: List<String>? = null,
    @SerialName("source_note_ids") val sourceNoteIdsSnake: List<String>? = null,
    val noteIds: List<String>? = null,
    @SerialName("note_ids") val noteIdsSnake: List<String>? = null,
)

@Serializable
data class MergeNotesResult(
    val destinationNoteId: String,
    val trashedNoteIds: List<String>,
    val success: Boolean = true,
)

/**
 * Write tool: merge_notes (C-4). Wraps [MergeNotesUseCase]. Concatenates notes' bodies under
 * headings named by their original titles, trashes the source notes, and keeps the destination
 * note's ID.
 */
@Singleton
class MergeNotesTool
    @Inject
    constructor(
        private val mergeNotesUseCase: MergeNotesUseCase,
        private val json: Json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
    ) : ToolExecutor {
        override val schema: ToolSchema =
            ToolSchema(
                name = "merge_notes",
                description =
                    "Merge notes: concatenates bodies under title headings, trashes sources, keeps destination ID.",
                parametersJsonSchema =
                    """
                    {
                      "type": "object",
                      "properties": {
                        "destinationNoteId": { "type": "string", "description": "The unique ID of the destination note that keeps its ID" },
                        "sourceNoteId": { "type": "string", "description": "The unique ID of the source note to merge and trash" },
                        "sourceNoteIds": { "type": "array", "items": { "type": "string" }, "description": "List of source note IDs to merge and trash" }
                      },
                      "required": ["destinationNoteId"]
                    }
                    """.trimIndent(),
            )

        override suspend fun execute(argumentsJson: String): String {
            val args =
                runCatching {
                    json.decodeFromString(MergeNotesArgs.serializer(), argumentsJson.trim().ifEmpty { "{}" })
                }.getOrElse {
                    throw IllegalArgumentException(
                        "Invalid arguments for merge_notes: $argumentsJson",
                    )
                }

            val rawNoteIds = args.noteIds ?: args.noteIdsSnake
            val explicitDest =
                args.destinationNoteId
                    ?: args.destinationNoteIdSnake ?: args.targetNoteId ?: args.targetNoteIdSnake
                    ?: rawNoteIds?.firstOrNull()

            require(!explicitDest.isNullOrBlank()) {
                "destinationNoteId parameter is required and must not be blank"
            }

            val explicitSources =
                args.sourceNoteIds
                    ?: args.sourceNoteIdsSnake
                    ?: listOfNotNull(args.sourceNoteId ?: args.sourceNoteIdSnake)

            val sources =
                if (explicitSources.isNotEmpty()) {
                    explicitSources
                } else if (rawNoteIds != null && rawNoteIds.size > 1) {
                    rawNoteIds.drop(1)
                } else {
                    emptyList()
                }

            require(sources.isNotEmpty()) { "At least one source note is required to merge" }

            val distinctSources = sources.filter { it.isNotBlank() && it != explicitDest }.distinct()
            require(distinctSources.isNotEmpty()) {
                "At least one source note distinct from destination is required"
            }

            val mergedNote =
                mergeNotesUseCase(destinationNoteId = explicitDest, sourceNoteIds = distinctSources)

            return json.encodeToString(
                MergeNotesResult.serializer(),
                MergeNotesResult(
                    destinationNoteId = mergedNote.id,
                    trashedNoteIds = distinctSources,
                ),
            )
        }
    }
