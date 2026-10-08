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

package com.locus.core.ai.llama

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

interface DeviceFingerprintProvider {
    fun getDeviceFingerprint(): String
}

@Singleton
class DefaultDeviceFingerprintProvider
    @Inject
    constructor() : DeviceFingerprintProvider {
        override fun getDeviceFingerprint(): String {
            val modelName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            return modelName.ifBlank { Build.FINGERPRINT.ifBlank { "unknown-device" } }
        }
    }
