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

package com.locus.core.data.files

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.locus.core.data.db.NoteDao
import com.locus.core.data.db.NoteIndexEntity
import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.FileFallbackMetadata
import com.locus.core.domain.notes.FrontmatterParser
import com.locus.core.domain.notes.Note
import com.locus.core.domain.notes.ParsedNote
import com.locus.core.domain.notes.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrashManager
    @Inject
    constructor(
        private val fileSource: SafNoteFileSource,
        private val parser: FrontmatterParser,
        private val noteDao: NoteDao,
    ) {
        suspend fun moveToTrash(
            root: DocumentFile,
            noteId: String,
            noteDoc: DocumentFile,
            originalFolderPath: String,
        ): Unit =
            withContext(Dispatchers.IO) {
                val trashDir = resolveOrCreateDirectory(root, TRASH_FOLDER_PATH)

                val existingTrashFiles = trashDir.listFiles().mapNotNull { it.name }.toSet()
                val currentDocName = noteDoc.name ?: "$noteId$MD_EXTENSION"
                val safeTrashName =
                    FilenameCollisionResolver.resolve(currentDocName, existingTrashFiles)
                if (safeTrashName != currentDocName) {
                    fileSource.renameDocument(noteDoc, safeTrashName)
                }

                val originFileName = "$noteId$ORIGIN_EXTENSION"
                val existingOrigin = trashDir.findFile(originFileName)
                val originDoc =
                    existingOrigin
                        ?: trashDir.createFile(ORIGIN_MIME_TYPE, originFileName)
                        ?: throw IOException(
                            "Failed to create origin sidecar file for note $noteId",
                        )
                fileSource.writeText(originDoc, originalFolderPath)

                fileSource.moveDocument(noteDoc, trashDir)

                noteDao.deleteById(noteId)
            }

        suspend fun restoreFromTrash(
            root: DocumentFile,
            noteId: String,
        ): Note =
            withContext(Dispatchers.IO) {
                val trashDir = resolveTrashDir(root)
                val (targetDoc, parsedNote) = findTrashedNote(trashDir, noteId)

                val originDoc = trashDir.findFile("$noteId$ORIGIN_EXTENSION")
                val originalFolderPath =
                    originDoc
                        ?.let { runCatching { fileSource.readText(it).trim() }.getOrNull() }
                        .orEmpty()

                val targetDir = resolveOrCreateDirectory(root, originalFolderPath)

                val existingInTarget = targetDir.listFiles().mapNotNull { it.name }.toSet()
                val baseName = targetDoc.name ?: "${parsedNote.title}$MD_EXTENSION"
                val safeTargetName = FilenameCollisionResolver.resolve(baseName, existingInTarget)
                if (safeTargetName != baseName) {
                    fileSource.renameDocument(targetDoc, safeTargetName)
                }

                val restoredDoc = fileSource.moveDocument(targetDoc, targetDir)

                originDoc?.delete()

                val rawText = fileSource.readText(restoredDoc)
                val checksum = Checksum.sha256(rawText)
                val entity = parsedNote.toIndexEntity(originalFolderPath, checksum)
                noteDao.upsert(entity)

                parsedNote.toDomain(originalFolderPath)
            }

        private fun resolveTrashDir(root: DocumentFile): DocumentFile {
            val locusDir =
                root.listFiles().firstOrNull { it.isDirectory && it.name == LOCUS_DIR_NAME }
                    ?: throw NoSuchElementException("Trash folder not found")
            return locusDir.listFiles().firstOrNull { it.isDirectory && it.name == TRASH_DIR_NAME }
                ?: throw NoSuchElementException("Trash folder not found")
        }

        private fun findTrashedNote(
            trashDir: DocumentFile,
            noteId: String,
        ): Pair<DocumentFile, ParsedNote> {
            val mdFiles =
                trashDir.listFiles().filter {
                    it.isFile && it.name?.endsWith(MD_EXTENSION, ignoreCase = true) == true
                }
            val parsedFiles =
                mdFiles.mapNotNull { file ->
                    val text =
                        runCatching { fileSource.readText(file) }.getOrNull()
                            ?: return@mapNotNull null
                    val parsed = parseDocument(file, text)
                    file to parsed
                }
            return parsedFiles.firstOrNull { it.second.id == noteId }
                ?: throw NoSuchElementException("Note with id '$noteId' not found in trash")
        }

        suspend fun loadTrashNotes(treeUri: Uri?): List<Note> =
            withContext(Dispatchers.IO) {
                if (treeUri == null) return@withContext emptyList()
                val root = fileSource.getRootDocument(treeUri) ?: return@withContext emptyList()
                val locusDir =
                    root.listFiles().firstOrNull { it.isDirectory && it.name == LOCUS_DIR_NAME }
                        ?: return@withContext emptyList()
                val trashDir =
                    locusDir.listFiles().firstOrNull {
                        it.isDirectory && it.name == TRASH_DIR_NAME
                    }
                        ?: return@withContext emptyList()

                val mdFiles =
                    trashDir.listFiles().filter {
                        it.isFile && it.name?.endsWith(MD_EXTENSION, ignoreCase = true) == true
                    }

                val notes = mutableListOf<Note>()
                for (file in mdFiles) {
                    val rawText = runCatching { fileSource.readText(file) }.getOrNull() ?: continue
                    val parsed = parseDocument(file, rawText)
                    val originDoc = trashDir.findFile("${parsed.id}$ORIGIN_EXTENSION")
                    val originFolder =
                        originDoc
                            ?.let {
                                runCatching { fileSource.readText(it).trim() }.getOrNull()
                            }.orEmpty()
                    notes.add(parsed.toDomain(originFolder))
                }
                notes
            }

        private fun parseDocument(
            file: DocumentFile,
            rawText: String,
        ): ParsedNote {
            val lastModifiedMs = file.lastModified()
            val modifiedInstant =
                if (lastModifiedMs > 0) {
                    Instant.ofEpochMilli(lastModifiedMs)
                } else {
                    Instant.now()
                }
            val fallback =
                FileFallbackMetadata(
                    fileCreated = modifiedInstant,
                    fileModified = modifiedInstant,
                    appVersion = "Locus 1.0.0",
                )
            return parser.parse(rawText, fallback)
        }

        private fun ParsedNote.toIndexEntity(
            folderPath: String,
            checksum: String,
        ): NoteIndexEntity =
            NoteIndexEntity(
                id = id,
                title = title,
                type = type,
                folderPath = folderPath,
                pinned = pinned,
                color = color,
                tags = tags,
                created = created,
                modified = modified,
                checksum = checksum,
                bodyPreview = body.take(BODY_PREVIEW_LENGTH),
            )

        internal companion object {
            private const val LOCUS_DIR_NAME = ".locus"
            private const val TRASH_DIR_NAME = "trash"
            private const val TRASH_FOLDER_PATH = ".locus/trash"
            private const val MD_EXTENSION = ".md"
            private const val ORIGIN_EXTENSION = ".origin"
            private const val ORIGIN_MIME_TYPE = "text/plain"
            private const val BODY_PREVIEW_LENGTH = 200
        }
    }

internal fun resolveOrCreateDirectory(
    root: DocumentFile,
    path: String,
): DocumentFile {
    val normalized = path.trim().trim('/')
    if (normalized.isEmpty()) return root
    val segments = normalized.split('/').filter { it.isNotBlank() }
    var current: DocumentFile = root
    for (seg in segments) {
        val next =
            current.listFiles().firstOrNull { it.isDirectory && it.name == seg }
                ?: current.createDirectory(seg)
                ?: throw IOException(
                    "Failed to create intermediate directory '$seg' in '$path'",
                )
        current = next
    }
    return current
}
