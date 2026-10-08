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

package com.locus.core.data.search

import androidx.sqlite.db.SimpleSQLiteQuery
import com.locus.core.data.db.NoteDao
import com.locus.core.domain.search.KeywordSearch
import com.locus.core.domain.search.SearchResult
import com.locus.core.domain.search.SearchScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomKeywordSearch
    @Inject
    constructor(
        private val noteDao: NoteDao,
    ) : KeywordSearch {
        override suspend fun search(
            query: String,
            scope: SearchScope,
        ): List<SearchResult> {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return emptyList()

            val entities = findMatchingEntities(trimmed, scope)
            return toSearchResults(entities)
        }

        private suspend fun findMatchingEntities(
            trimmed: String,
            scope: SearchScope,
        ): List<com.locus.core.data.db.NoteIndexEntity> {
            val rawTokens = extractTokens(trimmed)
            if (rawTokens.isEmpty()) return emptyList()

            val contentTokens = rawTokens.filter { it.lowercase() !in STOP_WORDS }
            val effectiveTokens = if (contentTokens.isNotEmpty()) contentTokens else rawTokens

            val exactMatches = if (trimmed.contains("\"")) runQuerySafe(trimmed, scope) else emptyList()
            val andMatches =
                exactMatches.ifEmpty { runQuerySafe(rawTokens.joinToString(" ") { "$it*" }, scope) }
            val contentMatches =
                andMatches.ifEmpty {
                    if (contentTokens.isNotEmpty() && contentTokens.size < rawTokens.size) {
                        runQuerySafe(contentTokens.joinToString(" ") { "$it*" }, scope)
                    } else {
                        emptyList()
                    }
                }
            val orMatches =
                contentMatches.ifEmpty {
                    val matches =
                        runQuerySafe(effectiveTokens.joinToString(" OR ") { "$it*" }, scope)
                    matches.sortedByDescending { entity ->
                        val text = "${entity.title} ${entity.bodyPreview}".lowercase()
                        effectiveTokens.count { token -> text.contains(token.lowercase()) }
                    }
                }

            return orMatches
        }

        private suspend fun runQuerySafe(
            matchQuery: String,
            scope: SearchScope,
        ): List<com.locus.core.data.db.NoteIndexEntity> =
            try {
                noteDao.ftsSearchScoped(buildFtsQuery(matchQuery, scope))
            } catch (_: Exception) {
                emptyList()
            }

        private fun toSearchResults(entities: List<com.locus.core.data.db.NoteIndexEntity>): List<SearchResult> =
            entities.mapIndexed { index, entity ->
                SearchResult(
                    noteId = entity.id,
                    title = entity.title,
                    snippet = entity.bodyPreview,
                    score = 1.0 / (index + 1.0),
                )
            }

        private fun extractTokens(query: String): List<String> =
            query.split(Regex("[^\\p{L}\\p{N}]+")).filter {
                it.isNotBlank() && it.uppercase() !in FTS_OPERATORS
            }

        private fun buildFtsQuery(
            matchQuery: String,
            scope: SearchScope,
        ): SimpleSQLiteQuery {
            val sql =
                StringBuilder(
                    """
                    SELECT note_index.*
                    FROM note_index
                    JOIN note_fts ON note_index.rowid = note_fts.rowid
                    WHERE note_fts MATCH ?
                    """.trimIndent(),
                )
            val args = mutableListOf<Any>(matchQuery)

            if (scope.folderPaths.isNotEmpty()) {
                val folderClauses = mutableListOf<String>()
                for (folder in scope.folderPaths) {
                    val normalized = normalizeFolder(folder)
                    if (normalized.isEmpty()) {
                        folderClauses.add("note_index.folderPath = ''")
                    } else {
                        folderClauses.add("(note_index.folderPath = ? OR note_index.folderPath LIKE ?)")
                        args.add(normalized)
                        args.add("$normalized/%")
                    }
                }
                if (folderClauses.isNotEmpty()) {
                    sql.append(" AND (").append(folderClauses.joinToString(" OR ")).append(")")
                }
            }

            if (scope.noteIds.isNotEmpty()) {
                val placeholders = scope.noteIds.joinToString(",") { "?" }
                sql.append(" AND note_index.id IN (").append(placeholders).append(")")
                args.addAll(scope.noteIds)
            }

            val after = scope.after
            if (after != null) {
                sql.append(" AND note_index.modified >= ?")
                args.add(after.toEpochMilli())
            }

            val before = scope.before
            if (before != null) {
                sql.append(" AND note_index.modified <= ?")
                args.add(before.toEpochMilli())
            }

            return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
        }

        private fun normalizeFolder(path: String): String = path.trim().trim('/')

        companion object {
            private val FTS_OPERATORS = setOf("AND", "OR", "NOT")
            private val STOP_WORDS =
                setOf(
                    "a",
                    "about",
                    "above",
                    "after",
                    "again",
                    "against",
                    "all",
                    "am",
                    "an",
                    "and",
                    "any",
                    "are",
                    "as",
                    "at",
                    "be",
                    "because",
                    "been",
                    "before",
                    "being",
                    "below",
                    "between",
                    "both",
                    "but",
                    "by",
                    "can",
                    "could",
                    "did",
                    "do",
                    "does",
                    "doing",
                    "down",
                    "during",
                    "each",
                    "few",
                    "for",
                    "from",
                    "further",
                    "had",
                    "has",
                    "have",
                    "having",
                    "he",
                    "her",
                    "here",
                    "hers",
                    "herself",
                    "him",
                    "himself",
                    "his",
                    "how",
                    "i",
                    "if",
                    "in",
                    "into",
                    "is",
                    "it",
                    "its",
                    "itself",
                    "just",
                    "me",
                    "more",
                    "most",
                    "my",
                    "myself",
                    "no",
                    "nor",
                    "not",
                    "now",
                    "of",
                    "off",
                    "on",
                    "once",
                    "only",
                    "or",
                    "other",
                    "our",
                    "ours",
                    "ourselves",
                    "out",
                    "over",
                    "own",
                    "same",
                    "she",
                    "should",
                    "so",
                    "some",
                    "such",
                    "than",
                    "that",
                    "the",
                    "their",
                    "theirs",
                    "them",
                    "themselves",
                    "then",
                    "there",
                    "these",
                    "they",
                    "this",
                    "those",
                    "through",
                    "to",
                    "too",
                    "under",
                    "until",
                    "up",
                    "very",
                    "was",
                    "we",
                    "were",
                    "what",
                    "when",
                    "where",
                    "which",
                    "while",
                    "who",
                    "whom",
                    "why",
                    "will",
                    "with",
                    "would",
                    "you",
                    "your",
                    "yours",
                    "yourself",
                    "yourselves",
                )
        }
    }
