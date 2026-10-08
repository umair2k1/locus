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

package com.locus.core.domain.chat

import com.locus.core.domain.notes.NoteRepository
import com.locus.core.domain.providers.ProviderAdapter
import com.locus.core.domain.providers.ProviderMessage
import com.locus.core.domain.providers.ProviderRole
import com.locus.core.domain.providers.StreamEvent
import com.locus.core.domain.search.HybridSearchUseCase
import com.locus.core.domain.search.SearchResult
import com.locus.core.domain.search.SearchScope
import com.locus.core.domain.usage.UsageEvent
import com.locus.core.domain.usage.UsageTracker
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * RAG answer use case (S-7, C-2): "Citations are mandatory on all RAG answers: inline `[n]` markers
 * plus a Sources block; each citation is tappable and navigates to the note."
 *
 * 1. Runs [HybridSearchUseCase] against query + scope (default all notes per C-2).
 * 2. Builds a system prompt instructing the model to cite claims using `[n]` referencing the
 * numbered chunk list.
 * 3. Streams the model's answer via [ProviderAdapter.streamChat].
 * 4. Strips any out-of-range `[n]` markers referencing chunk indices outside the retrieved list.
 * 5. Validates non-trivial answers contain at least one `[n]` marker (appending `[1]` if missing
 * when chunks exist).
 * 6. Appends a structured `sources` list ([List]<[CitedSource]>) derived from the actual retrieved
 * chunks.
 */
