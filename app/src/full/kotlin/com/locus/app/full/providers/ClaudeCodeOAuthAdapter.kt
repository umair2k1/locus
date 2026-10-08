// EXPERIMENTAL — see README ToS disclaimer
package com.locus.app.full.providers

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.providers.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClaudeCodeOAuthAdapter
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
                    emit(StreamEvent.Error("Claude Code subscription account not connected. Please log in."))
                    return@flow
                }
                emit(StreamEvent.TokenDelta("Generated via Claude Code subscription adapter: "))
                emit(StreamEvent.TokenDelta("Response for: ${prompt.take(50)}"))
                emit(StreamEvent.Done())
            }
    }
