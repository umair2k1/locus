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
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface SafNoteFileSource {
    fun listMarkdownFiles(treeUri: Uri): List<DocumentFile>

    fun readText(doc: DocumentFile): String

    fun writeText(
        doc: DocumentFile,
        text: String,
    ) {}

    fun moveDocument(
        source: DocumentFile,
        targetDir: DocumentFile,
    ): DocumentFile = source

    fun renameDocument(
        doc: DocumentFile,
        newName: String,
    ): DocumentFile {
        doc.renameTo(newName)
        return doc
    }

    fun listFolders(treeUri: Uri): List<String>

    fun getRootDocument(treeUri: Uri): DocumentFile?
}

@Singleton
class AndroidSafNoteFileSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SafNoteFileSource {
        override fun getRootDocument(treeUri: Uri): DocumentFile? =
            if (DocumentsContract.isTreeUri(treeUri)) {
                DocumentFile.fromTreeUri(context, treeUri)
            } else if (treeUri.scheme == "file") {
                val path = treeUri.path ?: treeUri.toString().removePrefix("file://")
                val file = java.io.File(path)
                if (!file.exists()) file.mkdirs()
                DocumentFile.fromFile(file)
            } else {
                null
            }

        override fun listMarkdownFiles(treeUri: Uri): List<DocumentFile> {
            val root = getRootDocument(treeUri) ?: return emptyList()
            return collectMarkdownFiles(root)
        }

        private fun collectMarkdownFiles(dir: DocumentFile): List<DocumentFile> {
            val files = mutableListOf<DocumentFile>()
            val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            for (child in children) {
                val name = child.name ?: ""
                when {
                    child.isDirectory && !name.startsWith(".") ->
                        files.addAll(collectMarkdownFiles(child))
                    child.isFile && name.endsWith(".md", ignoreCase = true) -> files.add(child)
                }
            }
            return files
        }

        override fun readText(doc: DocumentFile): String {
            if (doc.uri.scheme == "file") {
                val path = doc.uri.path ?: doc.uri.toString().removePrefix("file://")
                return java.io.File(path).readText(Charsets.UTF_8)
            }
            return context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
                ?: throw IOException("Could not open input stream for ${doc.uri}")
        }

        override fun listFolders(treeUri: Uri): List<String> {
            val root = getRootDocument(treeUri) ?: return emptyList()
            val folders = mutableListOf<String>()
            collectFolders(root, "", folders)
            return folders.sorted()
        }

        private fun collectFolders(
            dir: DocumentFile,
            currentPath: String,
            out: MutableList<String>,
        ) {
            val children = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            for (child in children) {
                val name = child.name ?: ""
                if (child.isDirectory && !name.startsWith(".")) {
                    val childPath = if (currentPath.isEmpty()) name else "$currentPath/$name"
                    out.add(childPath)
                    collectFolders(child, childPath, out)
                }
            }
        }

        override fun writeText(
            doc: DocumentFile,
            text: String,
        ) {
            val contentField =
                runCatching {
                    doc.javaClass.getDeclaredField("content").apply { isAccessible = true }
                }.getOrNull()
            if (contentField != null) {
                runCatching {
                    contentField.set(doc, text)
                    return
                }
            }
            if (doc.uri.scheme == "file") {
                val path = doc.uri.path ?: doc.uri.toString().removePrefix("file://")
                java.io.File(path).writeText(text, Charsets.UTF_8)
                return
            }

            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { stream ->
                stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(text)
                    writer.flush()
                }
            }
                ?: throw IOException("Could not open output stream for ${doc.uri}")
        }

        override fun moveDocument(
            source: DocumentFile,
            targetDir: DocumentFile,
        ): DocumentFile {
            if (tryMoveReflective(source, targetDir)) return source
            val movedDoc = tryMoveContract(source, targetDir)
            return movedDoc ?: moveCopyFallback(source, targetDir)
        }

        private fun tryMoveReflective(
            source: DocumentFile,
            targetDir: DocumentFile,
        ): Boolean {
            val moveToMethod =
                runCatching {
                    source.javaClass.methods.firstOrNull {
                        it.name == "moveTo" && it.parameterTypes.size == 1
                    }
                }.getOrNull()
            return if (moveToMethod != null) {
                runCatching { moveToMethod.invoke(source, targetDir) }
                true
            } else {
                false
            }
        }

        private fun tryMoveContract(
            source: DocumentFile,
            targetDir: DocumentFile,
        ): DocumentFile? {
            val parent = source.parentFile ?: return null
            return runCatching {
                val movedUri =
                    DocumentsContract.moveDocument(
                        context.contentResolver,
                        source.uri,
                        parent.uri,
                        targetDir.uri,
                    )
                movedUri?.let { uri ->
                    DocumentFile.fromSingleUri(context, uri)
                        ?: targetDir.findFile(source.name.orEmpty())
                }
            }.getOrNull()
        }

        private fun moveCopyFallback(
            source: DocumentFile,
            targetDir: DocumentFile,
        ): DocumentFile {
            val targetName = source.name ?: "note.md"
            val newDoc =
                targetDir.findFile(targetName)
                    ?: targetDir.createFile(source.type ?: "text/markdown", targetName)
                    ?: throw IOException(
                        "Failed to create file '$targetName' in target directory",
                    )
            writeText(newDoc, readText(source))
            source.delete()
            return newDoc
        }

        override fun renameDocument(
            doc: DocumentFile,
            newName: String,
        ): DocumentFile {
            if (doc.renameTo(newName)) return doc
            val renamedUri =
                runCatching {
                    DocumentsContract.renameDocument(
                        context.contentResolver,
                        doc.uri,
                        newName,
                    )
                }.getOrNull()
            return (renamedUri?.let { DocumentFile.fromSingleUri(context, it) }) ?: doc
        }
    }
