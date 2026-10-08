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

package com.locus.core.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.locus.core.data.backup.aiDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NetworkSettingsStoreTest {
    private lateinit var context: Context
    private lateinit var store: NetworkSettingsStore

    @Before
    fun setUp() =
        runTest {
            context = RuntimeEnvironment.getApplication()
            context.aiDataStore.edit { it.clear() }
            store = NetworkSettingsStore(context)
        }

    @Test
    fun isCloudDisabled_defaultsToFalse() =
        runTest {
            assertFalse(store.isCloudDisabled.first())
        }

    @Test
    fun setCloudDisabled_persistsAndEmits() =
        runTest {
            store.setCloudDisabled(true)
            assertTrue(store.isCloudDisabled.first())

            store.setCloudDisabled(false)
            assertFalse(store.isCloudDisabled.first())
        }
}
