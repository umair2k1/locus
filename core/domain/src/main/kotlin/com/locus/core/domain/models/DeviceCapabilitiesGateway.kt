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

/**
 * Gateway providing device runtime resource headroom (RAM, storage) and hardware fingerprint for
 * model candidate filtering and benchmark ranking policy (M-9).
 */
interface DeviceCapabilitiesGateway {
    /**
     * Available RAM headroom in bytes (e.g. from ActivityManager.MemoryInfo.availMem). If
     * unconstrained or unknown, returns 0.
     */
    fun getAvailableRamBytes(): Long

    /** Total device physical RAM in bytes (e.g. from ActivityManager.MemoryInfo.totalMem). */
    fun getTotalRamBytes(): Long

    /**
     * Available storage headroom in bytes for model downloads and temporary files (e.g. from
     * StatFs.availableBytes). If unconstrained or unknown, returns 0.
     */
    fun getAvailableStorageBytes(): Long

    /** Stable device fingerprint/identifier used to match benchmarked tok/s entries in ModelMeta. */
    fun getDeviceFingerprint(): String
}
