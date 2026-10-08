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

package com.locus.core.domain.dashboard

import com.locus.core.domain.chat.ChatModelClient
import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.routing.ModelRef
import com.locus.core.domain.routing.RouteAndSend
import com.locus.core.domain.routing.RouteDecision
import com.locus.core.domain.routing.TaskType
import com.locus.core.domain.time.Clock
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComputeDigestUseCase
    @Inject
    constructor(
        private val noteRepository: NoteRepository,
        private val chatModelClient: ChatModelClient,
        private val clock: Clock,
        private val routeAndSend: RouteAndSend? = null,
    ) {
        suspend fun execute(
            period: DigestPeriod,
            currentModel: ModelRef? = null,
            confirmCloudTransition: (suspend (RouteDecision.RequiresCloudTransitionConfirmation) -> Boolean)? = null,
        ): DigestCard? {
            val now = clock.now()
            val cutoff = computeCutoff(period, now)

            val notes =
                noteRepository
                    .observeAllNotes()
                    .first()
                    .filter { !it.modified.isBefore(cutoff) }

            if (notes.isEmpty()) return null

            if (routeAndSend != null && currentModel != null && confirmCloudTransition != null) {
                routeAndSend.route(
                    task = TaskType.DIGEST_TAGGING_CLUSTER_LABEL,
                    currentModel = currentModel,
                    confirmCloudTransition = confirmCloudTransition,
                )
            }

            val items = mutableListOf<DigestItem>()
            val noteBulletPoints = StringBuilder()
            for (note in notes) {
                val body = noteRepository.readBody(note.id).take(MAX_BODY_CHARS)
                val itemSummary =
                    if (body.isNotBlank()) {
                        body.lines().firstOrNull { it.isNotBlank() } ?: note.title
                    } else {
                        note.title
                    }
                items.add(
                    DigestItem(
                        noteId = note.id,
                        noteTitle = note.title.ifBlank { "Untitled" },
                        summary = itemSummary,
                    ),
                )
                noteBulletPoints.appendLine("- ${note.title}: ${body.take(SUMMARY_SNIPPET_LIMIT)}")
            }

            val prompt =
                "Summarize these recently updated notes into an executive digest " +
                    "for period ${period.name.lowercase()}:\n\n" +
                    noteBulletPoints.toString()

            val overallSummary = generateSummary(prompt)

            return DigestCard(
                id = UUID.randomUUID().toString(),
                period = period,
                items = items,
                overallSummary =
                    overallSummary.ifBlank {
                        "Updated ${items.size} note(s) during this period."
                    },
                computedAt = now,
            )
        }

        private suspend fun generateSummary(prompt: String): String {
            val sb = StringBuilder()
            chatModelClient.generate(prompt).collect { event ->
                if (event is StreamEvent.TokenDelta) {
                    sb.append(event.text)
                }
            }
            return sb.toString().trim()
        }

        internal fun computeCutoff(
            period: DigestPeriod,
            now: Instant,
        ): Instant =
            when (period) {
                DigestPeriod.DAILY -> now.minusSeconds(SECONDS_PER_DAY)
                DigestPeriod.WEEKLY -> now.minusSeconds(SECONDS_PER_WEEK)
                DigestPeriod.MONTHLY -> now.minusSeconds(SECONDS_PER_MONTH)
            }

        companion object {
            private const val SECONDS_PER_DAY = 86_400L
            private const val SECONDS_PER_WEEK = 7 * 86_400L
            private const val SECONDS_PER_MONTH = 30 * 86_400L
            private const val MAX_BODY_CHARS = 1000
            private const val SUMMARY_SNIPPET_LIMIT = 200
        }
    }
