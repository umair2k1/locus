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
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.locus.core.domain.time.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

private val Context.catalogDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "locus_model_catalog")

@Singleton
open class CatalogRepository(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val remoteUrl: String = DEFAULT_REMOTE_URL,
    private val dataStore: DataStore<Preferences>? = null,
    private val assetLoader: (() -> InputStream?)? = null,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ) : this(
        context = context,
        okHttpClient = okHttpClient,
        ioDispatcher = dispatchers.io,
        remoteUrl = DEFAULT_REMOTE_URL,
        dataStore = null,
        assetLoader = null,
    )

    companion object {
        private const val TAG = "CatalogRepository"
        const val DEFAULT_REMOTE_URL =
            "https://raw.githubusercontent.com/mdumair-sk/locus/master/catalog/models.json"
        val KEY_CATALOG_JSON = stringPreferencesKey("catalog_cached_json")
        val KEY_SCHEMA_VERSION = intPreferencesKey("catalog_schema_version")
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    private val activeDataStore: DataStore<Preferences> = dataStore ?: context.catalogDataStore

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val refreshMutex = Mutex()

    private val catalogState: MutableStateFlow<ModelCatalog> =
        MutableStateFlow(loadBundledCatalog())

    init {
        val bundled = catalogState.value
        scope.launch {
            activeDataStore.data
                .catch { e -> Log.w(TAG, "Failed reading catalog DataStore: ${e.message}") }
                .collect { prefs ->
                    val cachedJson = prefs[KEY_CATALOG_JSON]
                    val cachedVersion = prefs[KEY_SCHEMA_VERSION]
                    val effective = resolveEffectiveCatalog(bundled, cachedJson, cachedVersion)
                    catalogState.value = effective
                }
        }
    }

    open fun current(): Flow<ModelCatalog> = catalogState.asStateFlow()

    open suspend fun refreshFromRemote(): Result<Unit> =
        withContext(ioDispatcher) {
            refreshMutex.withLock {
                runCatching {
                    val request =
                        Request
                            .Builder()
                            .url(remoteUrl)
                            .get()
                            .build()

                    val responseBody =
                        okHttpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException(
                                    "Catalog remote refresh failed: HTTP ${response.code} from $remoteUrl",
                                )
                            }
                            response.body?.string()
                                ?: throw IOException(
                                    "Empty response body from $remoteUrl",
                                )
                        }

                    val remoteCatalog = json.decodeFromString<ModelCatalog>(responseBody)

                    val cachedVersion =
                        runCatching { activeDataStore.data.first()[KEY_SCHEMA_VERSION] }
                            .getOrNull()
                            ?: catalogState.value.schemaVersion

                    val currentVersion = maxOf(catalogState.value.schemaVersion, cachedVersion)

                    if (remoteCatalog.schemaVersion > currentVersion) {
                        activeDataStore.edit { prefs ->
                            prefs[KEY_CATALOG_JSON] = responseBody
                            prefs[KEY_SCHEMA_VERSION] = remoteCatalog.schemaVersion
                        }
                        catalogState.value = remoteCatalog
                        Log.i(
                            TAG,
                            "Updated model catalog to schemaVersion ${remoteCatalog.schemaVersion}",
                        )
                    } else {
                        Log.d(
                            TAG,
                            "Remote catalog schemaVersion ${remoteCatalog.schemaVersion} " +
                                "is not newer than current $currentVersion",
                        )
                    }
                    Unit
                }
            }
        }

    private fun resolveEffectiveCatalog(
        bundled: ModelCatalog,
        cachedJson: String?,
        cachedVersion: Int?,
    ): ModelCatalog {
        if (cachedJson.isNullOrBlank() ||
            cachedVersion == null ||
            cachedVersion < bundled.schemaVersion
        ) {
            return bundled
        }
        return runCatching { json.decodeFromString<ModelCatalog>(cachedJson) }.getOrDefault(bundled)
    }

    private fun loadBundledCatalog(): ModelCatalog =
        runCatching {
            val stream =
                assetLoader?.invoke()
                    ?: runCatching { context.assets.open("catalog/models.json") }
                        .getOrNull()
                    ?: javaClass.classLoader?.getResourceAsStream(
                        "catalog/models.json",
                    )
                    ?: javaClass.classLoader?.getResourceAsStream(
                        "assets/catalog/models.json",
                    )
                    ?: error("Unable to open bundled catalog asset")

            val jsonText = stream.use { it.bufferedReader().readText() }
            json.decodeFromString<ModelCatalog>(jsonText)
        }.getOrElse { e ->
            Log.w(TAG, "Failed loading bundled catalog snapshot: ${e.message}")
            ModelCatalog.EMPTY
        }
}
