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

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.locus.core.data.history.NoteHistoryStore
import com.locus.core.domain.notes.Checksum
import com.locus.core.domain.notes.FlushReceipt
import com.locus.core.domain.notes.NoteFileWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SafNoteFileWriter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fileSource: SafNoteFileSource,
        private val treeUriStore: TreeUriStore,
        private val historyStore: NoteHistoryStore,
    ) : NoteFileWriter {
        constructor(
            context: Context,
            fileSource: SafNoteFileSource,
            treeUriStore: TreeUriStore,
        ) : this(
            context = context,
            fileSource = fileSource,
            treeUriStore = treeUriStore,
            historyStore = NoteHistoryStore(context, fileSource, treeUriStore),
        )

        override suspend fun atomicWrite(
            noteId: String,
            path: String,
            content: String,
        ): Result<FlushReceipt> =
            withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        path.startsWith("content://") -> writeSafDocument(noteId, path, content)
                        isFilesystemPath(path) -> writeFileDirect(noteId, path, content)
                        else -> writeRelativeSafDocument(noteId, path, content)
                    }
                }
            }

        private fun isFilesystemPath(path: String): Boolean =
            path.startsWith("/") ||
                path.startsWith("file://") ||
                (path.length > 2 && path[1] == ':' && (path[2] == '\\' || path[2] == '/'))

        private suspend fun writeFileDirect(
            noteId: String,
            path: String,
            content: String,
        ): FlushReceipt {
            val file = File(if (path.startsWith("file://")) Uri.parse(path).path ?: path else path)
            val parent = file.parentFile ?: File(".")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Failed to create parent directory for ${file.absolutePath}")
            }

            if (file.exists() && file.length() > 0) {
                val previousContent = file.readText(Charsets.UTF_8)
                if (previousContent.isNotEmpty()) {
                    historyStore.snapshot(noteId, previousContent, parent)
                }
            }

            val tempFile = File(parent, ".${file.name}.${System.currentTimeMillis()}.tmp")
            tempFile.writeText(content, Charsets.UTF_8)
            val renamed = tempFile.renameTo(file)
            if (!renamed) {
                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
            }
            return FlushReceipt(
                noteId = noteId,
                checksum = Checksum.sha256(content),
                flushedAt = System.currentTimeMillis(),
            )
        }

        private suspend fun writeSafDocument(
            noteId: String,
            path: String,
            content: String,
        ): FlushReceipt {
            val targetUri = Uri.parse(path)
            val treeUri = treeUriStore.getTreeUri()
            val root = treeUri?.let { fileSource.getRootDocument(it) }

            var targetDoc: DocumentFile? = null
            var parentDoc: DocumentFile? = null
            var targetName: String? = null

            if (treeUri != null) {
                val match = fileSource.listMarkdownFiles(treeUri).firstOrNull { it.uri == targetUri }
                if (match != null) {
                    targetDoc = match
                    parentDoc = match.parentFile ?: root
                    targetName = match.name
                }
            }

            if (parentDoc == null) {
                val singleDoc = DocumentFile.fromSingleUri(context, targetUri)
                targetDoc = singleDoc
                targetName = singleDoc?.name
                parentDoc = singleDoc?.parentFile ?: root
            }

            if (parentDoc == null) {
                throw IOException("Cannot resolve parent directory document for $path")
            }

            val finalName = targetName ?: (if (noteId.endsWith(".md")) noteId else "$noteId.md")
            return writeToParentAndReplace(
                noteId = noteId,
                parentDoc = parentDoc,
                targetDoc = targetDoc,
                targetName = finalName,
                content = content,
            )
        }

        private suspend fun writeRelativeSafDocument(
            noteId: String,
            relativePath: String,
            content: String,
        ): FlushReceipt {
            val treeUri =
                treeUriStore.getTreeUri()
                    ?: throw IOException(
                        "No tree URI configured for relative path $relativePath",
                    )
            val root =
                fileSource.getRootDocument(treeUri)
                    ?: throw IOException("Could not load root document for $treeUri")

            val segments = relativePath.trim('/').split('/')
            val parentDir = resolveSubfolder(root, segments.dropLast(1))
            val fileName = segments.last()
            val targetDoc = parentDir.findFile(fileName)
            return writeToParentAndReplace(
                noteId = noteId,
                parentDoc = parentDir,
                targetDoc = targetDoc,
                targetName = fileName,
                content = content,
            )
        }

        private fun resolveSubfolder(
            root: DocumentFile,
            folderSegments: List<String>,
        ): DocumentFile {
            var currentDir = root
            for (seg in folderSegments) {
                val existing = currentDir.findFile(seg)
                currentDir =
                    if (existing != null && existing.isDirectory) {
                        existing
                    } else {
                        currentDir.createDirectory(seg) ?: currentDir
                    }
            }
            return currentDir
        }

        private suspend fun writeToParentAndReplace(
            noteId: String,
            parentDoc: DocumentFile,
            targetDoc: DocumentFile?,
            targetName: String,
            content: String,
        ): FlushReceipt {
            val target = targetDoc ?: parentDoc.findFile(targetName)
            if (target != null && target.exists() && target.length() > 0) {
                val previousContent = runCatching { fileSource.readText(target) }.getOrNull()
                if (!previousContent.isNullOrEmpty()) {
                    historyStore.snapshot(noteId, previousContent)
                }
            }
            val docToWrite =
                target
                    ?: parentDoc.createFile("text/markdown", targetName)
                    ?: throw IOException("Failed to create document $targetName")
            writeContent(docToWrite, content)
            return FlushReceipt(
                noteId = noteId,
                checksum = Checksum.sha256(content),
                flushedAt = System.currentTimeMillis(),
            )
        }

        private fun writeContent(
            doc: DocumentFile,
            content: String,
        ) {
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
            }
                ?: throw IOException("Failed to open output stream for ${doc.uri}")
        }
    }
