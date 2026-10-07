package com.locus.core.domain.notes

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.RouteAndSend
import com.locus.core.domain.routing.RouteDecision
import com.locus.core.domain.routing.TaskType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain use case for I-1 inline AI actions: summarize, rewrite, translate, extract tasks.
 * Single-shot convenience wrapper for editor actions.
 * Routes through [RouteAndSend] with [TaskType.CHAT_RAG_QA] per D-9.
 */
@Singleton
class InlineAiUseCase
    @Inject
    constructor(
        private val chatModelClient: ChatModelClient,
        private val routeAndSend: RouteAndSend? = null,
    ) {
        suspend fun execute(
            action: InlineAiAction,
            text: String,
            currentModel: ModelRef? = null,
            confirmCloudTransition: (suspend (RouteDecision.RequiresCloudTransitionConfirmation) -> Boolean)? = null,
        ): String {
            if (text.isBlank()) return ""

            if (routeAndSend != null && currentModel != null && confirmCloudTransition != null) {
                routeAndSend.route(
                    task = TaskType.CHAT_RAG_QA,
                    currentModel = currentModel,
                    confirmCloudTransition = confirmCloudTransition,
                )
            }

            val prompt = buildPrompt(action, text)
            val result = StringBuilder()
            chatModelClient.generate(prompt).collect { event ->
                if (event is StreamEvent.TokenDelta) {
                    result.append(event.text)
                }
            }
            return result.toString().trim()
        }

        fun buildPrompt(
            action: InlineAiAction,
            text: String,
        ): String =
            when (action) {
                InlineAiAction.SUMMARIZE ->
                    "Summarize the following text concisely:\n\n$text"
                InlineAiAction.REWRITE ->
                    "Rewrite the following text for clarity and polish:\n\n$text"
                InlineAiAction.TRANSLATE ->
                    "Translate the following text into clear English:\n\n$text"
                InlineAiAction.EXTRACT_TASKS ->
                    "Extract actionable tasks from the following text as a markdown checklist " +
                        "(e.g. - [ ] task):\n\n$text"
            }
    }
