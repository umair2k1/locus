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

package com.locus.core.ai.hf

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HuggingFaceCatalogClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HuggingFaceCatalogClient
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            HuggingFaceCatalogClient(
                okHttpClient = OkHttpClient(),
                baseUrl = server.url("/").toString(),
                ioDispatcher = testDispatcher,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchGgufRepos_withQuery_buildsCorrectUrlAndParsesResponse() =
        runTest(testDispatcher) {
            val mockJson =
                """
                [
                  {
                    "id": "Qwen/Qwen2.5-Coder-7B-Instruct-GGUF",
                    "description": "Qwen2.5-Coder is the code version of the Qwen2.5 series.",
                    "downloads": 150240,
                    "likes": 421,
                    "tags": ["gguf", "code"]
                  },
                  {
                    "id": "TheBloke/Llama-2-7B-GGUF",
                    "pipeline_tag": "text-generation",
                    "downloads": 892100,
                    "likes": 1205
                  }
                ]
                """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

            val results = client.searchGgufRepos("qwen")

            val recordedRequest = server.takeRequest()
            assertEquals("GET", recordedRequest.method)
            assertEquals("/api/models?filter=gguf&search=qwen", recordedRequest.path)

            assertEquals(2, results.size)

            val first = results[0]
            assertEquals("Qwen/Qwen2.5-Coder-7B-Instruct-GGUF", first.id)
            assertEquals("Qwen/Qwen2.5-Coder-7B-Instruct-GGUF", first.repoId)
            assertEquals(
                "Qwen2.5-Coder is the code version of the Qwen2.5 series.",
                first.description,
            )
            assertEquals(150240, first.downloads)
            assertEquals(421, first.likes)

            val second = results[1]
            assertEquals("TheBloke/Llama-2-7B-GGUF", second.id)
            assertEquals("TheBloke/Llama-2-7B-GGUF", second.repoId)
            assertEquals("text-generation", second.description)
            assertEquals(892100, second.downloads)
            assertEquals(1205, second.likes)
        }

    @Test
    fun searchGgufRepos_emptyQuery_omitsSearchQueryParam() =
        runTest(testDispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            val results = client.searchGgufRepos("   ")

            val recordedRequest = server.takeRequest()
            assertEquals("GET", recordedRequest.method)
            assertEquals("/api/models?filter=gguf", recordedRequest.path)
            assertTrue(results.isEmpty())
        }

    @Test
    fun searchGgufRepos_withSortAndDirection_includesParamsInUrl() =
        runTest(testDispatcher) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

            val results = client.searchGgufRepos("qwen", sort = "downloads", direction = -1)

            val recordedRequest = server.takeRequest()
            assertEquals("GET", recordedRequest.method)
            assertEquals("/api/models?filter=gguf&search=qwen&sort=downloads&direction=-1", recordedRequest.path)
            assertTrue(results.isEmpty())
        }

    @Test
    fun searchGgufRepos_httpError_throwsIOException() =
        runTest(testDispatcher) {
            server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking { client.searchGgufRepos("error-query") }
            }
        }

    @Test
    fun listFiles_defaultGgufFilter_returnsOnlyGgufFilesWithSha256() =
        runTest(testDispatcher) {
            val mockJson =
                """
                [
                  {
                    "type": "file",
                    "oid": "8672c8d7255b101150c9153a45cfa4058410ddc8",
                    "size": 1585,
                    "path": ".gitattributes"
                  },
                  {
                    "type": "file",
                    "oid": "f803baf9221fcaba3f2465f3c4909b6ac7cb1b22",
                    "size": 1359,
                    "path": "README.md"
                  },
                  {
                    "type": "file",
                    "oid": "348a491efd217d3af9bbded8890b3dc4f6ba6092",
                    "size": 333590944,
                    "lfs": {
                      "oid": "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63",
                      "size": 333590944,
                      "pointerSize": 134
                    },
                    "path": "embeddinggemma-300M-Q8_0.gguf"
                  },
                  {
                    "type": "directory",
                    "path": "configs",
                    "size": 0
                  }
                ]
                """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

            val files = client.listFiles("ggml-org/embeddinggemma-300M-GGUF")

            val recordedRequest = server.takeRequest()
            assertEquals("GET", recordedRequest.method)
            assertEquals(
                "/api/models/ggml-org/embeddinggemma-300M-GGUF/tree/main",
                recordedRequest.path,
            )

            assertEquals(1, files.size)
            val ggufFile = files[0]
            assertEquals("embeddinggemma-300M-Q8_0.gguf", ggufFile.name)
            assertEquals("embeddinggemma-300M-Q8_0.gguf", ggufFile.path)
            assertEquals(333590944L, ggufFile.size)
            assertEquals(
                "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63",
                ggufFile.sha256,
            )
        }

    @Test
    fun listFiles_filterGgufFalse_includesAllFiles() =
        runTest(testDispatcher) {
            val mockJson =
                """
                [
                  {
                    "type": "file",
                    "oid": "8672c8d7255b101150c9153a45cfa4058410ddc8",
                    "size": 1585,
                    "path": ".gitattributes"
                  },
                  {
                    "type": "file",
                    "oid": "f803baf9221fcaba3f2465f3c4909b6ac7cb1b22",
                    "size": 1359,
                    "path": "README.md"
                  },
                  {
                    "type": "file",
                    "oid": "348a491efd217d3af9bbded8890b3dc4f6ba6092",
                    "size": 333590944,
                    "lfs": {
                      "oid": "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63",
                      "size": 333590944,
                      "pointerSize": 134
                    },
                    "path": "embeddinggemma-300M-Q8_0.gguf"
                  },
                  {
                    "type": "directory",
                    "path": "configs",
                    "size": 0
                  }
                ]
                """.trimIndent()

            server.enqueue(MockResponse().setResponseCode(200).setBody(mockJson))

            val files = client.listFiles("ggml-org/embeddinggemma-300M-GGUF", filterGguf = false)

            assertEquals(3, files.size)
            assertEquals(".gitattributes", files[0].name)
            assertEquals(1585L, files[0].size)
            assertEquals("", files[0].sha256)

            assertEquals("README.md", files[1].name)
            assertEquals(1359L, files[1].size)
            assertEquals("", files[1].sha256)

            assertEquals("embeddinggemma-300M-Q8_0.gguf", files[2].name)
            assertEquals(333590944L, files[2].size)
            assertEquals(
                "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63",
                files[2].sha256,
            )
        }

    @Test
    fun listFiles_http404_throwsIOException() =
        runTest(testDispatcher) {
            server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

            assertThrows(IOException::class.java) {
                kotlinx.coroutines.runBlocking { client.listFiles("nonexistent/repo") }
            }
        }
}
