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

package com.locus.core.domain.chat

/**
 * Model tier indicator per SEC-2 / P-4. Distinguishes local on-device models from remote cloud
 * providers.
 */
typealias ModelTier = com.locus.core.domain.routing.ModelTier

/**
 * Metadata for the currently active AI model in Chat.
 *
 * @property name Human-readable model identifier (e.g. "gpt-4o", "llama-3.2-1b")
 * @property tier Whether the model runs locally on-device or via a cloud API
 * @property contextLength Optional context window length in tokens
 */
data class ActiveModelInfo(
    val name: String,
    val tier: ModelTier,
    val contextLength: Int? = null,
) {
    val isLocal: Boolean
        get() = tier == ModelTier.LOCAL
    val isCloud: Boolean
        get() = tier == ModelTier.CLOUD
}
