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

package com.locus.app.ui.chat

import com.locus.core.domain.chat.ActiveModelInfo
import com.locus.core.domain.chat.ModelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveModelIndicatorTest {
    @Test
    fun cloudModel_verifiesPropertiesAndLabels() {
        val model =
            ActiveModelInfo(
                name = "gpt-4o",
                tier = ModelTier.CLOUD,
                contextLength = 128_000,
            )

        assertEquals("gpt-4o", model.name)
        assertEquals(ModelTier.CLOUD, model.tier)
        assertTrue(model.isCloud)
        assertFalse(model.isLocal)
        assertEquals(128_000, model.contextLength)
    }

    @Test
    fun localModel_verifiesPropertiesAndLabels() {
        val model =
            ActiveModelInfo(
                name = "llama-3.2-3b",
                tier = ModelTier.LOCAL,
                contextLength = 8192,
            )

        assertEquals("llama-3.2-3b", model.name)
        assertEquals(ModelTier.LOCAL, model.tier)
        assertTrue(model.isLocal)
        assertFalse(model.isCloud)
        assertEquals(8192, model.contextLength)
    }
}
