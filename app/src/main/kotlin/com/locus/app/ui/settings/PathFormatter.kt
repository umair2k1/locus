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

package com.locus.app.ui.settings

import android.net.Uri
import android.provider.DocumentsContract
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object PathFormatter {
    fun formatDisplayPath(uriString: String?): String? {
        val parsedUri =
            if (uriString.isNullOrBlank()) {
                null
            } else {
                runCatching { Uri.parse(uriString) }.getOrNull()
            }
        return when {
            uriString.isNullOrBlank() -> null
            parsedUri == null -> uriString
            DocumentsContract.isTreeUri(parsedUri) -> formatTreeUri(parsedUri)
            DocumentsContract.isDocumentUri(null, parsedUri) -> formatDocumentUri(parsedUri)
            parsedUri.authority == EXTERNAL_STORAGE_AUTHORITY -> formatExternalStorageUri(parsedUri)
            parsedUri.authority == DOWNLOADS_AUTHORITY -> formatDownloadsUri(parsedUri)
            parsedUri.scheme == "file" ->
                formatFilePath(parsedUri.path ?: uriString.removePrefix("file://"))
            else -> formatFallback(uriString)
        }
    }

    private fun formatTreeUri(uri: Uri): String {
        val treeDocId =
            runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
                ?: uri.path?.substringAfter("/tree/") ?: return uri.toString()
        return formatDocId(treeDocId)
    }

    private fun formatDocumentUri(uri: Uri): String {
        val docId =
            runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
                ?: uri.path?.substringAfter("/document/") ?: return uri.toString()
        return formatDocId(docId)
    }

    private fun formatExternalStorageUri(uri: Uri): String {
        val rawPath = uri.path ?: return uri.toString()
        val id =
            when {
                rawPath.contains("/tree/") -> rawPath.substringAfter("/tree/")
                rawPath.contains("/document/") -> rawPath.substringAfter("/document/")
                else -> rawPath.trim('/')
            }
        return formatDocId(id)
    }

    private fun formatDownloadsUri(uri: Uri): String {
        val rawPath = decode(uri.path ?: return uri.toString())
        return when {
            rawPath.contains(PRIMARY_STORAGE_PREFIX) ->
                rawPath.substringAfter(PRIMARY_STORAGE_PREFIX).trim('/')
            rawPath.contains("/storage/") -> {
                formatSdCardStoragePath(rawPath.substringAfter("/storage/").trim('/'))
            }
            rawPath.contains("raw:") -> rawPath.substringAfter("raw:").trim('/')
            else -> rawPath.trim('/')
        }
    }

    private fun formatFilePath(path: String): String {
        val decoded = decode(path)
        return when {
            decoded.startsWith(PRIMARY_STORAGE_PREFIX) ->
                decoded.removePrefix(PRIMARY_STORAGE_PREFIX).trim('/')
            decoded.startsWith("/sdcard/") -> decoded.removePrefix("/sdcard/").trim('/')
            decoded.startsWith("/storage/") -> {
                formatSdCardStoragePath(decoded.removePrefix("/storage/").trim('/'))
            }
            else -> decoded.trim('/')
        }
    }

    private fun formatSdCardStoragePath(subPath: String): String {
        val parts = subPath.split('/', limit = 2)
        return if (parts.size == 2) "SD card/${parts[1]}" else "SD card/$subPath"
    }

    private fun formatDocId(rawId: String): String {
        val cleanPath = decode(rawId).removePrefix("raw:")
        return when {
            cleanPath.startsWith(PRIMARY_STORAGE_PREFIX) ->
                cleanPath.removePrefix(PRIMARY_STORAGE_PREFIX).trim('/')
            cleanPath.startsWith("/sdcard/") -> cleanPath.removePrefix("/sdcard/").trim('/')
            cleanPath.startsWith("/storage/") ->
                formatSdCardStoragePath(cleanPath.removePrefix("/storage/").trim('/'))
            else -> formatVolumePath(cleanPath)
        }
    }

    private fun formatVolumePath(path: String): String {
        val colonIndex = path.indexOf(':')
        if (colonIndex == -1) return path.trim('/')
        val volume = path.substring(0, colonIndex)
        val relPath = path.substring(colonIndex + 1).trim('/')
        return when {
            volume.equals("primary", ignoreCase = true) -> relPath.ifEmpty { "Internal storage" }
            relPath.isEmpty() -> "SD card ($volume)"
            else -> "SD card/$relPath"
        }
    }

    private fun formatFallback(uriString: String): String {
        val decoded = decode(uriString)
        val primaryIdx = decoded.indexOf("primary:")
        return if (primaryIdx >= 0) {
            decoded.substring(primaryIdx + "primary:".length).trim('/')
        } else {
            decoded
        }
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
            .getOrDefault(value)

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
    private const val PRIMARY_STORAGE_PREFIX = "/storage/emulated/0/"
}
