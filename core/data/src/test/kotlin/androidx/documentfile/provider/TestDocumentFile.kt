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

package androidx.documentfile.provider

import android.net.Uri

class TestDocumentFile(
    var parent: DocumentFile?,
    docName: String,
    private val isDir: Boolean,
    var content: String = "",
    val children: MutableList<DocumentFile> = mutableListOf(),
    private val docUri: Uri =
        Uri.parse("content://test.provider/document/${System.nanoTime()}"),
) : DocumentFile(parent) {
    var contentBytes: ByteArray? = null
    private var currentName: String = docName

    override fun getParentFile(): DocumentFile? = parent

    override fun createFile(
        mimeType: String,
        displayName: String,
    ): DocumentFile? {
        val child =
            TestDocumentFile(
                parent = this,
                docName = displayName,
                isDir = false,
            )
        children.add(child)
        return child
    }

    override fun createDirectory(displayName: String): DocumentFile? {
        val child =
            TestDocumentFile(
                parent = this,
                docName = displayName,
                isDir = true,
            )
        children.add(child)
        return child
    }

    override fun getUri(): Uri = docUri

    override fun getName(): String = currentName

    override fun getType(): String? = if (isDir) null else "text/markdown"

    override fun isDirectory(): Boolean = isDir

    override fun isFile(): Boolean = !isDir

    override fun isVirtual(): Boolean = false

    override fun lastModified(): Long = 1726488000000L

    override fun length(): Long = content.length.toLong()

    override fun canRead(): Boolean = true

    override fun canWrite(): Boolean = true

    override fun delete(): Boolean {
        (parent as? TestDocumentFile)?.children?.remove(this)
        return true
    }

    override fun exists(): Boolean = true

    override fun listFiles(): Array<DocumentFile> = children.toTypedArray()

    override fun renameTo(displayName: String): Boolean {
        currentName = displayName
        return true
    }

    fun moveTo(newParent: TestDocumentFile) {
        (parent as? TestDocumentFile)?.children?.remove(this)
        parent = newParent
        newParent.children.add(this)
    }

    fun openOutputStream(): java.io.OutputStream =
        object : java.io.ByteArrayOutputStream() {
            override fun close() {
                super.close()
                contentBytes = toByteArray()
                content = String(contentBytes!!, Charsets.UTF_8)
            }
        }
}
