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

package com.locus.core.data.chat

import com.locus.core.domain.chat.ActiveModelInfo
import com.locus.core.domain.chat.ModelTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultActiveModelRepositoryTest {
    private lateinit var repository: DefaultActiveModelRepository

    @Before
    fun setUp() {
        repository = DefaultActiveModelRepository()
    }

    @Test
    fun defaultModelIsCloudGpt4o() =
        runTest {
            val initial = repository.getActiveModel()
            assertEquals("gpt-4o", initial.name)
            assertEquals(ModelTier.CLOUD, initial.tier)
            assertTrue(initial.isCloud)

            val observed = repository.observeActiveModel().first()
            assertEquals(initial, observed)
        }

    @Test
    fun updatingActiveModelEmitsNewValue() =
        runTest {
            val localModel =
                ActiveModelInfo(
                    name = "llama-3.2-3b",
                    tier = ModelTier.LOCAL,
                    contextLength = 8192,
                )
            repository.setActiveModel(localModel)

            assertEquals(localModel, repository.getActiveModel())
            assertTrue(repository.getActiveModel().isLocal)

            val observed = repository.observeActiveModel().first()
            assertEquals(localModel, observed)
        }
}
