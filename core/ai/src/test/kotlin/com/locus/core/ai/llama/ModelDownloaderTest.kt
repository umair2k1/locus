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
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class ModelDownloaderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val modelsDir = File(context.filesDir, "models")
        if (modelsDir.exists()) {
            modelsDir.deleteRecursively()
        }
    }

    @Test
    fun downloadModelIfMissing_whenFileAlreadyExists_returnsExistingFileWithoutNetworkCalls() =
        runTest {
            val modelsDir = File(context.filesDir, "models")
            modelsDir.mkdirs()
            val existingFile = File(modelsDir, ModelDownloader.DEFAULT_MODEL_FILENAME)
            existingFile.writeText("pre-existing model bytes")

            val failingClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        Interceptor {
                            error(
                                "Network call attempted when model file already exists!",
                            )
                        },
                    ).build()

            val downloader = ModelDownloader(context, failingClient)
            val resultFile = downloader.downloadModelIfMissing()

            assertEquals(existingFile.absolutePath, resultFile.absolutePath)
            assertTrue(resultFile.exists())
            assertEquals("pre-existing model bytes", resultFile.readText())
        }

    @Test
    fun downloadModelIfMissing_downloadsAndVerifiesChecksumSuccessfully() =
        runTest {
            val modelContent = "simulated gguf model binary payload"
            val digest = MessageDigest.getInstance("SHA-256")
            val sha256 = digest.digest(modelContent.toByteArray()).joinToString("") { "%02x".format(it) }

            val treeJson =
                """
                [
                  {
                    "type": "file",
                    "path": "${ModelDownloader.DEFAULT_MODEL_FILENAME}",
                    "lfs": {
                      "oid": "$sha256",
                      "size": ${modelContent.length}
                    }
                  }
                ]
                """.trimIndent()

            val mockClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            val url = chain.request().url.toString()
                            if (url.contains("/tree/")) {
                                Response
                                    .Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body(
                                        treeJson.toResponseBody(
                                            "application/json".toMediaType(),
                                        ),
                                    ).build()
                            } else {
                                Response
                                    .Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body(
                                        modelContent.toResponseBody(
                                            "application/octet-stream".toMediaType(),
                                        ),
                                    ).build()
                            }
                        },
                    ).build()

            val downloader = ModelDownloader(context, mockClient)
            val downloadedFile = downloader.downloadModelIfMissing()

            assertTrue(downloadedFile.exists())
            assertEquals(modelContent, downloadedFile.readText())
        }

    @Test
    fun downloadModelIfMissing_checksumMismatch_throwsExceptionAndDeletesTempFile() =
        runTest {
            val modelContent = "actual downloaded content"
            val fakeSha256 = "0000000000000000000000000000000000000000000000000000000000000000"

            val treeJson =
                """
                [
                  {
                    "type": "file",
                    "path": "${ModelDownloader.DEFAULT_MODEL_FILENAME}",
                    "lfs": {
                      "oid": "$fakeSha256",
                      "size": ${modelContent.length}
                    }
                  }
                ]
                """.trimIndent()

            val mockClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            val url = chain.request().url.toString()
                            if (url.contains("/tree/")) {
                                Response
                                    .Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body(
                                        treeJson.toResponseBody(
                                            "application/json".toMediaType(),
                                        ),
                                    ).build()
                            } else {
                                Response
                                    .Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body(
                                        modelContent.toResponseBody(
                                            "application/octet-stream".toMediaType(),
                                        ),
                                    ).build()
                            }
                        },
                    ).build()

            val downloader = ModelDownloader(context, mockClient)
            val result = runCatching { downloader.downloadModelIfMissing() }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            val targetFile = downloader.getModelFile()
            assertFalse(targetFile.exists())
            val tempFile = File(targetFile.parentFile, "${ModelDownloader.DEFAULT_MODEL_FILENAME}.tmp")
            assertFalse(tempFile.exists())
        }

    @Test
    fun downloadModelIfMissing_missingInTreeListing_throwsException() =
        runTest {
            val treeJson = "[]"
            val mockClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                    treeJson.toResponseBody(
                                        "application/json".toMediaType(),
                                    ),
                                ).build()
                        },
                    ).build()

            val downloader = ModelDownloader(context, mockClient)
            val result = runCatching { downloader.downloadModelIfMissing() }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
        }

    @Test
    fun downloadToFileResumable_resumesFromCommittedOffsetWithRangeHeader() =
        runTest {
            val filename = "resumable-test.gguf"
            val part1 = "part1-"
            val part2 = "part2-completed"
            val fullContent = part1 + part2
            val fullSha256 =
                MessageDigest.getInstance("SHA-256").digest(fullContent.toByteArray()).joinToString(
                    "",
                ) { "%02x".format(it) }

            var capturedRangeHeader: String? = null
            val mockClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            val request = chain.request()
                            capturedRangeHeader = request.header("Range")
                            Response
                                .Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(206)
                                .message("Partial Content")
                                .header(
                                    "Content-Range",
                                    "bytes 6-${fullContent.length - 1}/${fullContent.length}",
                                ).body(
                                    part2.toResponseBody(
                                        "application/octet-stream".toMediaType(),
                                    ),
                                ).build()
                        },
                    ).build()

            val downloader = ModelDownloader(context, mockClient)
            val targetFile = downloader.getModelFile(filename)
            val tempFile = File(targetFile.parentFile, "$filename.tmp")
            targetFile.parentFile?.mkdirs()
            tempFile.writeText(part1)
            downloader.saveCommittedOffset(filename, part1.length.toLong())

            val resultFile =
                downloader.downloadToFileResumable(
                    url = "https://huggingface.co/test/model/resolve/main/$filename",
                    filename = filename,
                    expectedSha256 = fullSha256,
                )

            assertEquals("bytes=${part1.length}-", capturedRangeHeader)
            assertTrue(resultFile.exists())
            assertEquals(fullContent, resultFile.readText())
            assertFalse(tempFile.exists())
            assertEquals(0L, downloader.getCommittedOffset(filename))
        }

    @Test
    fun downloadToFileResumable_corruptedFile_failsVerificationAndDeletesFiles() =
        runTest {
            val filename = "corrupted-test.gguf"
            val validContent = "original clean content"
            val corruptedContent = "original clean xontent" // flipped byte 'c' -> 'x'
            val validSha256 =
                MessageDigest.getInstance("SHA-256").digest(validContent.toByteArray()).joinToString(
                    "",
                ) { "%02x".format(it) }

            val mockClient =
                OkHttpClient
                    .Builder()
                    .addInterceptor(
                        Interceptor { chain ->
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(
                                    corruptedContent.toResponseBody(
                                        "application/octet-stream".toMediaType(),
                                    ),
                                ).build()
                        },
                    ).build()

            val downloader = ModelDownloader(context, mockClient)
            val result =
                runCatching {
                    downloader.downloadToFileResumable(
                        url = "https://huggingface.co/test/model/resolve/main/$filename",
                        filename = filename,
                        expectedSha256 = validSha256,
                    )
                }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            val targetFile = downloader.getModelFile(filename)
            val tempFile = File(targetFile.parentFile, "$filename.tmp")
            assertFalse("Target file must not be left loadable on corruption", targetFile.exists())
            assertFalse("Temp file must be deleted on corruption", tempFile.exists())
            assertEquals(0L, downloader.getCommittedOffset(filename))
        }

    @Test
    fun storageStats_and_deleteModel_worksCorrectly() =
        runTest {
            val downloader = ModelDownloader(context, OkHttpClient())
            val file1 = downloader.getModelFile("model1.gguf")
            val file2 = downloader.getModelFile("model2.gguf")
            file1.writeText("12345") // 5 bytes
            file2.writeText("1234567890") // 10 bytes

            val models = downloader.getDownloadedModels()
            assertEquals(2, models.size)

            val stats = downloader.getStorageStats()
            assertEquals(15L, stats.totalUsedBytes)

            val deleted = downloader.deleteModel("model1.gguf")
            assertTrue(deleted)
            assertFalse(file1.exists())

            val remainingModels = downloader.getDownloadedModels()
            assertEquals(1, remainingModels.size)
            assertEquals("model2.gguf", remainingModels[0].name)
            assertEquals(10L, downloader.getStorageStats().totalUsedBytes)
        }
}