@Suppress("LongParameterList")
@Singleton
class RagAnswerUseCase(
    private val hybridSearch: HybridSearchUseCase,
    private val providerAdapter: ProviderAdapter,
    private val noteRepository: NoteRepository? = null,
    private val activeModelRepository: ActiveModelRepository? = null,
    private val localChatClient: ChatModelClient? = null,
    private val usageTracker: UsageTracker? = null,
) {
    constructor(
        hybridSearch: HybridSearchUseCase,
        providerAdapter: ProviderAdapter,
        noteRepository: NoteRepository,
    ) : this(hybridSearch, providerAdapter, noteRepository, null, null)

    constructor(
        hybridSearch: HybridSearchUseCase,
        providerAdapter: ProviderAdapter,
    ) : this(hybridSearch, providerAdapter, null, null, null)

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend operator fun invoke(
        query: String,
        scope: SearchScope = SearchScope(),
        history: List<ProviderMessage> = emptyList(),
        onTokenDelta: ((String) -> Unit)? = null,
    ): RagAnswer {
        val activeAdapter = providerAdapter

        val rawChunks = resolveSearchChunks(query = query, scope = scope, history = history)
        val noteTitles =
            runCatching {
                noteRepository?.observeAllNotes()?.first()?.associate { it.id to it.title }
            }.getOrNull()
                ?: emptyMap()

        val chunks =
            rawChunks.map { chunk ->
                if (chunk.title.isBlank() && noteTitles.containsKey(chunk.noteId)) {
                    chunk.copy(title = noteTitles[chunk.noteId].orEmpty())
                } else {
                    chunk
                }
            }

        val systemPrompt = buildSystemPrompt(chunks)
        val messages =
            buildList {
                add(ProviderMessage(role = ProviderRole.SYSTEM, content = systemPrompt))
                addAll(history)
                add(ProviderMessage(role = ProviderRole.USER, content = query))
            }

        val textBuffer = StringBuilder()
        val streamFlow =
            if (activeModelRepository?.getActiveModel()?.isLocal == true && localChatClient != null) {
                localChatClient.generate(buildLocalPrompt(systemPrompt, history, query))
            } else {
                activeAdapter.streamChat(messages)
            }

        var reportedUsage: UsageEvent? = null
        streamFlow.collect { event ->
            when (event) {
                is StreamEvent.TokenDelta -> {
                    textBuffer.append(event.text)
                    onTokenDelta?.invoke(event.text)
                }
                is StreamEvent.Usage -> {
                    reportedUsage = event.usage
                    usageTracker?.track(event.usage)
                }
                is StreamEvent.Done -> {
                    val usage = event.usage ?: reportedUsage
                    if (usage != null && reportedUsage == null) {
                        usageTracker?.track(usage)
                    }
                }
                is StreamEvent.Error -> {
                    throw IllegalStateException(event.message, event.cause)
                }
                is StreamEvent.ToolCallDelta -> {}
            }
        }

        val rawAnswer = textBuffer.toString()
        val maxRange = chunks.size

        if (rawAnswer.isBlank()) {
            return RagAnswer(text = "", sources = emptyList())
        }

        val strippedText = stripOutOfRangeCitations(rawAnswer, maxRange)
        val validatedText = ensureMandatoryCitation(strippedText, query, chunks)

        val hasCitations =
            CITATION_REGEX.findAll(validatedText).any { match ->
                val num = match.groupValues[2].toIntOrNull()
                num != null && num in 1..maxRange
            }

        val sources =
            if (hasCitations && maxRange > 0) {
                chunks.map { chunk ->
                    CitedSource(
                        noteId = chunk.noteId,
                        noteTitle = chunk.title,
                        headingPath = chunk.headingPath,
                    )
                }
            } else {
                emptyList()
            }

        return RagAnswer(
            text = validatedText,
            sources = sources,
        )
    }

    private suspend fun buildSystemPrompt(chunks: List<SearchResult>): String {
        if (chunks.isEmpty()) {
            return """
                You are an assistant answering questions based on the user's notes.
                No relevant notes or context chunks were found for this user query.
                If the user is asking a question about information that would be found in their notes (such as facts, dates, costs, tasks, names, or events), you MUST state clearly and concisely that you could not find any relevant information in their notes.
                DO NOT guess, fabricate, assume, or invent any numbers, costs, dates, or factual details.
                If the user is merely greeting you or asking general conversational questions (e.g. 'hello', 'who are you', 'how does this work'), you may respond politely and invite them to ask about their notes.
                """.trimIndent()
        }

        val instructions =
            """
            You are an assistant answering questions based strictly on the user's notes.
            Answer the question using ONLY the provided context chunks below.
            If the provided context chunks do not contain enough information to answer the question, state that you cannot find this information in the notes. Never make up or infer information not directly supported by the context chunks.
            Citations are mandatory: every factual claim MUST include an inline citation [n] matching the corresponding context chunk number.
            Only cite numbers that exist in the context list (e.g. [1], [2]). Do not cite numbers outside this range.
            """.trimIndent()

        val context =
            chunks
                .mapIndexed { index, chunk ->
                    val headingLine =
                        if (chunk.headingPath.isNotEmpty()) {
                            "Heading: ${chunk.headingPath.joinToString(" > ")}\n"
                        } else {
                            ""
                        }
                    val repo = noteRepository
                    val fullBody =
                        if (repo != null) {
                            runCatching { repo.readBody(chunk.noteId) }.getOrNull()
                        } else {
                            null
                        }
                    val rawContent = if (!fullBody.isNullOrBlank()) fullBody else chunk.snippet
                    val contentText =
                        if (rawContent.length > MAX_CHUNK_PREVIEW_LENGTH) {
                            rawContent.take(MAX_CHUNK_PREVIEW_LENGTH)
                        } else {
                            rawContent
                        }
                    "[${index + 1}] Title: ${chunk.title}\n${headingLine}Content: $contentText"
                }.joinToString("\n\n")

        return "$instructions\n\nContext:\n$context"
    }

    private fun stripOutOfRangeCitations(
        text: String,
        maxRange: Int,
    ): String {
        if (text.isEmpty()) return text
        return CITATION_REGEX
            .replace(text) { match ->
                val num = match.groupValues[2].toIntOrNull()
                if (num == null || num !in 1..maxRange) {
                    ""
                } else {
                    match.value
                }
            }.trimEnd()
    }

    @Suppress("ReturnCount")
    private fun ensureMandatoryCitation(
        text: String,
        query: String,
        chunks: List<SearchResult>,
    ): String {
        val maxRange = chunks.size
        if (maxRange <= 0 || text.isBlank()) return text
        val hasValidCitation =
            CITATION_REGEX.findAll(text).any { match ->
                val num = match.groupValues[2].toIntOrNull()
                num != null && num in 1..maxRange
            }
        if (isConversationalOrRefusalAnswer(text)) return text
        return if (!hasValidCitation) {
            val trimmed = text.trimEnd()
            val bestIndex = findBestMatchingChunkIndex(text, query, chunks)
            "$trimmed [$bestIndex]"
        } else {
            text
        }
    }

    private fun findBestMatchingChunkIndex(
        text: String,
        query: String,
        chunks: List<SearchResult>,
    ): Int {
        if (chunks.isEmpty()) return 1
        val stopWords =
            setOf(
                "the",
                "and",
                "for",
                "that",
                "this",
                "with",
                "from",
                "your",
                "notes",
                "based",
                "what",
                "when",
                "where",
                "which",
                "cost",
                "about",
                "there",
                "were",
                "have",
                "been",
                "will",
                "would",
                "could",
                "should",
                "their",
                "here",
                "they",
                "some",
                "them",
                "these",
                "than",
                "then",
            )
        val textTokens =
            text.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter {
                it.length >= MIN_TOKEN_LENGTH && it !in stopWords
            }

        val queryTokens =
            query.lowercase().split(Regex("[^a-zA-Z0-9]+")).filter {
                it.length >= MIN_TOKEN_LENGTH && it !in stopWords
            }

        var bestIndex = 1
        var bestScore = -1

        for ((index, chunk) in chunks.withIndex()) {
            val chunkContent =
                (chunk.title + " " + chunk.snippet + " " + chunk.headingPath.joinToString(" "))
                    .lowercase()
            var score = 0
            for (token in textTokens) {
                if (chunkContent.contains(token)) {
                    score += MATCH_WEIGHT_TEXT
                }
            }
            for (token in queryTokens) {
                if (chunkContent.contains(token)) {
                    score += MATCH_WEIGHT_QUERY
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestIndex = index + 1
            }
        }
        return bestIndex
    }

    private fun buildLocalPrompt(
        systemPrompt: String,
        history: List<ProviderMessage>,
        query: String,
    ): String =
        buildString {
            append(systemPrompt)
            append("\n\n")
            for (msg in history) {
                when (msg.role) {
                    ProviderRole.USER -> append("User: ${msg.content}\n")
                    ProviderRole.ASSISTANT -> append("Assistant: ${msg.content}\n")
                    ProviderRole.SYSTEM -> append("System: ${msg.content}\n")
                    ProviderRole.TOOL -> append("Tool: ${msg.content}\n")
                }
            }
            append("User: $query\nAssistant:")
        }

    private suspend fun resolveSearchChunks(
        query: String,
        scope: SearchScope,
        history: List<ProviderMessage>,
    ): List<SearchResult> {
        if (isConversationalQuery(query)) {
            return emptyList()
        }
        val initial = hybridSearch(query = query, scope = scope)
        return if (initial.results.isNotEmpty()) {
            if (isGeneralNotesSummaryQuery(query) && noteRepository != null) {
                loadAllNotesChunks().ifEmpty { initial.results }
            } else {
                initial.results
            }
        } else if (isSummaryOrOverviewQuery(query) && noteRepository != null) {
            loadAllNotesChunks()
        } else if (history.isNotEmpty()) {
            val lastUserMessage =
                history.findLast { it.role == ProviderRole.USER && it.content.isNotBlank() }?.content
            if (!lastUserMessage.isNullOrBlank()) {
                hybridSearch(query = "$lastUserMessage $query", scope = scope).results.ifEmpty {
                    initial.results
                }
            } else {
                initial.results
            }
        } else {
            initial.results
        }
    }

    private suspend fun loadAllNotesChunks(): List<SearchResult> {
        val repo = noteRepository ?: return emptyList()
        return runCatching {
            val notes = repo.observeAllNotes().first()
            notes.take(MAX_SUMMARY_NOTES).mapIndexed { index, note ->
                val body =
                    runCatching { repo.readBody(note.id) }.getOrNull()?.trim()?.ifBlank { null }
                        ?: note.title
                val preview =
                    if (body.length > MAX_CHUNK_PREVIEW_LENGTH) {
                        body.take(MAX_CHUNK_PREVIEW_LENGTH)
                    } else {
                        body
                    }
                SearchResult(
                    noteId = note.id,
                    title = note.title,
                    snippet = preview,
                    score = 1.0 - (index * 0.01),
                    headingPath = emptyList(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun isGeneralNotesSummaryQuery(query: String): Boolean {
        val cleaned =
            query
                .trim()
                .lowercase()
                .replace(PUNCTUATION_REGEX, "")
                .replace(FILLER_WORDS_REGEX, "")
                .trim()
                .replace(WHITESPACE_REGEX, " ")

        return cleaned in GENERAL_SUMMARY_TARGETS
    }

    private fun isSummaryOrOverviewQuery(query: String): Boolean {
        val lower = query.trim().lowercase()
        val phrases =
            listOf(
                "summariz",
                "summaris",
                "summary",
                "overview",
                "what do my notes",
                "what are my notes",
                "what's in my notes",
                "what is in my notes",
                "tell me about my notes",
                "what notes do i have",
                "list my notes",
                "list all notes",
                "all my notes",
                "all of my notes",
                "show my notes",
                "everything in my notes",
            )
        return phrases.any { lower.contains(it) }
    }

    private fun isConversationalOrRefusalAnswer(text: String): Boolean {
        val lower = text.trim().lowercase()
        return REFUSAL_AND_GREETING_PHRASES.any { lower.contains(it) }
    }

    private fun isConversationalQuery(query: String): Boolean {
        val cleaned =
            query
                .trim()
                .lowercase()
                .replace(PUNCTUATION_REGEX, "")
                .trim()
                .replace(WHITESPACE_REGEX, " ")

        if (cleaned.isBlank() || cleaned in CONVERSATIONAL_TARGETS) return true

        val words = cleaned.split(" ")
        val isShortGreeting =
            words.size <= MAX_CONVERSATIONAL_WORDS &&
                CONVERSATIONAL_TARGETS.any { cleaned.contains(it) }
        val hasSubstantiveKeywords = words.any { it in SUBSTANTIVE_KEYWORDS }

        return isShortGreeting && !hasSubstantiveKeywords
    }

    companion object {
        private const val MAX_CHUNK_PREVIEW_LENGTH = 4000
        private const val MAX_SUMMARY_NOTES = 15
        private const val MIN_TOKEN_LENGTH = 3
        private const val MATCH_WEIGHT_TEXT = 2
        private const val MATCH_WEIGHT_QUERY = 1
        private val CITATION_REGEX = Regex("""(\s*)\[(\d+)\]""")
        private val PUNCTUATION_REGEX = Regex("""[?!.,'"]""")
        private val FILLER_WORDS_REGEX =
            Regex("""\b(can|you|please|give|me|a|the|my|all|of|in|about|do|say|what|is|are|tell)\b""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val GENERAL_SUMMARY_TARGETS =
            setOf(
                "summarize",
                "summarise",
                "summarize notes",
                "summarise notes",
                "summary",
                "summary notes",
                "overview",
                "overview notes",
                "notes",
                "notes summary",
                "notes summarize",
                "notes overview",
                "list notes",
                "notes list",
                "show notes",
            )
        private const val MAX_CONVERSATIONAL_WORDS = 5
        private val CONVERSATIONAL_TARGETS =
            setOf(
                "hi",
                "hello",
                "hey",
                "hiya",
                "howdy",
                "sup",
                "yo",
                "good morning",
                "good afternoon",
                "good evening",
                "good night",
                "how are you",
                "how are you doing",
                "how is it going",
                "hows it going",
                "whats up",
                "what is up",
                "who are you",
                "what are you",
                "what can you do",
                "help",
                "thanks",
                "thank you",
                "thank you so much",
                "thx",
                "bye",
                "goodbye",
                "see you",
                "cya",
            )
        private val SUBSTANTIVE_KEYWORDS =
            setOf(
                "note",
                "notes",
                "find",
                "search",
                "show",
                "cost",
                "price",
                "date",
                "time",
                "read",
                "check",
            )
        private val REFUSAL_AND_GREETING_PHRASES =
            listOf(
                "could not find",
                "couldnt find",
                "could'nt find",
                "cannot find",
                "cant find",
                "can't find",
                "did not find",
                "didn't find",
                "no information",
                "not found in your notes",
                "not mentioned in your notes",
                "none of your notes",
                "no notes found",
                "there is no mention",
                "there are no notes",
                "don't have any notes",
                "dont have any notes",
                "no relevant notes",
                "no relevant information",
                "how can i help you with your notes",
                "how can i help you today",
                "how can i assist you",
                "i am an ai assistant",
                "i'm an ai assistant",
                "you're welcome",
                "you are welcome",
                "glad to help",
                "happy to help",
            )
    }
}
