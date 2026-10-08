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
import com.locus.core.ai.llama.DeviceFingerprintProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AndroidDeviceCapabilitiesGatewayTest {
    private lateinit var context: Context
    private lateinit var fakeDeviceProvider: FakeDeviceFingerprintProvider
    private lateinit var gateway: AndroidDeviceCapabilitiesGateway

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val shadowActivityManager = Shadows.shadowOf(activityManager)
        val memInfo =
            ActivityManager.MemoryInfo().apply {
                availMem = 4_000_000_000L
                totalMem = 8_000_000_000L
            }
        shadowActivityManager.setMemoryInfo(memInfo)

        fakeDeviceProvider = FakeDeviceFingerprintProvider("robolectric-device-fingerprint")
        gateway = AndroidDeviceCapabilitiesGateway(context, fakeDeviceProvider)
    }

    @Test
    fun getAvailableRamBytes_returnsPositiveRamHeadroom() {
        val availableRam = gateway.getAvailableRamBytes()
        assertTrue("Available RAM should be positive, got $availableRam", availableRam > 0L)
    }

    @Test
    fun getTotalRamBytes_returnsPositiveTotalMemory() {
        val totalRam = gateway.getTotalRamBytes()
        assertTrue("Total RAM should be positive, got $totalRam", totalRam > 0L)
        assertTrue(
            "Total RAM should be >= available RAM",
            totalRam >= gateway.getAvailableRamBytes(),
        )
    }

    @Test
    fun getAvailableStorageBytes_returnsNonNegativeStorageSpace() {
        val storageBytes = gateway.getAvailableStorageBytes()
        assertTrue("Storage bytes should be non-negative, got $storageBytes", storageBytes >= 0L)
    }

    @Test
    fun getDeviceFingerprint_delegatesToDeviceFingerprintProvider() {
        assertEquals("robolectric-device-fingerprint", gateway.getDeviceFingerprint())
    }

    private class FakeDeviceFingerprintProvider(
        private val fingerprint: String,
    ) : DeviceFingerprintProvider {
        override fun getDeviceFingerprint(): String = fingerprint
    }
}
