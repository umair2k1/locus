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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NoteChunkerTest {
    private val chunker = NoteChunker()

    @Test
    fun nestedHeadings_producesCorrectAncestorChain() {
        val body =
            """
            Preamble text before any heading.
            # Overview
            Section overview content.
            ## Details A
            Sub-section A details.
            ### Deep Dive
            Deep dive inside A.
            ## Details B
            Sibling section B details.
            """.trimIndent()

        val chunks = chunker.chunk(noteId = "note-1", noteTitle = "My Note", body = body)

        assertEquals(5, chunks.size)

        // Preamble
        assertEquals(emptyList<String>(), chunks[0].headingPath)
        assertEquals("Preamble text before any heading.", chunks[0].text)
        assertEquals(0, chunks[0].index)
        assertEquals("note-1", chunks[0].noteId)
        assertEquals("My Note", chunks[0].noteTitle)

        // # Overview
        assertEquals(listOf("Overview"), chunks[1].headingPath)
        assertEquals("Section overview content.", chunks[1].text)
        assertEquals(1, chunks[1].index)

        // ## Details A
        assertEquals(listOf("Overview", "Details A"), chunks[2].headingPath)
        assertEquals("Sub-section A details.", chunks[2].text)
        assertEquals(2, chunks[2].index)

        // ### Deep Dive
        assertEquals(listOf("Overview", "Details A", "Deep Dive"), chunks[3].headingPath)
        assertEquals("Deep dive inside A.", chunks[3].text)
        assertEquals(3, chunks[3].index)

        // ## Details B (stack popped back to Overview -> Details B)
        assertEquals(listOf("Overview", "Details B"), chunks[4].headingPath)
        assertEquals("Sibling section B details.", chunks[4].text)
        assertEquals(4, chunks[4].index)
    }

    @Test
    fun sectionLongerThan500Words_producesOverlappingConsecutiveChunks() {
        // Generate a 600-word section under a single heading
        val totalWords = 600
        val wordList = (1..totalWords).map { "token$it" }
        val body = "# Long Section\n" + wordList.joinToString(" ")

        val chunks = chunker.chunk(noteId = "note-2", noteTitle = "Long Note", body = body)

        assertEquals(2, chunks.size)

        val chunk0Words = chunks[0].text.split(" ")
        val chunk1Words = chunks[1].text.split(" ")

        // Default targetTokens = 500, overlapFraction = 0.15
        // step = 500 * (1 - 0.15) = 425
        // Chunk 0: words 0..499 (500 tokens)
        // Chunk 1: words 425..599 (175 tokens)
        assertEquals(500, chunk0Words.size)
        assertEquals(175, chunk1Words.size)

        // Overlap region: words 425..499 (75 tokens)
        val overlapWords = chunk0Words.intersect(chunk1Words.toSet())
        assertEquals(75, overlapWords.size)

        val overlapRatio = overlapWords.size.toDouble() / 500.0
        assertTrue(
            "Overlap ratio should be ~15% (actual: $overlapRatio)",
            abs(overlapRatio - 0.15) < 0.01,
        )

        assertEquals(0, chunks[0].index)
        assertEquals(1, chunks[1].index)
        assertEquals(listOf("Long Section"), chunks[0].headingPath)
        assertEquals(listOf("Long Section"), chunks[1].headingPath)
    }

    @Test
    fun bodyWithNoHeadings_producesOneSection() {
        val body = "This is a simple note body with no headings at all."

        val chunks = chunker.chunk(noteId = "note-3", noteTitle = "Simple Note", body = body)

        assertEquals(1, chunks.size)
        assertEquals(emptyList<String>(), chunks[0].headingPath)
        assertEquals(body, chunks[0].text)
        assertEquals(0, chunks[0].index)
        assertEquals("note-3", chunks[0].noteId)
        assertEquals("Simple Note", chunks[0].noteTitle)
    }

    @Test
    fun emptyOrBlankBody_producesEmptyChunkList() {
        assertTrue(chunker.chunk("n1", "t1", "").isEmpty())
        assertTrue(chunker.chunk("n2", "t2", "   \n\t  \n  ").isEmpty())
    }
}
