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

package com.locus.core.domain.notes

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-repository orchestration / note merge use case (C-4).
 *
 * Concatenates two or more notes' bodies under headings named by their original titles,
 * trashes the source notes (via [NoteRepository.deleteNote] consistent with N-8 soft delete),
 * and keeps the destination note's ID.
 */
@Singleton
class MergeNotesUseCase
    @Inject
    constructor(
        private val noteRepository: NoteRepository,
    ) {
        suspend operator fun invoke(
            destinationNoteId: String,
            sourceNoteId: String,
        ): Note = invoke(destinationNoteId, listOf(sourceNoteId))

        suspend operator fun invoke(
            destinationNoteId: String,
            sourceNoteIds: List<String>,
        ): Note {
            require(destinationNoteId.isNotBlank()) { "destinationNoteId must not be blank" }
            val distinctSources = sourceNoteIds.filter { it.isNotBlank() && it != destinationNoteId }.distinct()
            require(distinctSources.isNotEmpty()) { "At least one source note distinct from destination is required" }

            val destNote = noteRepository.getNote(destinationNoteId)
            val destBody = noteRepository.readBody(destinationNoteId)
            val destTitle = destNote?.title ?: extractFirstHeading(destBody) ?: "Untitled"

            val sections = mutableListOf<String>()
            sections += formatSection(destTitle, destBody)

            for (sourceId in distinctSources) {
                val sourceNote = noteRepository.getNote(sourceId)
                val sourceBody = noteRepository.readBody(sourceId)
                val sourceTitle = sourceNote?.title ?: extractFirstHeading(sourceBody) ?: "Untitled"
                sections += formatSection(sourceTitle, sourceBody)
            }

            val mergedBody = sections.joinToString("\n\n") + "\n"

            noteRepository.edit(destinationNoteId, mergedBody)

            for (sourceId in distinctSources) {
                noteRepository.deleteNote(sourceId)
            }

            return noteRepository.getNote(destinationNoteId)
                ?: destNote?.copy(checksum = "")
                ?: Note(
                    id = destinationNoteId,
                    title = destTitle,
                    type = destNote?.type ?: NoteType.NOTE,
                    folderPath = destNote?.folderPath.orEmpty(),
                    pinned = destNote?.pinned ?: false,
                    color = destNote?.color,
                    tags = destNote?.tags ?: emptyList(),
                    created = destNote?.created ?: java.time.Instant.EPOCH,
                    modified = java.time.Instant.now(),
                    checksum = "",
                )
        }

        suspend fun merge(
            destinationNoteId: String,
            sourceNoteId: String,
        ): Note = invoke(destinationNoteId, sourceNoteId)

        suspend fun merge(
            destinationNoteId: String,
            sourceNoteIds: List<String>,
        ): Note = invoke(destinationNoteId, sourceNoteIds)

        suspend fun merge(
            sourceNoteIds: List<String>,
            destinationNoteId: String,
        ): Note = invoke(destinationNoteId, sourceNoteIds)

        private fun formatSection(
            title: String,
            body: String,
        ): String {
            val trimmedBody = body.trim()
            return if (trimmedBody.isEmpty()) {
                "# $title"
            } else {
                "# $title\n\n$trimmedBody"
            }
        }

        private fun extractFirstHeading(body: String): String? =
            body.lineSequence().firstNotNullOfOrNull { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("# ")) {
                    trimmed.removePrefix("# ").trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
    }
