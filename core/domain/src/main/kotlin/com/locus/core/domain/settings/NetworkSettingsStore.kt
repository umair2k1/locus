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

package com.locus.core.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Manages privacy and network settings, specifically controlling whether external cloud AI providers
 * and outbound internet connections are permitted or disabled (enforcing a 100% offline pipeline).
 */
interface NetworkSettingsStore {
    /**
     * Emits true when external cloud AI providers and outbound internet operations are disabled.
     */
    val isCloudDisabled: Flow<Boolean>

    /**
     * Sets whether external cloud AI providers and outbound internet operations are disabled.
     */
    suspend fun setCloudDisabled(disabled: Boolean)
}
