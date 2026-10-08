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

package com.locus.core.data.history

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.locus.core.data.files.SafNoteFileSource
import com.locus.core.data.files.TreeUriStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

typealias HistoryRevision = com.locus.core.domain.notes.HistoryRevision

@Singleton
open class NoteHistoryStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val fileSource: SafNoteFileSource,
        private val treeUriStore: TreeUriStore,
    ) {
        constructor(
            fileSource: SafNoteFileSource,
            treeUriStore: TreeUriStore,
        ) : this(
            context = FallbackContext(),
            fileSource = fileSource,
            treeUriStore = treeUriStore,
        )

        private val noteIdToRootDir = ConcurrentHashMap<String, File>()
        private var lastKnownRootDir: File? = null

        open suspend fun snapshot(
            noteId: String,
            previousBody: String,
            rootHint: File? = null,
        ): Unit =
            withContext(Dispatchers.IO) {
                val cleanBody = extractBody(previousBody)
                if (cleanBody.isEmpty()) return@withContext

                val treeUri = treeUriStore.getTreeUri()
                if (treeUri != null && isSafContentUri(treeUri)) {
                    snapshotSaf(noteId, cleanBody, treeUri)
                } else {
                    val rootDir = resolveFileRootDir(treeUri, rootHint)
                    noteIdToRootDir[noteId] = rootDir
                    lastKnownRootDir = rootDir
                    snapshotDirectFile(noteId, cleanBody, rootDir)
                }
            }

        open suspend fun listRevisions(noteId: String): List<HistoryRevision> =
            withContext(Dispatchers.IO) {
                val treeUri = treeUriStore.getTreeUri()
                if (treeUri != null && isSafContentUri(treeUri)) {
                    val revisions = listRevisionsSaf(noteId, treeUri)
                    if (revisions.isNotEmpty()) return@withContext revisions
                }

                val rootDir =
                    noteIdToRootDir[noteId] ?: resolveFileRootDir(treeUri, lastKnownRootDir)
                val revisions = listRevisionsDirectFile(noteId, rootDir)
                if (revisions.isNotEmpty()) return@withContext revisions

                val fallbackDir = context.filesDir ?: File(".")
                if (rootDir != fallbackDir) {
                    val fallbackRevisions = listRevisionsDirectFile(noteId, fallbackDir)
                    if (fallbackRevisions.isNotEmpty()) return@withContext fallbackRevisions
                }
                emptyList()
            }

        private fun isSafContentUri(uri: Uri): Boolean = uri.scheme == "content"

        private fun resolveFileRootDir(
            treeUri: Uri?,
            rootHint: File?,
        ): File =
            when {
                treeUri != null && treeUri.scheme == "file" ->
                    File(treeUri.path ?: treeUri.toString().removePrefix("file://"))
                rootHint != null -> rootHint
                lastKnownRootDir != null -> lastKnownRootDir!!
                else -> context.filesDir ?: File(".")
            }

        private fun snapshotSaf(
            noteId: String,
            body: String,
            treeUri: Uri,
        ) {
            val rootDoc =
                fileSource.getRootDocument(treeUri)
                    ?: throw IOException("Cannot resolve root document for $treeUri")

            val locusDir = resolveOrCreateDir(rootDoc, LOCUS_DIR)
            val historyDir = resolveOrCreateDir(locusDir, HISTORY_DIR)
            val noteHistoryDir = resolveOrCreateDir(historyDir, noteId)

            var timestamp = System.currentTimeMillis()
            var fileName = "$timestamp$MD_EXT"
            while (noteHistoryDir.findFile(fileName) != null) {
                timestamp++
                fileName = "$timestamp$MD_EXT"
            }

            val snapshotDoc =
                noteHistoryDir.createFile(MIME_TYPE_MARKDOWN, fileName)
                    ?: throw IOException(
                        "Failed to create snapshot document $fileName for note $noteId",
                    )
            fileSource.writeText(snapshotDoc, body)

            val existingFiles =
                noteHistoryDir
                    .listFiles()
                    .filter {
                        it.isFile && it.name?.endsWith(MD_EXT, ignoreCase = true) == true
                    }.sortedWith(
                        compareBy(
                            {
                                it.name?.removeSuffix(MD_EXT)?.toLongOrNull()
                                    ?: it.lastModified()
                            },
                            { it.name.orEmpty() },
                        ),
                    )

            if (existingFiles.size > MAX_REVISIONS) {
                val excess = existingFiles.size - MAX_REVISIONS
                for (fileToDelete in existingFiles.take(excess)) {
                    fileToDelete.delete()
                }
            }
        }

        private fun snapshotDirectFile(
            noteId: String,
            body: String,
            rootDir: File,
        ) {
            val noteHistoryDir = File(rootDir, "$LOCUS_DIR/$HISTORY_DIR/$noteId")
            if (!noteHistoryDir.exists() && !noteHistoryDir.mkdirs()) {
                throw IOException("Failed to create history directory: ${noteHistoryDir.absolutePath}")
            }

            var timestamp = System.currentTimeMillis()
            var targetFile = File(noteHistoryDir, "$timestamp$MD_EXT")
            while (targetFile.exists()) {
                timestamp++
                targetFile = File(noteHistoryDir, "$timestamp$MD_EXT")
            }

            targetFile.writeText(body, Charsets.UTF_8)

            val existingFiles =
                noteHistoryDir
                    .listFiles { file ->
                        file.isFile && file.name.endsWith(MD_EXT, ignoreCase = true)
                    }?.sortedWith(
                        compareBy(
                            {
                                it.name.removeSuffix(MD_EXT).toLongOrNull()
                                    ?: it.lastModified()
                            },
                            { it.name },
                        ),
                    ).orEmpty()

            if (existingFiles.size > MAX_REVISIONS) {
                val excess = existingFiles.size - MAX_REVISIONS
                for (fileToDelete in existingFiles.take(excess)) {
                    fileToDelete.delete()
                }
            }
        }

        private fun listRevisionsSaf(
            noteId: String,
            treeUri: Uri,
        ): List<HistoryRevision> {
            val noteHistoryDir =
                fileSource
                    .getRootDocument(treeUri)
                    ?.findFile(LOCUS_DIR)
                    ?.findFile(HISTORY_DIR)
                    ?.findFile(noteId)
                    ?: return emptyList()
            return noteHistoryDir
                .listFiles()
                .filter { it.isFile && it.name?.endsWith(MD_EXT, ignoreCase = true) == true }
                .mapNotNull { file ->
                    val timestamp =
                        file.name?.removeSuffix(MD_EXT)?.toLongOrNull() ?: file.lastModified()
                    val body =
                        runCatching { fileSource.readText(file) }.getOrNull()
                            ?: return@mapNotNull null
                    HistoryRevision(timestamp = timestamp, body = body)
                }.sortedByDescending { it.timestamp }
        }

        private fun listRevisionsDirectFile(
            noteId: String,
            rootDir: File,
        ): List<HistoryRevision> {
            val noteHistoryDir = File(rootDir, "$LOCUS_DIR/$HISTORY_DIR/$noteId")
            if (!noteHistoryDir.exists() || !noteHistoryDir.isDirectory) return emptyList()

            return noteHistoryDir
                .listFiles { file -> file.isFile && file.name.endsWith(MD_EXT, ignoreCase = true) }
                ?.mapNotNull { file ->
                    val timestamp =
                        file.name.removeSuffix(MD_EXT).toLongOrNull() ?: file.lastModified()
                    val body =
                        runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
                            ?: return@mapNotNull null
                    HistoryRevision(timestamp = timestamp, body = body)
                }?.sortedByDescending { it.timestamp }
                .orEmpty()
        }

        private fun resolveOrCreateDir(
            parent: DocumentFile,
            dirName: String,
        ): DocumentFile {
            val existing = parent.findFile(dirName)
            if (existing != null && existing.isDirectory) return existing
            return parent.createDirectory(dirName)
                ?: throw IOException("Failed to create directory $dirName under ${parent.name}")
        }

        internal fun extractBody(raw: String): String {
            val match = FRONTMATTER_REGEX.find(raw)
            return if (match != null) {
                raw.substring(match.range.last + 1).trimStart('\n')
            } else {
                raw
            }
        }

        private class FallbackContext(
            private val baseDir: File = File("."),
        ) : ContextWrapper(null) {
            override fun getFilesDir(): File = baseDir
        }

        companion object {
            private const val MAX_REVISIONS = 20
            private const val LOCUS_DIR = ".locus"
            private const val HISTORY_DIR = "history"
            private const val MD_EXT = ".md"
            private const val MIME_TYPE_MARKDOWN = "text/markdown"
            private val FRONTMATTER_REGEX = Regex("(?s)\\A---\\r?\\n.*?\\r?\\n---\\r?\\n?")
        }
    }
