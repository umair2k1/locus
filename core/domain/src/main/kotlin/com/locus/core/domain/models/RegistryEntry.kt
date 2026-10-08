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

package com.locus.core.domain.models

import com.locus.core.domain.chat.ActiveModelInfo
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.routing.ModelRef

data class RegistryEntry(
    val ref: ModelRef,
    val contextLength: Int,
    val capabilities: ProviderCapabilities?,
    val isOffline: Boolean,
    val benchmarkedTokPerSecond: Double?,
) {
    fun toActiveModelInfo(): ActiveModelInfo =
        ActiveModelInfo(
            name = ref.id,
            tier = ref.tier,
            contextLength = contextLength,
        )
}
