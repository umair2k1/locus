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

package com.locus.core.ai.catalog

import android.content.Context
import com.locus.core.ai.llama.LlamaRuntime
import com.locus.core.ai.llama.ModelDownloader
import com.locus.core.ai.llama.ModelKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CatalogIntegrityTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private val testDispatcher = UnconfinedTestDispatcher()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        server = MockWebServer()
        server.start()
        okHttpClient = OkHttpClient()
        cleanModelsDirectory()
    }

    @After
    fun tearDown() {
        server.shutdown()
        cleanModelsDirectory()
    }

    private fun cleanModelsDirectory() {
        val dir = File(context.filesDir, "models")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun computeSha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray(Charsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }
    }

    private fun createTestCatalogRepository(catalog: ModelCatalog): CatalogRepository {
        val catalogJson = json.encodeToString(catalog)
        return CatalogRepository(
            context = context,
            okHttpClient = okHttpClient,
            ioDispatcher = testDispatcher,
            remoteUrl = server.url("/models.json").toString(),
            dataStore = null,
            assetLoader = { ByteArrayInputStream(catalogJson.toByteArray(Charsets.UTF_8)) },
        )
    }

    @Test
    fun existingModel_withChecksumMismatchAgainstCatalogEntry_isRejectedBeforeLlamaRuntimeLoadModel() =
        runTest {
            val validContent = "valid-model-content-bytes"
            val validSha256 = computeSha256(validContent)
            val corruptedContent = "corrupted-or-tampered-bytes"

            val testCatalog =
                ModelCatalog(
                    schemaVersion = 1,
                    embeddings =
                        listOf(
                            CatalogEntry(
                                id = "test-model-id",
                                name = "Test Model",
                                repo = "test-org/test-model",
                                filename = "test-model.gguf",
                                sha256 = validSha256,
                                sizeBytes = validContent.length.toLong(),
                            ),
                        ),
                )
            val catalogRepo = createTestCatalogRepository(testCatalog)
            val downloader =
                ModelDownloader(
                    context = context,
                    okHttpClient = okHttpClient,
                    catalogRepository = catalogRepo,
                )

            val modelsDir = File(context.filesDir, "models")
            modelsDir.mkdirs()
            val existingFile = File(modelsDir, "test-model.gguf")
            existingFile.writeText(corruptedContent)

            var loadModelInvoked = false
            val fakeRuntime =
                object : LlamaRuntime() {
                    override suspend fun loadModel(
                        path: String,
                        kind: ModelKind,
                    ): Result<Unit> {
                        loadModelInvoked = true
                        return Result.success(Unit)
                    }

                    override suspend fun loadModel(path: String): Result<Unit> {
                        loadModelInvoked = true
                        return Result.success(Unit)
                    }
                }

            val result =
                downloader.loadModel(
                    runtime = fakeRuntime,
                    filename = "test-model.gguf",
                    repo = "test-org/test-model",
                    kind = ModelKind.EMBEDDING,
                )

            assertTrue("Loading must fail when checksum mismatches", result.isFailure)
            val exception = result.exceptionOrNull()
            assertNotNull(exception)
            assertTrue(
                "Error must indicate checksum mismatch",
                exception?.message?.contains("Checksum mismatch") == true,
            )
            assertFalse(
                "LlamaRuntime.loadModel must NEVER be reached on checksum mismatch",
                loadModelInvoked,
            )
            assertFalse(
                "Corrupted model file must be deleted from disk",
                existingFile.exists(),
            )
        }

    @Test
    fun existingModel_withMatchingChecksumAgainstCatalogEntry_succeedsAndReachesLlamaRuntimeLoadModel() =
        runTest {
            val validContent = "verified-authentic-model-bytes"
            val validSha256 = computeSha256(validContent)

            val testCatalog =
                ModelCatalog(
                    schemaVersion = 1,
                    embeddings =
                        listOf(
                            CatalogEntry(
                                id = "valid-model-id",
                                name = "Valid Model",
                                repo = "test-org/valid-model",
                                filename = "valid-model.gguf",
                                sha256 = validSha256,
                                sizeBytes = validContent.length.toLong(),
                            ),
                        ),
                )
            val catalogRepo = createTestCatalogRepository(testCatalog)
            val downloader =
                ModelDownloader(
                    context = context,
                    okHttpClient = okHttpClient,
                    catalogRepository = catalogRepo,
                )

            val modelsDir = File(context.filesDir, "models")
            modelsDir.mkdirs()
            val existingFile = File(modelsDir, "valid-model.gguf")
            existingFile.writeText(validContent)

            var loadModelInvoked = false
            var loadedPath: String? = null
            val fakeRuntime =
                object : LlamaRuntime() {
                    override suspend fun loadModel(
                        path: String,
                        kind: ModelKind,
                    ): Result<Unit> {
                        loadModelInvoked = true
                        loadedPath = path
                        return Result.success(Unit)
                    }

                    override suspend fun loadModel(path: String): Result<Unit> {
                        loadModelInvoked = true
                        loadedPath = path
                        return Result.success(Unit)
                    }
                }

            val result =
                downloader.loadModel(
                    runtime = fakeRuntime,
                    filename = "valid-model.gguf",
                    repo = "test-org/valid-model",
                    kind = ModelKind.EMBEDDING,
                )

            assertTrue("Loading must succeed when checksum matches", result.isSuccess)
            assertTrue(
                "LlamaRuntime.loadModel must be reached when checksum matches",
                loadModelInvoked,
            )
            assertEquals(existingFile.absolutePath, loadedPath)
            assertTrue("Valid file must remain on disk", existingFile.exists())
        }

    @Test
    fun downloadedModel_withChecksumMismatchAgainstCatalogEntry_isRejectedBeforeLlamaRuntimeLoadModel() =
        runTest {
            val expectedSha256 =
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            val servedBytes = "actual-network-bytes-with-different-checksum"

            val testCatalog =
                ModelCatalog(
                    schemaVersion = 1,
                    chat =
                        listOf(
                            CatalogEntry(
                                id = "network-model-id",
                                name = "Network Model",
                                repo = "test-org/network-model",
                                filename = "network-model.gguf",
                                sha256 = expectedSha256,
                                sizeBytes = servedBytes.length.toLong(),
                            ),
                        ),
                )
            val catalogRepo = createTestCatalogRepository(testCatalog)

            val clientWithRedirect =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        if (request.url.encodedPath.contains("resolve/main")) {
                            server.url("/download/network-model.gguf").let { redirectUrl ->
                                chain.proceed(
                                    request.newBuilder().url(redirectUrl).build(),
                                )
                            }
                        } else {
                            chain.proceed(request)
                        }
                    }.build()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", servedBytes.length.toString())
                    .setBody(servedBytes),
            )

            val downloader =
                ModelDownloader(
                    context = context,
                    okHttpClient = clientWithRedirect,
                    catalogRepository = catalogRepo,
                )

            var loadModelInvoked = false
            val fakeRuntime =
                object : LlamaRuntime() {
                    override suspend fun loadModel(
                        path: String,
                        kind: ModelKind,
                    ): Result<Unit> {
                        loadModelInvoked = true
                        return Result.success(Unit)
                    }
                }

            val result =
                downloader.loadModel(
                    runtime = fakeRuntime,
                    filename = "network-model.gguf",
                    repo = "test-org/network-model",
                    kind = ModelKind.CHAT,
                )

            assertTrue("Download must fail on checksum mismatch", result.isFailure)
            assertFalse(
                "LlamaRuntime.loadModel must NEVER be reached on checksum mismatch",
                loadModelInvoked,
            )

            val targetFile = downloader.getModelFile("network-model.gguf")
            assertFalse("Target file must not remain on mismatch", targetFile.exists())
            val tempFile = File(targetFile.parentFile, "network-model.gguf.tmp")
            assertFalse("Temp file must be cleaned up on mismatch", tempFile.exists())
        }

    @Test
    fun downloadedModel_withMatchingChecksumAgainstCatalogEntry_succeedsAndReachesLlamaRuntimeLoadModel() =
        runTest {
            val servedBytes = "legitimate-model-binary-stream"
            val validSha256 = computeSha256(servedBytes)

            val testCatalog =
                ModelCatalog(
                    schemaVersion = 1,
                    chat =
                        listOf(
                            CatalogEntry(
                                id = "good-download-id",
                                name = "Good Download",
                                repo = "test-org/good-download",
                                filename = "good-download.gguf",
                                sha256 = validSha256,
                                sizeBytes = servedBytes.length.toLong(),
                            ),
                        ),
                )
            val catalogRepo = createTestCatalogRepository(testCatalog)

            val clientWithRedirect =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val request = chain.request()
                        if (request.url.encodedPath.contains("resolve/main")) {
                            server.url("/download/good-download.gguf").let { redirectUrl ->
                                chain.proceed(
                                    request.newBuilder().url(redirectUrl).build(),
                                )
                            }
                        } else {
                            chain.proceed(request)
                        }
                    }.build()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", servedBytes.length.toString())
                    .setBody(servedBytes),
            )

            val downloader =
                ModelDownloader(
                    context = context,
                    okHttpClient = clientWithRedirect,
                    catalogRepository = catalogRepo,
                )

            var loadModelInvoked = false
            val fakeRuntime =
                object : LlamaRuntime() {
                    override suspend fun loadModel(
                        path: String,
                        kind: ModelKind,
                    ): Result<Unit> {
                        loadModelInvoked = true
                        return Result.success(Unit)
                    }
                }

            val result =
                downloader.loadModel(
                    runtime = fakeRuntime,
                    filename = "good-download.gguf",
                    repo = "test-org/good-download",
                    kind = ModelKind.CHAT,
                )

            assertTrue("Download must succeed when checksum matches", result.isSuccess)
            assertTrue(
                "LlamaRuntime.loadModel must be reached when checksum matches",
                loadModelInvoked,
            )

            val targetFile = downloader.getModelFile("good-download.gguf")
            assertTrue("Target file must exist", targetFile.exists())
            assertEquals(servedBytes, targetFile.readText())
        }

    @Test
    fun bundledCatalogSeeds_haveValidPinnedChecksumsAndRoutingDefaults() {
        val stream =
            javaClass.classLoader?.getResourceAsStream("catalog/models.json")
                ?: error("catalog/models.json resource not found")
        val jsonText = stream.use { it.bufferedReader().readText() }
        val catalog = json.decodeFromString<ModelCatalog>(jsonText)

        assertEquals(1, catalog.schemaVersion)
        assertEquals("Qwen3-4B", catalog.routingDefaults.chat)
        assertEquals("Qwen3-1.7B", catalog.routingDefaults.utility)
        assertEquals("embeddinggemma-300m", catalog.routingDefaults.embeddings)

        assertEquals(1, catalog.chat.size)
        val chatSeed = catalog.chat.first()
        assertEquals("Qwen3-4B", chatSeed.id)
        assertEquals("Qwen/Qwen3-4B-GGUF", chatSeed.repo)
        assertEquals("Qwen3-4B-Q4_K_M.gguf", chatSeed.filename)
        assertEquals(
            "7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5",
            chatSeed.sha256,
        )
        assertEquals(2497280256L, chatSeed.sizeBytes)
        assertEquals(32768, chatSeed.contextLength)

        assertEquals(1, catalog.utility.size)
        val utilitySeed = catalog.utility.first()
        assertEquals("Qwen3-1.7B", utilitySeed.id)
        assertEquals("Qwen/Qwen3-1.7B-GGUF", utilitySeed.repo)
        assertEquals("Qwen3-1.7B-Q8_0.gguf", utilitySeed.filename)
        assertEquals(
            "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a",
            utilitySeed.sha256,
        )
        assertEquals(1834426016L, utilitySeed.sizeBytes)
        assertEquals(32768, utilitySeed.contextLength)

        assertEquals(1, catalog.embeddings.size)
        val embeddingsSeed = catalog.embeddings.first()
        assertEquals("embeddinggemma-300m", embeddingsSeed.id)
        assertEquals("ggml-org/embeddinggemma-300m-GGUF", embeddingsSeed.repo)
        assertEquals("embeddinggemma-300M-Q8_0.gguf", embeddingsSeed.filename)
        assertEquals(
            "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63",
            embeddingsSeed.sha256,
        )
        assertEquals(333590944L, embeddingsSeed.sizeBytes)
        assertEquals(2048, embeddingsSeed.contextLength)
    }
}
