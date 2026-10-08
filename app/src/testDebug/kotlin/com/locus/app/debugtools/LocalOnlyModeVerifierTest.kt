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

package com.locus.app.debugtools

import com.locus.core.ai.llama.LlamaRuntime
import com.locus.core.ai.llama.LocalLlamaChatModelClient
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderCapabilities
import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOnlyModeVerifierTest {
    private class FakeProviderAdapter : ProviderAdapter {
        override val capabilities: ProviderCapabilities =
            ProviderCapabilities(
                supportsNativeTools = false,
                contextLength = 4096,
                pricePerMillionInputTokens = null,
                pricePerMillionOutputTokens = null,
            )

        override fun streamChat(
            messages: List<ProviderMessage>,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> = emptyFlow()
    }

    @Test
    fun verify_withDefaultStaticCheck_succeeds() {
        LocalOnlyModeVerifier.verify()
    }

    @Test
    fun verify_withExplicitLocalLlamaClient_succeeds() {
        val runtime = LlamaRuntime()
        val client = LocalLlamaChatModelClient(runtime)
        LocalOnlyModeVerifier.verify(client)
    }

    @Test
    fun verify_withProviderAdapter_throwsIllegalStateException() {
        val cloudAdapter = FakeProviderAdapter()
        var exceptionThrown = false
        try {
            LocalOnlyModeVerifier.verify(cloudAdapter)
        } catch (e: IllegalStateException) {
            exceptionThrown = true
            assertTrue(e.message?.contains("M-2 violation") == true)
        }
        assertTrue(
            "Expected IllegalStateException when verifying a ProviderAdapter",
            exceptionThrown,
        )
    }
}
