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

package com.locus.core.domain.search

/**
 * Heading-aware chunker (S-3): splits a note body on markdown headings first, then packs each section
 * into ~[targetTokens]-token windows with ~[overlapFraction] overlap between consecutive windows in the
 * same section. Token count is approximated by whitespace-word count.
 */
class NoteChunker(
    private val targetTokens: Int = 500,
    private val overlapFraction: Double = 0.15,
) {
    private val headingRegex = Regex("^(#{1,6})\\s+(.+?)\\s*$", RegexOption.MULTILINE)

    fun chunk(
        noteId: String,
        noteTitle: String,
        body: String,
    ): List<Chunk> {
        val sections = splitByHeading(body)
        val chunks = mutableListOf<Chunk>()
        var index = 0
        for (section in sections) {
            val words = section.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            val step = maxOf(1, (targetTokens * (1 - overlapFraction)).toInt())
            var start = 0
            while (start < words.size) {
                val end = minOf(start + targetTokens, words.size)
                chunks +=
                    Chunk(
                        noteId = noteId,
                        noteTitle = noteTitle,
                        headingPath = section.path,
                        text = words.subList(start, end).joinToString(" "),
                        index = index++,
                    )
                if (end == words.size) break
                start += step
            }
        }
        return chunks
    }

    private data class Section(
        val path: List<String>,
        val text: String,
    )

    private fun splitByHeading(body: String): List<Section> {
        val matches = headingRegex.findAll(body).toList()
        if (matches.isEmpty()) return listOf(Section(emptyList(), body))
        val sections = mutableListOf<Section>()
        val stack = mutableListOf<Pair<Int, String>>()
        if (matches.first().range.first > 0) {
            sections += Section(emptyList(), body.substring(0, matches.first().range.first))
        }
        for ((i, m) in matches.withIndex()) {
            val level = m.groupValues[1].length
            val title = m.groupValues[2]
            while (stack.isNotEmpty() && stack.last().first >= level) stack.removeAt(stack.lastIndex)
            stack += level to title
            val contentStart = m.range.last + 1
            val contentEnd = if (i + 1 < matches.size) matches[i + 1].range.first else body.length
            sections += Section(stack.map { it.second }, body.substring(contentStart, contentEnd))
        }
        return sections
    }
}
