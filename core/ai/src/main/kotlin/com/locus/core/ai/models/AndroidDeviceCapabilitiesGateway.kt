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

package com.locus.core.ai.models

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.locus.core.ai.llama.DeviceFingerprintProvider
import com.locus.core.domain.models.DeviceCapabilitiesGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete Android implementation of [DeviceCapabilitiesGateway] using [ActivityManager] for RAM
 * headroom and [StatFs] for file system storage headroom.
 */
@Singleton
class AndroidDeviceCapabilitiesGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val deviceFingerprintProvider: DeviceFingerprintProvider,
    ) : DeviceCapabilitiesGateway {
        override fun getAvailableRamBytes(): Long {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            return memoryInfo.availMem
        }

        override fun getTotalRamBytes(): Long {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            return memoryInfo.totalMem
        }

        override fun getAvailableStorageBytes(): Long =
            try {
                val dir = context.filesDir
                val statFs = StatFs(dir.absolutePath)
                statFs.availableBytes
            } catch (_: Exception) {
                0L
            }

        override fun getDeviceFingerprint(): String = deviceFingerprintProvider.getDeviceFingerprint()
    }
