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

package com.locus.core.domain.agent

import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderMessage

/**
 * Domain interface for running the multi-tool agent loop.
 */
interface AgentToolLoopRunner {
    @Suppress("LongParameterList")
    suspend fun run(
        userMessage: String,
        adapter: ProviderAdapter? = null,
        coordinator: AgentRunCoordinator? = null,
        history: List<ProviderMessage> = emptyList(),
        systemPrompt: String? = null,
        onTokenDelta: ((String) -> Unit)? = null,
    ): String
}
