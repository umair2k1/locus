package com.locus.core.domain.notes

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.RouteAndSend
import com.locus.core.domain.routing.RouteDecision
import com.locus.core.domain.routing.TaskType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SuggestTagsUseCase
    @Inject
    constructor(
        private val chatModelClient: ChatModelClient,
        private val routeAndSend: RouteAndSend? = null,
    ) {
        suspend fun execute(
            body: String,
            currentModel: ModelRef? = null,
            confirmCloudTransition: (suspend (RouteDecision.RequiresCloudTransitionConfirmation) -> Boolean)? = null,
        ): List<String> {
            if (body.isBlank()) return emptyList()

            if (routeAndSend != null && currentModel != null && confirmCloudTransition != null) {
                routeAndSend.route(
                    task = TaskType.DIGEST_TAGGING_CLUSTER_LABEL,
                    currentModel = currentModel,
                    confirmCloudTransition = confirmCloudTransition,
                )
            }

            val prompt = buildPrompt(body)
            val result = StringBuilder()
            chatModelClient.generate(prompt).collect { event ->
                if (event is StreamEvent.TokenDelta) {
                    result.append(event.text)
                }
            }
            return parseTags(result.toString())
        }

        fun buildPrompt(body: String): String =
            "Suggest 3 to 5 concise, single-word or hyphenated tags for the following note. " +
                "Output ONLY a comma-separated list of tags (e.g. android, architecture, compose):\n\n" +
                body.take(MAX_BODY_CHARS)

        fun parseTags(raw: String): List<String> =
            raw
                .split(",", "\n")
                .map {
                    it
                        .trim()
                        .removePrefix("#")
                        .removePrefix("-")
                        .trim()
                        .lowercase()
                }.filter { it.isNotBlank() && it.length < MAX_TAG_LENGTH && it.matches(TAG_REGEX) }
                .distinct()
                .take(MAX_TAGS_SUGGESTED)

        companion object {
            private const val MAX_BODY_CHARS = 2000
            private const val MAX_TAG_LENGTH = 30
            private const val MAX_TAGS_SUGGESTED = 5
            private val TAG_REGEX = Regex("^[a-z0-9_-]+$")
        }
    }
