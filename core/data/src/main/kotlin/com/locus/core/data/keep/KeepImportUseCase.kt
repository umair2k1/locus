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

package com.locus.core.data.keep

import com.locus.core.domain.keep.KeepImportRepository
import com.locus.core.domain.keep.KeepImportResult
import com.locus.core.domain.notes.NoteRepository
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@Suppress("TooGenericExceptionCaught")
class KeepImportUseCase
    @Inject
    constructor(
        private val noteRepository: NoteRepository,
    ) : KeepImportRepository {
        override suspend fun importFromZip(inputStream: InputStream): KeepImportResult {
            val notes =
                try {
                    KeepTakeoutParser.parseZip(inputStream)
                } catch (e: Exception) {
                    return KeepImportResult(0, 0, listOf("Failed to parse zip: ${e.message}"))
                }

            var imported = 0
            var skipped = 0
            val errors = mutableListOf<String>()

            for (parsed in notes) {
                try {
                    val note =
                        noteRepository.createNote(
                            folderPath = "",
                            title = parsed.title,
                            type = parsed.type,
                        )

                    if (parsed.body.isNotBlank()) {
                        noteRepository.edit(note.id, parsed.body)
                    }
                    if (parsed.isPinned) {
                        noteRepository.setPinned(note.id, true)
                    }
                    if (parsed.color != null) {
                        noteRepository.setColor(note.id, parsed.color)
                    }
                    if (parsed.tags.isNotEmpty()) {
                        noteRepository.setTags(note.id, parsed.tags)
                    }
                    imported++
                } catch (e: Exception) {
                    skipped++
                    errors.add("Failed to import note '${parsed.title}': ${e.message}")
                }
            }

            return KeepImportResult(
                importedCount = imported,
                skippedCount = skipped,
                errors = errors,
            )
        }
    }
