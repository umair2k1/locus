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

import kotlinx.serialization.Serializable

@Serializable
data class ModelCatalog(
    val schemaVersion: Int,
    val chat: List<CatalogEntry> = emptyList(),
    val utility: List<CatalogEntry> = emptyList(),
    val embeddings: List<CatalogEntry> = emptyList(),
    val routingDefaults: RoutingDefaults = RoutingDefaults(),
) {
    companion object {
        val EMPTY =
            ModelCatalog(
                schemaVersion = 1,
                chat = emptyList(),
                utility = emptyList(),
                embeddings = emptyList(),
                routingDefaults = RoutingDefaults(),
            )
    }
}

@Serializable
data class CatalogEntry(
    val id: String,
    val name: String = "",
    val repo: String,
    val filename: String,
    val sha256: String,
    val sizeBytes: Long = 0L,
    val contextLength: Int = 4096,
    val description: String = "",
    val supportsTools: Boolean = false,
)

@Serializable
data class RoutingDefaults(
    val chat: String = "",
    val utility: String = "",
    val embeddings: String = "",
)
