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

package com.locus.core.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.locus.core.data.files.SafNoteFileSource
import com.locus.core.data.files.TreeUriStore
import com.locus.core.data.files.resolveOrCreateDirectory
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.notes.RescanReport
import com.locus.core.domain.notes.YamlCodec
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ImportResult {
    data class Success(
        val fileCount: Int,
        val rescanReport: RescanReport? = null,
    ) : ImportResult

    data class InvalidZip(
        val message: String,
    ) : ImportResult

    data class Failure(
        val cause: Throwable,
    ) : ImportResult
}

@Singleton
class LibraryImporter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repo: NoteRepository,
        private val treeUriStore: TreeUriStore,
        private val fileSource: SafNoteFileSource,
        private val yamlCodec: YamlCodec,
        private val dispatchers: DispatcherProvider,
    ) {
        @Suppress("TooGenericExceptionCaught")
        suspend fun import(
            zipUri: Uri,
            destinationTreeUri: Uri,
        ): ImportResult =
            withContext(dispatchers.io) {
                try {
                    val validationError = validateArchive(zipUri)
                    if (validationError != null) {
                        return@withContext ImportResult.InvalidZip(validationError)
                    }

                    val rootDoc =
                        resolveRootDoc(destinationTreeUri)
                            ?: return@withContext ImportResult.Failure(
                                IllegalStateException(
                                    "Cannot resolve root document for $destinationTreeUri",
                                ),
                            )

                    val fileCount = extractArchive(zipUri, rootDoc)
                    treeUriStore.setTreeUri(destinationTreeUri)
                    repo.setRootUri(destinationTreeUri.toString())
                    val report = repo.rescan()

                    ImportResult.Success(fileCount = fileCount, rescanReport = report)
                } catch (e: ZipException) {
                    ImportResult.InvalidZip("Corrupt or invalid zip archive: ${e.message}")
                } catch (e: Exception) {
                    ImportResult.Failure(e)
                }
            }

        private fun validateArchive(zipUri: Uri): String? {
            val result = runCatching { scanArchiveForValidNote(zipUri) }
            val hasValidNote = result.getOrNull() ?: false
            return when {
                result.isFailure -> "Failed to read zip archive: ${result.exceptionOrNull()?.message}"
                !hasValidNote -> "No valid frontmatter-bearing markdown notes found in archive"
                else -> null
            }
        }

        private fun scanArchiveForValidNote(zipUri: Uri): Boolean =
            ZipInputStream(openInputStreamForUri(zipUri)).use { zipIn ->
                var found = false
                var entry = zipIn.nextEntry
                while (entry != null && !found) {
                    if (checkEntryHasValidNote(zipIn, entry.name, entry.isDirectory)) {
                        found = true
                    }
                    zipIn.closeEntry()
                    if (!found) {
                        entry = zipIn.nextEntry
                    }
                }
                found
            }

        private fun checkEntryHasValidNote(
            zipIn: ZipInputStream,
            name: String,
            isDir: Boolean,
        ): Boolean {
            if (isDir || !isCandidateMarkdownEntry(name)) return false
            val content = String(zipIn.readBytes(), Charsets.UTF_8)
            return hasValidFrontmatter(content)
        }

        private fun isCandidateMarkdownEntry(name: String): Boolean =
            name.endsWith(".md", ignoreCase = true) &&
                !name.startsWith(".locus/history/") &&
                !name.startsWith("/.locus/history/")

        private fun hasValidFrontmatter(content: String): Boolean {
            val match = FRONTMATTER_REGEX.find(content)
            val fields = match?.let { runCatching { yamlCodec.decode(it.groupValues[1]) }.getOrNull() }
            return fields != null &&
                (
                    fields.containsKey("id") ||
                        fields.containsKey("title") ||
                        fields.keys.any { it in KNOWN_FIELDS }
                )
        }

        private fun extractArchive(
            zipUri: Uri,
            rootDoc: DocumentFile,
        ): Int {
            var fileCount = 0
            ZipInputStream(openInputStreamForUri(zipUri)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (processZipEntry(zipIn, rootDoc, entry)) {
                        fileCount++
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            return fileCount
        }

        private fun processZipEntry(
            zipIn: ZipInputStream,
            rootDoc: DocumentFile,
            entry: ZipEntry,
        ): Boolean {
            val name = entry.name
            val isFile = isSafePath(name) && !entry.isDirectory
            if (isSafePath(name)) {
                if (entry.isDirectory) {
                    resolveOrCreateDirectory(rootDoc, name)
                } else {
                    extractFileEntry(zipIn, rootDoc, name)
                }
            }
            return isFile
        }

        private fun isSafePath(name: String): Boolean {
            val hasTraversal = name.contains("..")
            val isRooted = name.startsWith("/") || name.startsWith("\\")
            return !hasTraversal && !isRooted
        }

        private fun extractFileEntry(
            zipIn: ZipInputStream,
            rootDoc: DocumentFile,
            entryName: String,
        ) {
            val normalized = entryName.trim().trim('/')
            val lastSlash = normalized.lastIndexOf('/')
            val (parentPath, fileName) =
                if (lastSlash >= 0) {
                    normalized.substring(0, lastSlash) to normalized.substring(lastSlash + 1)
                } else {
                    "" to normalized
                }

            val parentDir = resolveOrCreateDirectory(rootDoc, parentPath)
            val existing = parentDir.listFiles().firstOrNull { it.isFile && it.name == fileName }
            val targetFile =
                existing
                    ?: parentDir.createFile(mimeTypeFor(fileName), fileName)
                    ?: throw IOException("Failed to create document file '$fileName'")

            openOutputStreamForDoc(targetFile).use { out -> zipIn.copyTo(out) }
        }

        private fun mimeTypeFor(fileName: String): String =
            when {
                fileName.endsWith(".md", ignoreCase = true) -> "text/markdown"
                fileName.endsWith(".origin", ignoreCase = true) -> "text/plain"
                fileName.endsWith(".json", ignoreCase = true) -> "application/json"
                else -> "application/octet-stream"
            }

        private fun resolveRootDoc(treeUri: Uri): DocumentFile? =
            fileSource.getRootDocument(treeUri)
                ?: if (treeUri.scheme == "file") {
                    val path = treeUri.path ?: treeUri.toString().removePrefix("file://")
                    val file = File(path)
                    file.mkdirs()
                    DocumentFile.fromFile(file)
                } else if (android.provider.DocumentsContract.isTreeUri(treeUri)) {
                    DocumentFile.fromTreeUri(context, treeUri)
                } else {
                    null
                }

        private fun openInputStreamForUri(uri: Uri): InputStream {
            val testStream = readTestDocumentStreamByUri(uri)
            if (testStream != null) return testStream

            return if (uri.scheme == "file") {
                val path = uri.path ?: uri.toString().removePrefix("file://")
                FileInputStream(File(path))
            } else {
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open input stream for $uri")
            }
        }

        private fun readTestDocumentStreamByUri(uri: Uri): InputStream? {
            val doc = runCatching { fileSource.getRootDocument(uri) }.getOrNull() ?: return null
            return getTestDocInputStream(doc)
        }

        private fun getTestDocInputStream(doc: DocumentFile): InputStream? {
            val bytesField =
                runCatching {
                    doc.javaClass.getDeclaredField("contentBytes").apply {
                        isAccessible = true
                    }
                }.getOrNull()
            val bytes = bytesField?.get(doc) as? ByteArray
            if (bytes != null) return ByteArrayInputStream(bytes)

            val contentField =
                runCatching {
                    doc.javaClass.getDeclaredField("content").apply { isAccessible = true }
                }.getOrNull()
            val content = contentField?.get(doc) as? String
            return content?.let { ByteArrayInputStream(it.toByteArray(Charsets.UTF_8)) }
        }

        private fun openOutputStreamForDoc(doc: DocumentFile): OutputStream {
            val streamMethod = runCatching { doc.javaClass.getMethod("openOutputStream") }.getOrNull()
            return if (streamMethod != null) {
                streamMethod.invoke(doc) as OutputStream
            } else {
                openDirectOutputStream(doc.uri)
            }
        }

        private fun openDirectOutputStream(uri: Uri): OutputStream =
            if (uri.scheme == "file") {
                val path = uri.path ?: uri.toString().removePrefix("file://")
                FileOutputStream(File(path))
            } else {
                context.contentResolver.openOutputStream(uri, "wt")
                    ?: context.contentResolver.openOutputStream(uri)
                    ?: throw IOException(
                        "Cannot open output stream for destination: $uri",
                    )
            }

        companion object {
            private val FRONTMATTER_REGEX = Regex("(?s)\\A---\\r?\\n(.*?)\\r?\\n---\\r?\\n?")
            private val KNOWN_FIELDS =
                setOf(
                    "id",
                    "title",
                    "type",
                    "created",
                    "modified",
                    "pinned",
                    "color",
                    "tags",
                    "history",
                    "checksum",
                    "app",
                )
        }
    }
