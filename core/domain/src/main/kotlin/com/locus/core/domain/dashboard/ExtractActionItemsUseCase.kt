package com.locus.core.domain.dashboard

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.ModelTier
import com.locus.core.domain.routing.RouteAndSend
import com.locus.core.domain.routing.RouteDecision
import com.locus.core.domain.routing.TaskType
import com.locus.core.domain.time.Clock
import kotlinx.coroutines.flow.first
import java.util.UUID

class ExtractActionItemsUseCase(
    private val noteRepository: NoteRepository,
    private val chatModelClient: ChatModelClient,
    private val clock: Clock,
    private val routeAndSend: RouteAndSend? = null,
) {
    @Suppress("ReturnCount")
    suspend fun execute(
        currentModel: ModelRef? = null,
        confirmCloudTransition: (suspend (RouteDecision.RequiresCloudTransitionConfirmation) -> Boolean)? = null,
    ): ActionItemCard? {
        val notes = noteRepository.observeAllNotes().first()
        if (notes.isEmpty()) return null

        val defaultModel = ModelRef(id = "local-utility", tier = ModelTier.LOCAL, providerId = null)
        val modelToRoute = currentModel ?: defaultModel

        val cardId = UUID.randomUUID().toString()
        val allActionItems = mutableListOf<ActionItem>()

        for (note in notes.take(MAX_NOTES_TO_ANALYZE)) {
            val body = noteRepository.readBody(note.id).take(MAX_BODY_CHARS)
            if (body.isBlank()) continue

            if (routeAndSend != null) {
                routeAndSend.route(
                    task = TaskType.DIGEST_TAGGING_CLUSTER_LABEL,
                    currentModel = modelToRoute,
                    confirmCloudTransition = confirmCloudTransition ?: { true },
                )
            }

            val prompt = buildPrompt(note.title, body)
            val response = generateResponse(prompt)
            val extractedTasks = parseTasks(response)

            for (task in extractedTasks) {
                allActionItems.add(
                    ActionItem(
                        id = UUID.randomUUID().toString(),
                        noteId = note.id,
                        noteTitle = note.title.ifBlank { "Untitled" },
                        task = task,
                    ),
                )
            }
        }

        if (allActionItems.isEmpty()) return null

        return ActionItemCard(
            id = cardId,
            items = allActionItems,
            computedAt = clock.now(),
        )
    }

    private fun buildPrompt(
        title: String,
        body: String,
    ): String =
        "Extract actionable to-do items from this note. " +
            "Output each action item on a new line starting with '- '. If none, output nothing.\n\n" +
            "Title: $title\nBody:\n$body"

    private suspend fun generateResponse(prompt: String): String {
        val sb = StringBuilder()
        try {
            chatModelClient.generate(prompt).collect { event ->
                if (event is StreamEvent.TokenDelta) {
                    sb.append(event.text)
                }
            }
        } catch (_: Exception) {
            // return accumulated
        }
        return sb.toString()
    }

    private fun parseTasks(raw: String): List<String> =
        raw
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("[] ") || it.startsWith("[ ] ") }
            .map {
                it
                    .removePrefix("- ")
                    .removePrefix("* ")
                    .removePrefix("[] ")
                    .removePrefix("[ ] ")
                    .trim()
            }.filter { it.isNotBlank() }
            .take(MAX_TASKS_PER_NOTE)

    companion object {
        private const val MAX_NOTES_TO_ANALYZE = 10
        private const val MAX_BODY_CHARS = 1500
        private const val MAX_TASKS_PER_NOTE = 5
    }
}
