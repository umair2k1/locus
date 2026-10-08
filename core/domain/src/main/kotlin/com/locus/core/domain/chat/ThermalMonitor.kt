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

import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for device thermal status monitoring during sustained local model generation (M-7).
 */
interface ThermalMonitor {
    /** Whether thermal throttling is likely (status reaches THERMAL_STATUS_MODERATE or higher). */
    val isThrottlingLikely: StateFlow<Boolean>

    /** Begins active monitoring of device thermal status during sustained generation. */
    fun startMonitoring()

    /** Halts active monitoring and releases any listeners/resources. */
    fun stopMonitoring()

    /** Dismisses/resets the current throttling warning flag. */
    fun dismissWarning()
}
