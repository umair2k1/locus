// EXPERIMENTAL — see README ToS disclaimer

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

package com.locus.app.full.providers

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiAntigravityOAuthAdapter
    @Inject
    constructor() : ChatModelClient {
        private var oauthAccessToken: String? = null

        fun setAccessToken(token: String?) {
            oauthAccessToken = token
        }

        fun hasValidSession(): Boolean = !oauthAccessToken.isNullOrBlank()

        override fun generate(
            prompt: String,
            tools: List<ToolSchema>,
        ): Flow<StreamEvent> =
            flow {
                if (oauthAccessToken.isNullOrBlank()) {
                    emit(
                        StreamEvent.Error(
                            "Gemini Antigravity subscription account not connected. Please log in.",
                        ),
                    )
                    return@flow
                }
                emit(StreamEvent.TokenDelta("Generated via Gemini Antigravity subscription adapter: "))
                emit(StreamEvent.TokenDelta("Response for: ${prompt.take(50)}"))
                emit(StreamEvent.Done())
            }
    }
