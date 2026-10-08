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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DismissedRecommendationsStoreTest {
    private lateinit var context: Context
    private lateinit var store: DismissedRecommendationsStore

    @Before
    fun setUp() =
        runTest {
            context = RuntimeEnvironment.getApplication()
            context.aiDataStore.edit { it.clear() }
            store = DismissedRecommendationsStore(context)
        }

    @Test
    fun defaultDismissedIds_isEmpty() = runTest { assertTrue(store.dismissedIds.first().isEmpty()) }

    @Test
    fun dismiss_persistsAndEmits() =
        runTest {
            store.dismiss("Qwen3-4B")
            assertEquals(setOf("Qwen3-4B"), store.dismissedIds.first())
        }

    @Test
    fun multipleDismiss_accumulates() =
        runTest {
            store.dismiss("Qwen3-4B")
            store.dismiss("Qwen3-1.7B")
            assertEquals(setOf("Qwen3-4B", "Qwen3-1.7B"), store.dismissedIds.first())
        }

    @Test
    fun dismiss_persistsAcrossNewInstance() =
        runTest {
            store.dismiss("embeddinggemma-300m")
            val newStoreInstance = DismissedRecommendationsStore(context)
            assertEquals(setOf("embeddinggemma-300m"), newStoreInstance.dismissedIds.first())
        }
}
