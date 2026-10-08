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

package com.locus.core.domain.providers

import com.locus.core.domain.chat.ChatModelClient
import kotlinx.coroutines.flow.Flow

interface ProviderAdapter : ChatModelClient {
    val providerId: String
        get() = "openai"
    val capabilities: ProviderCapabilities

    fun streamChat(
        messages: List<ProviderMessage>,
        tools: List<ToolSchema> = emptyList(),
    ): Flow<StreamEvent>

    override fun generate(
        prompt: String,
        tools: List<ToolSchema>,
    ): Flow<StreamEvent> =
        streamChat(
            messages = listOf(ProviderMessage(role = ProviderRole.USER, content = prompt)),
            tools = tools,
        )
}
