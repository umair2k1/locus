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

package com.locus.core.ai.llama

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.locus.core.ai.catalog.CatalogRepository
import com.locus.core.domain.models.ModelStorageStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.modelDownloadDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "locus_model_downloads")

private data class DownloadSpec(
    val url: String,
    val filename: String,
    val tempFile: File,
    val targetFile: File,
    val expectedSha256: String,
    val expectedSize: Long,
    val progressListener: (suspend (bytesRead: Long, totalBytes: Long) -> Unit)?,
)

@Suppress("TooManyFunctions")
@Singleton
class ModelDownloader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val okHttpClient: OkHttpClient,
        private val workManager: WorkManager? = null,
        private val catalogRepository: CatalogRepository? = null,
    ) {
        companion object {
            const val DEFAULT_HF_REPO = "ggml-org/embeddinggemma-300M-GGUF"
            const val DEFAULT_MODEL_FILENAME = "embeddinggemma-300M-Q8_0.gguf"
            const val TAG_MODEL_DOWNLOAD = "tag_model_download"
            private const val BUFFER_SIZE = 64 * 1024
            private const val COMMIT_INTERVAL_BYTES = 512 * 1024L
            private const val HTTP_OK = 200
            private const val HTTP_PARTIAL_CONTENT = 206
            private const val HTTP_RANGE_NOT_SATISFIABLE = 416
            private const val SHA256_BYTE_BUFFER_SIZE = 64 * 1024

            fun workName(filename: String): String = "download_model_$filename"

            fun modelTag(filename: String): String = "tag_model_$filename"

            fun offsetKey(filename: String) = longPreferencesKey("offset_$filename")

            fun computeFileSha256(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(SHA256_BYTE_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        digest.update(buffer, 0, read)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            }

            fun parseTotalBytesFromContentRange(header: String?): Long? {
                if (header.isNullOrBlank()) return null
                val slashIndex = header.lastIndexOf('/')
                return if (slashIndex != -1 && slashIndex + 1 < header.length) {
                    header.substring(slashIndex + 1).trim().toLongOrNull()
                } else {
                    null
                }
            }
        }

        suspend fun getCommittedOffset(filename: String): Long =
            withContext(Dispatchers.IO) {
                context.modelDownloadDataStore.data.first()[offsetKey(filename)] ?: 0L
            }

        suspend fun saveCommittedOffset(
            filename: String,
            offset: Long,
        ) = withContext(Dispatchers.IO) {
            context.modelDownloadDataStore.edit { prefs -> prefs[offsetKey(filename)] = offset }
        }

        suspend fun clearCommittedOffset(filename: String) =
            withContext(Dispatchers.IO) {
                context.modelDownloadDataStore.edit { prefs -> prefs.remove(offsetKey(filename)) }
            }

        fun getModelsDirectory(): File {
            val dir = File(context.filesDir, "models")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

        fun getModelFile(filename: String = DEFAULT_MODEL_FILENAME): File {
            val dir = getModelsDirectory()
            return File(dir, filename)
        }

        suspend fun downloadModelIfMissing(
            repo: String = DEFAULT_HF_REPO,
            filename: String = DEFAULT_MODEL_FILENAME,
            expectedSha256: String? = null,
        ): File =
            withContext(Dispatchers.IO) {
                val targetFile = getModelFile(filename)
                if (targetFile.exists() && targetFile.length() > 0L) {
                    val requiredSha256 =
                        if (!expectedSha256.isNullOrBlank()) {
                            expectedSha256
                        } else {
                            val catalog = catalogRepository?.current()?.first()
                            val entry =
                                catalog?.let { c ->
                                    (c.chat + c.utility + c.embeddings).find {
                                        it.filename.equals(filename, ignoreCase = true) ||
                                            (
                                                it.repo.equals(repo, ignoreCase = true) &&
                                                    it.filename.equals(
                                                        filename,
                                                        ignoreCase = true,
                                                    )
                                            )
                                    }
                                }
                            entry?.sha256?.takeIf { it.isNotBlank() }
                        }

                    if (!requiredSha256.isNullOrBlank()) {
                        val actualSha256 = computeFileSha256(targetFile)
                        if (!actualSha256.equals(requiredSha256, ignoreCase = true)) {
                            targetFile.delete()
                            clearCommittedOffset(filename)
                            error(
                                "Checksum mismatch for existing model $filename: " +
                                    "expected $requiredSha256 but got $actualSha256",
                            )
                        }
                    }
                    return@withContext targetFile
                }

                val resolvedSha256 = resolveExpectedSha256(repo, filename, expectedSha256)
                val downloadUrl = "https://huggingface.co/$repo/resolve/main/$filename"
                downloadToFileResumable(
                    url = downloadUrl,
                    filename = filename,
                    expectedSha256 = resolvedSha256,
                )
            }

        private suspend fun resolveExpectedSha256(
            repo: String,
            filename: String,
            explicitSha256: String?,
        ): String {
            if (!explicitSha256.isNullOrBlank()) {
                return explicitSha256
            }
            val catalog = catalogRepository?.current()?.first()
            val entry =
                catalog?.let { c ->
                    (c.chat + c.utility + c.embeddings).find {
                        it.filename.equals(filename, ignoreCase = true) ||
                            (
                                it.repo.equals(repo, ignoreCase = true) &&
                                    it.filename.equals(filename, ignoreCase = true)
                            )
                    }
                }
            return entry?.sha256?.takeIf { it.isNotBlank() } ?: fetchExpectedSha256(repo, filename)
        }

        suspend fun loadModel(
            runtime: LlamaRuntime,
            filename: String = DEFAULT_MODEL_FILENAME,
            repo: String = DEFAULT_HF_REPO,
            kind: ModelKind = ModelKind.EMBEDDING,
        ): Result<Unit> =
            runCatching {
                val modelFile = downloadModelIfMissing(repo = repo, filename = filename)
                val loadResult =
                    if (kind == ModelKind.EMBEDDING) {
                        runtime.loadModel(modelFile.absolutePath)
                    } else {
                        runtime.loadModel(modelFile.absolutePath, kind)
                    }
                check(loadResult.isSuccess) {
                    "Failed to load model from ${modelFile.absolutePath}: ${loadResult.exceptionOrNull()?.message}"
                }
            }

        fun fetchExpectedSha256(
            repo: String,
            filename: String,
        ): String {
            val treeUrl = "https://huggingface.co/api/models/$repo/tree/main"
            val request = Request.Builder().url(treeUrl).build()
            val response = okHttpClient.newCall(request).execute()
            check(response.isSuccessful) {
                "Failed to fetch model tree from $treeUrl: HTTP ${response.code}"
            }
            val bodyString = response.body?.string() ?: error("Empty response from $treeUrl")
            val jsonArray = JSONArray(bodyString)
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                val path = item.optString("path")
                if (path.equals(filename, ignoreCase = true)) {
                    val lfs = item.optJSONObject("lfs")
                    val oid = lfs?.optString("oid") ?: item.optString("oid")
                    if (!oid.isNullOrBlank()) {
                        return oid
                    }
                }
            }
            error("Model $filename not found in HF tree listing at $treeUrl")
        }

        suspend fun downloadToFileResumable(
            url: String,
            filename: String,
            expectedSha256: String,
            expectedSize: Long = 0L,
            progressListener: (suspend (bytesRead: Long, totalBytes: Long) -> Unit)? = null,
        ): File =
            withContext(Dispatchers.IO) {
                val targetFile = getModelFile(filename)
                if (targetFile.exists() && targetFile.length() > 0L) {
                    if (expectedSha256.isNotBlank()) {
                        val actualSha256 = computeFileSha256(targetFile)
                        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                            targetFile.delete()
                            clearCommittedOffset(filename)
                            error(
                                "Checksum mismatch for existing model $filename: " +
                                    "expected $expectedSha256 but got $actualSha256",
                            )
                        }
                    }
                    return@withContext targetFile
                }

                val parentDir = targetFile.parentFile ?: context.filesDir
                if (!parentDir.exists()) {
                    parentDir.mkdirs()
                }
                val tempFile = File(parentDir, "$filename.tmp")

                var offset = getCommittedOffset(filename)
                if (!tempFile.exists()) {
                    offset = 0L
                    saveCommittedOffset(filename, 0L)
                } else if (tempFile.length() < offset) {
                    offset = tempFile.length()
                    saveCommittedOffset(filename, offset)
                } else if (tempFile.length() > offset) {
                    RandomAccessFile(tempFile, "rw").use { it.setLength(offset) }
                }

                val spec =
                    DownloadSpec(
                        url = url,
                        filename = filename,
                        tempFile = tempFile,
                        targetFile = targetFile,
                        expectedSha256 = expectedSha256,
                        expectedSize = expectedSize,
                        progressListener = progressListener,
                    )
                executeDownloadLoop(spec, offset)
            }

        private suspend fun executeDownloadLoop(
            spec: DownloadSpec,
            initialOffset: Long,
        ): File {
            var offset = initialOffset
            val requestBuilder = Request.Builder().url(spec.url)
            if (offset > 0L) {
                requestBuilder.header("Range", "bytes=$offset-")
            }

            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                response.close()
                return handleRangeNotSatisfiable(spec)
            }

            val (appendMode, totalBytes) =
                when (response.code) {
                    HTTP_PARTIAL_CONTENT -> {
                        val contentRange = response.header("Content-Range")
                        val parsedTotal = parseTotalBytesFromContentRange(contentRange)
                        val total =
                            parsedTotal
                                ?: if (spec.expectedSize > 0L) {
                                    spec.expectedSize
                                } else {
                                    offset + (response.body?.contentLength() ?: 0L)
                                }
                        true to total
                    }
                    HTTP_OK -> {
                        offset = 0L
                        saveCommittedOffset(spec.filename, 0L)
                        if (spec.tempFile.exists()) {
                            RandomAccessFile(spec.tempFile, "rw").use { it.setLength(0L) }
                        }
                        val bodyLength = response.body?.contentLength() ?: 0L
                        val total = if (bodyLength > 0L) bodyLength else spec.expectedSize
                        false to total
                    }
                    else -> {
                        throw IOException(
                            "Failed to download model from ${spec.url}: HTTP ${response.code}",
                        )
                    }
                }

            return streamAndVerify(
                spec = spec,
                response = response,
                startOffset = offset,
                totalBytes = totalBytes,
                appendMode = appendMode,
            )
        }

        private suspend fun handleRangeNotSatisfiable(spec: DownloadSpec): File {
            saveCommittedOffset(spec.filename, 0L)
            if (spec.tempFile.exists()) {
                RandomAccessFile(spec.tempFile, "rw").use { it.setLength(0L) }
            }
            val retryRequest = Request.Builder().url(spec.url).build()
            val retryResponse = okHttpClient.newCall(retryRequest).execute()
            check(retryResponse.isSuccessful) {
                "Failed to download model from ${spec.url} after range reset: HTTP ${retryResponse.code}"
            }
            val bodyLength = retryResponse.body?.contentLength() ?: 0L
            val total = if (bodyLength > 0L) bodyLength else spec.expectedSize
            return streamAndVerify(
                spec = spec,
                response = retryResponse,
                startOffset = 0L,
                totalBytes = total,
                appendMode = false,
            )
        }

        private suspend fun copyStreamWithProgress(
            spec: DownloadSpec,
            input: InputStream,
            output: OutputStream,
            startOffset: Long,
            totalBytes: Long,
        ): Long {
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesWritten = startOffset
            var lastCommitBytes = bytesWritten
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                bytesWritten += read
                if (bytesWritten - lastCommitBytes >= COMMIT_INTERVAL_BYTES) {
                    output.flush()
                    saveCommittedOffset(spec.filename, bytesWritten)
                    lastCommitBytes = bytesWritten
                    spec.progressListener?.invoke(bytesWritten, totalBytes)
                }
            }
            output.flush()
            return bytesWritten
        }

        private suspend fun streamAndVerify(
            spec: DownloadSpec,
            response: Response,
            startOffset: Long,
            totalBytes: Long,
            appendMode: Boolean,
        ): File {
            val responseBody = response.body ?: error("Empty response body")
            spec.progressListener?.invoke(startOffset, totalBytes)

            val finalOffset =
                response.use {
                    responseBody.byteStream().use { input ->
                        FileOutputStream(spec.tempFile, appendMode).use { output ->
                            copyStreamWithProgress(
                                spec = spec,
                                input = input,
                                output = output,
                                startOffset = startOffset,
                                totalBytes = totalBytes,
                            )
                        }
                    }
                }

            saveCommittedOffset(spec.filename, finalOffset)
            spec.progressListener?.invoke(finalOffset, totalBytes)

            val actualSha256 = computeFileSha256(spec.tempFile)
            val matches =
                spec.expectedSha256.isBlank() ||
                    actualSha256.equals(spec.expectedSha256, ignoreCase = true)
            if (!matches) {
                if (spec.tempFile.exists()) spec.tempFile.delete()
                if (spec.targetFile.exists()) spec.targetFile.delete()
                clearCommittedOffset(spec.filename)
            }
            check(matches) {
                "Checksum mismatch for ${spec.filename}: expected ${spec.expectedSha256} but got $actualSha256"
            }

            promoteTempFile(spec.tempFile, spec.targetFile)
            clearCommittedOffset(spec.filename)
            return spec.targetFile
        }

        private fun promoteTempFile(
            tempFile: File,
            targetFile: File,
        ) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        }

        fun getDownloadedModels(): List<File> {
            val dir = getModelsDirectory()
            return dir
                .listFiles { file ->
                    file.isFile && !file.name.endsWith(".tmp") && file.length() > 0L
                }?.sortedBy { it.name }
                ?: emptyList()
        }

        suspend fun deleteModel(filename: String): Boolean =
            withContext(Dispatchers.IO) {
                val targetFile = getModelFile(filename)
                val tempFile = File(targetFile.parentFile, "$filename.tmp")
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                clearCommittedOffset(filename)
                if (targetFile.exists()) {
                    targetFile.delete()
                } else {
                    true
                }
            }

        fun getStorageStats(): ModelStorageStats {
            val dir = getModelsDirectory()
            val models = getDownloadedModels()
            val totalUsedBytes = models.sumOf { it.length() }
            val freeBytes = dir.usableSpace.coerceAtLeast(0L)
            val totalDeviceBytes = dir.totalSpace.coerceAtLeast(0L)
            return ModelStorageStats(
                totalUsedBytes = totalUsedBytes,
                freeBytes = freeBytes,
                totalDeviceBytes = totalDeviceBytes,
            )
        }

        fun enqueueDownloadWork(
            repo: String,
            filename: String,
            sha256: String,
            expectedSize: Long = 0L,
        ): UUID {
            val inputData =
                Data
                    .Builder()
                    .putString(ModelDownloadWorker.KEY_REPO, repo)
                    .putString(ModelDownloadWorker.KEY_FILENAME, filename)
                    .putString(ModelDownloadWorker.KEY_SHA256, sha256)
                    .putLong(ModelDownloadWorker.KEY_EXPECTED_SIZE, expectedSize)
                    .build()

            val request =
                OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                    .setInputData(inputData)
                    .addTag(TAG_MODEL_DOWNLOAD)
                    .addTag(modelTag(filename))
                    .build()

            val wm = workManager ?: WorkManager.getInstance(context)
            wm.enqueueUniqueWork(
                workName(filename),
                ExistingWorkPolicy.KEEP,
                request,
            )
            return request.id
        }

        fun cancelDownloadWork(workId: String) {
            val wm = workManager ?: WorkManager.getInstance(context)
            wm.cancelWorkById(UUID.fromString(workId))
        }
    }
