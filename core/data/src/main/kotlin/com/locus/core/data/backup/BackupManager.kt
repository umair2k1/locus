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
import com.locus.core.domain.notes.FlushTrigger
import com.locus.core.domain.notes.NoteFlushCoordinator
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BackupResult {
    data class Success(
        val outputUri: Uri,
        val fileCount: Int,
        val byteCount: Long,
    ) : BackupResult

    data class Failure(
        val cause: Throwable,
    ) : BackupResult
}

@Singleton
open class BackupManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val coordinator: NoteFlushCoordinator,
        private val treeUriStore: TreeUriStore,
        private val fileSource: SafNoteFileSource,
        private val dispatchers: DispatcherProvider,
    ) {
        @Suppress("TooGenericExceptionCaught")
        open suspend fun runBackup(destinationUri: Uri): BackupResult =
            withContext(dispatchers.io) {
                try {
                    // 1. Force flush all dirty buffers before zipping starts (N-11, N-1)
                    coordinator.forceFlushAll(FlushTrigger.PRE_BACKUP)

                    // 2. Resolve tree root
                    val rootDoc =
                        resolveRootDoc()
                            ?: return@withContext BackupResult.Failure(
                                IllegalStateException(
                                    "Cannot resolve root document for backup",
                                ),
                            )

                    // 3. Resolve destination output stream and target URI
                    val (outputStream, finalUri) = resolveDestinationOutputStream(destinationUri)

                    // 4. Zip the entire tree (including .locus/trash and .locus/history per D-3)
                    val (fileCount, byteCount) = zipTree(rootDoc, outputStream)

                    BackupResult.Success(
                        outputUri = finalUri,
                        fileCount = fileCount,
                        byteCount = byteCount,
                    )
                } catch (e: Exception) {
                    android.util.Log.e("BackupManager", "runBackup failed: ${e.message}", e)
                    BackupResult.Failure(e)
                }
            }

        private suspend fun resolveRootDoc(): DocumentFile? {
            val treeUri = treeUriStore.getTreeUri() ?: return null
            return fileSource.getRootDocument(treeUri)
                ?: if (treeUri.scheme == "file") {
                    DocumentFile.fromFile(File(treeUri.path ?: ""))
                } else {
                    null
                }
        }

        private fun zipTree(
            rootDoc: DocumentFile,
            outputStream: OutputStream,
        ): Pair<Int, Long> {
            val tracker = ZipTracker()
            ZipOutputStream(BufferedOutputStream(outputStream), Charsets.UTF_8).use { zipOut ->
                zipDirectory(rootDoc, "", zipOut, tracker)
            }
            return Pair(tracker.fileCount, tracker.byteCount)
        }

        private fun zipDirectory(
            dir: DocumentFile,
            currentPath: String,
            zipOut: ZipOutputStream,
            tracker: ZipTracker,
        ) {
            val children = dir.listFiles()
            for (child in children) {
                zipChild(child, currentPath, zipOut, tracker)
            }
        }

        private fun zipChild(
            child: DocumentFile,
            currentPath: String,
            zipOut: ZipOutputStream,
            tracker: ZipTracker,
        ) {
            val name = child.name ?: return
            val entryPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
            if (child.isDirectory) {
                zipOut.putNextEntry(ZipEntry("$entryPath/"))
                zipOut.closeEntry()
                zipDirectory(child, entryPath, zipOut, tracker)
            } else if (child.isFile) {
                zipFileEntry(child, entryPath, zipOut, tracker)
            }
        }

        private fun zipFileEntry(
            child: DocumentFile,
            entryPath: String,
            zipOut: ZipOutputStream,
            tracker: ZipTracker,
        ) {
            val entry = ZipEntry(entryPath)
            val lastMod = child.lastModified()
            if (lastMod > 0L) {
                entry.time = lastMod
            }
            zipOut.putNextEntry(entry)
            val bytesCopied =
                try {
                    openInputStreamForDoc(child).use { input -> input.copyTo(zipOut) }
                } catch (e: IOException) {
                    android.util.Log.e("BackupManager", "Failed to copy child: $entryPath", e)
                    throw e
                }
            zipOut.closeEntry()
            tracker.fileCount++
            tracker.byteCount += bytesCopied
        }

        private class ZipTracker(
            var fileCount: Int = 0,
            var byteCount: Long = 0L,
        )

        private fun resolveDestinationOutputStream(destinationUri: Uri): Pair<OutputStream, Uri> {
            val destDoc =
                if (destinationUri.scheme == "file") {
                    val file =
                        File(
                            destinationUri.path
                                ?: destinationUri.toString().removePrefix("file://"),
                        )
                    if (file.isDirectory) DocumentFile.fromFile(file) else null
                } else if (android.provider.DocumentsContract.isTreeUri(destinationUri)) {
                    DocumentFile.fromTreeUri(context, destinationUri)
                } else {
                    null
                }
            if (destDoc != null && destDoc.isDirectory) {
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val fileName = "locus-backup-$timestamp.zip"
                val createdFile =
                    destDoc.createFile("application/zip", fileName)
                        ?: throw IOException(
                            "Failed to create backup file $fileName in $destinationUri",
                        )
                val stream = openOutputStreamForDoc(createdFile)
                return Pair(stream, createdFile.uri)
            }

            return Pair(openDirectOutputStream(destinationUri), destinationUri)
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

        private fun openInputStreamForDoc(doc: DocumentFile): InputStream {
            val testStream = readTestDocumentStream(doc)
            return testStream ?: openDirectInputStream(doc.uri)
        }

        private fun readTestDocumentStream(doc: DocumentFile): InputStream? {
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

        private fun openDirectInputStream(uri: Uri): InputStream =
            if (uri.scheme == "file") {
                val path = uri.path ?: uri.toString().removePrefix("file://")
                FileInputStream(File(path))
            } else {
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open input stream for $uri")
            }
    }
