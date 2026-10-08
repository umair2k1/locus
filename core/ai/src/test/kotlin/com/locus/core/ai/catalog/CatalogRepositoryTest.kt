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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CatalogRepositoryTest {
    private lateinit var context: Context
    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var testDataStoreFile: File
    private lateinit var testDataStore: DataStore<Preferences>

    private val sampleBundledJson =
        """
        {
          "schemaVersion": 1,
          "chat": [],
          "utility": [],
          "embeddings": [],
          "routingDefaults": {
            "chat": "",
            "utility": "",
            "embeddings": ""
          }
        }
        """.trimIndent()

    private val bumpedRemoteJson =
        """
        {
          "schemaVersion": 2,
          "chat": [
            {
              "id": "qwen3-4b-q4km",
              "name": "Qwen3-4B Q4_K_M",
              "repo": "Qwen/Qwen3-4B-GGUF",
              "filename": "Qwen3-4B-Q4_K_M.gguf",
              "sha256": "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
              "sizeBytes": 2500000000,
              "contextLength": 4096,
              "description": "Recommended chat model"
            }
          ],
          "utility": [],
          "embeddings": [],
          "routingDefaults": {
            "chat": "qwen3-4b-q4km",
            "utility": "",
            "embeddings": ""
          }
        }
        """.trimIndent()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        mockWebServer = MockWebServer()
        mockWebServer.start()
        okHttpClient = OkHttpClient.Builder().build()
        testDataStoreFile = File(context.filesDir, "test_catalog_${System.nanoTime()}.preferences_pb")
        testDataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + Job()),
                produceFile = { testDataStoreFile },
            )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        if (testDataStoreFile.exists()) {
            testDataStoreFile.delete()
        }
    }

    private fun createRepository(
        remoteUrl: String = mockWebServer.url("/catalog/models.json").toString(),
        dataStore: DataStore<Preferences> = testDataStore,
        bundledJson: String = sampleBundledJson,
    ): CatalogRepository =
        CatalogRepository(
            context = context,
            okHttpClient = okHttpClient,
            ioDispatcher = Dispatchers.IO,
            remoteUrl = remoteUrl,
            dataStore = dataStore,
            assetLoader = { ByteArrayInputStream(bundledJson.toByteArray()) },
        )

    @Test
    fun firstRun_offline_usesBundledCatalog() =
        runTest {
            val repository = createRepository(remoteUrl = "http://127.0.0.1:1/non_existent_url")
            val catalog = repository.current().first()

            assertEquals(1, catalog.schemaVersion)
            assertTrue(catalog.chat.isEmpty())
            assertTrue(catalog.utility.isEmpty())
            assertTrue(catalog.embeddings.isEmpty())
            assertEquals("", catalog.routingDefaults.chat)

            val refreshResult = repository.refreshFromRemote()
            assertTrue(refreshResult.isFailure)

            val catalogAfterFailedRefresh = repository.current().first()
            assertEquals(1, catalogAfterFailedRefresh.schemaVersion)
        }

    @Test
    fun refreshFromRemote_bumpedVersion_updatesMemoryAndDataStore() =
        runTest {
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(bumpedRemoteJson),
            )

            val repository = createRepository()
            val initial = repository.current().first()
            assertEquals(1, initial.schemaVersion)

            val refreshResult = repository.refreshFromRemote()
            assertTrue(refreshResult.isSuccess)

            val updated = repository.current().first()
            assertEquals(2, updated.schemaVersion)
            assertEquals(1, updated.chat.size)
            assertEquals("qwen3-4b-q4km", updated.chat[0].id)
            assertEquals("Qwen3-4B Q4_K_M", updated.chat[0].name)
            assertEquals("Qwen/Qwen3-4B-GGUF", updated.chat[0].repo)
            assertEquals("qwen3-4b-q4km", updated.routingDefaults.chat)

            // Verify DataStore cache was written
            val prefs = testDataStore.data.first()
            assertEquals(2, prefs[CatalogRepository.KEY_SCHEMA_VERSION])
            assertTrue(prefs[CatalogRepository.KEY_CATALOG_JSON]?.contains("qwen3-4b-q4km") == true)
        }

    @Test
    fun refreshFromRemote_staleOrEqualVersion_doesNotOverwrite() =
        runTest {
            // First bump to version 2
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(bumpedRemoteJson),
            )
            val repository = createRepository()
            repository.refreshFromRemote()
            assertEquals(2, repository.current().first().schemaVersion)

            // Now remote serves an older version 1
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(sampleBundledJson),
            )

            val secondRefresh = repository.refreshFromRemote()
            assertTrue(secondRefresh.isSuccess)

            // Current should still be version 2
            val catalog = repository.current().first()
            assertEquals(2, catalog.schemaVersion)
            assertEquals(1, catalog.chat.size)
        }

    @Test
    fun refreshFromRemote_networkFailure_retainsCurrentCatalog() =
        runTest {
            mockWebServer.enqueue(
                MockResponse().setResponseCode(500).setBody("Internal Server Error"),
            )

            val repository = createRepository()
            val refreshResult = repository.refreshFromRemote()
            assertTrue(refreshResult.isFailure)

            val catalog = repository.current().first()
            assertEquals(1, catalog.schemaVersion)
        }

    @Test
    fun relaunch_loadsCachedCatalogFromDataStore() =
        runTest {
            // Populate cache with version 2
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(bumpedRemoteJson),
            )
            val initialRepo = createRepository()
            initialRepo.refreshFromRemote()
            assertEquals(2, initialRepo.current().first().schemaVersion)

            // Simulate app relaunch with a new repository pointing to the same DataStore, offline
            val relaunchedRepo =
                createRepository(
                    remoteUrl = "http://127.0.0.1:1/offline",
                    dataStore = testDataStore,
                )

            // Wait for DataStore collector to update current catalog
            val cachedCatalog =
                testDataStore.data.first { prefs -> prefs[CatalogRepository.KEY_SCHEMA_VERSION] == 2 }
            assertEquals(2, cachedCatalog[CatalogRepository.KEY_SCHEMA_VERSION])

            val relaunchedCatalog = relaunchedRepo.current().first()
            assertEquals(2, relaunchedCatalog.schemaVersion)
            assertEquals(1, relaunchedCatalog.chat.size)
            assertEquals("qwen3-4b-q4km", relaunchedCatalog.chat[0].id)
        }

    @Test
    fun appUpdate_newerBundledSnapshot_supersedesOldCache() =
        runTest {
            // Cache has version 2
            mockWebServer.enqueue(
                MockResponse().setResponseCode(200).setBody(bumpedRemoteJson),
            )
            val repo = createRepository()
            repo.refreshFromRemote()

            // App is updated with bundled snapshot having version 3
            val version3BundledJson =
                """
                {
                  "schemaVersion": 3,
                  "chat": [
                    {
                      "id": "qwen3-bundled-v3",
                      "name": "Qwen3 Bundled V3",
                      "repo": "Qwen/Qwen3-4B-GGUF",
                      "filename": "Qwen3-4B-Q4_K_M.gguf",
                      "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
                      "sizeBytes": 2500000000,
                      "contextLength": 4096,
                      "description": "Bundled v3"
                    }
                  ],
                  "utility": [],
                  "embeddings": [],
                  "routingDefaults": {
                    "chat": "qwen3-bundled-v3",
                    "utility": "",
                    "embeddings": ""
                  }
                }
                """.trimIndent()

            val updatedAppRepo =
                createRepository(
                    dataStore = testDataStore,
                    bundledJson = version3BundledJson,
                )

            val activeCatalog = updatedAppRepo.current().first()
            assertEquals(3, activeCatalog.schemaVersion)
            assertEquals("qwen3-bundled-v3", activeCatalog.chat[0].id)
        }
}
