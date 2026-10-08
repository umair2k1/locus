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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class HfRepoSummary(
    val id: String,
    val description: String = "",
    val downloads: Int = 0,
    val likes: Int = 0,
) {
    val repoId: String
        get() = id
}

@Serializable
data class HfFileInfo(
    val name: String,
    val size: Long,
    val sha256: String = "",
) {
    val path: String
        get() = name
}

@Singleton
class HuggingFaceCatalogClient(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        okHttpClient: OkHttpClient,
    ) : this(
        okHttpClient = okHttpClient,
        baseUrl = DEFAULT_BASE_URL,
        ioDispatcher = Dispatchers.IO,
    )

    companion object {
        const val DEFAULT_BASE_URL = "https://huggingface.co"
        private const val SHA256_HEX_LENGTH = 64
    }

    suspend fun searchGgufRepos(
        query: String,
        sort: String? = null,
        direction: Int? = null,
    ): List<HfRepoSummary> =
        withContext(ioDispatcher) {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val httpUrl = cleanBase.toHttpUrlOrNull() ?: ("https://$cleanBase").toHttpUrl()
            val urlBuilder =
                httpUrl
                    .newBuilder()
                    .addPathSegment("api")
                    .addPathSegment("models")
                    .addQueryParameter("filter", "gguf")

            val trimmedQuery = query.trim()
            if (trimmedQuery.isNotEmpty()) {
                urlBuilder.addQueryParameter("search", trimmedQuery)
            }

            if (!sort.isNullOrBlank()) {
                urlBuilder.addQueryParameter("sort", sort)
                if (direction != null) {
                    urlBuilder.addQueryParameter("direction", direction.toString())
                }
            }

            val request =
                Request
                    .Builder()
                    .url(urlBuilder.build())
                    .get()
                    .build()

            val bodyString =
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(
                            "Hugging Face search failed with HTTP ${response.code}: ${request.url}",
                        )
                    }
                    response.body?.string()
                        ?: throw IOException("Empty response body from ${request.url}")
                }

            parseRepoSummaries(bodyString)
        }

    suspend fun listFiles(
        repoId: String,
        filterGguf: Boolean = true,
    ): List<HfFileInfo> =
        withContext(ioDispatcher) {
            val cleanBase = baseUrl.trim().trimEnd('/')
            val httpUrl = cleanBase.toHttpUrlOrNull() ?: ("https://$cleanBase").toHttpUrl()
            val urlBuilder = httpUrl.newBuilder().addPathSegment("api").addPathSegment("models")

            repoId.split("/").filter { it.isNotBlank() }.forEach { segment ->
                urlBuilder.addPathSegment(segment)
            }

            urlBuilder.addPathSegment("tree").addPathSegment("main")

            val request =
                Request
                    .Builder()
                    .url(urlBuilder.build())
                    .get()
                    .build()

            val bodyString =
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException(
                            "Hugging Face listFiles failed with HTTP ${response.code}: ${request.url}",
                        )
                    }
                    response.body?.string()
                        ?: throw IOException("Empty response body from ${request.url}")
                }

            parseFileTree(bodyString, filterGguf)
        }

    private fun parseRepoSummaries(jsonString: String): List<HfRepoSummary> {
        val array = JSONArray(jsonString)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id").ifBlank { item.optString("modelId") }
            if (id.isBlank()) return@mapNotNull null
            val description =
                item.optString("description").ifBlank { item.optString("pipeline_tag") }
            val downloads = item.optInt("downloads", 0)
            val likes = item.optInt("likes", 0)
            HfRepoSummary(
                id = id,
                description = description,
                downloads = downloads,
                likes = likes,
            )
        }
    }

    private fun parseFileTree(
        jsonString: String,
        filterGguf: Boolean,
    ): List<HfFileInfo> {
        val array = JSONArray(jsonString)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            parseFileInfo(item, filterGguf)
        }
    }

    private fun parseFileInfo(
        item: JSONObject,
        filterGguf: Boolean,
    ): HfFileInfo? {
        val type = item.optString("type")
        val path = item.optString("path").ifBlank { item.optString("name") }
        val isValidFile =
            !type.equals("directory", ignoreCase = true) &&
                path.isNotBlank() &&
                (!filterGguf || path.endsWith(".gguf", ignoreCase = true))

        if (!isValidFile) {
            return null
        }

        val lfs = item.optJSONObject("lfs")
        val size =
            if (item.has("size")) {
                item.optLong("size")
            } else {
                lfs?.optLong("size") ?: 0L
            }

        val sha256 =
            lfs?.optString("oid")?.ifBlank { null }
                ?: item.optString("sha256").ifBlank { null }
                ?: item.optString("oid").takeIf { it.length == SHA256_HEX_LENGTH }
                ?: ""

        return HfFileInfo(
            name = path,
            size = size,
            sha256 = sha256,
        )
    }
}
