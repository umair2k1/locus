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

package com.locus.core.domain.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FrontmatterParserTest {
    private val codec = SnakeYamlCodec()
    private val parser = FrontmatterParser(codec)
    private val fallback =
        FileFallbackMetadata(
            fileCreated = Instant.parse("2026-09-01T12:00:00Z"),
            fileModified = Instant.parse("2026-09-02T15:30:00Z"),
            appVersion = "Locus 1.0.0",
        )

    @Test
    fun wellFormedFrontmatterRoundTripsExactly() {
        val original =
            ParsedNote(
                id = "0191ebc2-841e-7b28-b072-46ebc605cf52",
                title = "Trip to Kyoto",
                type = NoteType.NOTE,
                created = Instant.parse("2026-09-10T08:00:00Z"),
                modified = Instant.parse("2026-09-11T09:30:00Z"),
                pinned = true,
                color = "#FFE082",
                tags = listOf("travel", "japan"),
                history = 3,
                checksum = "sha256-abc123456",
                app = "Locus 1.0.0",
                unknownFields = emptyMap(),
                body = "Pack comfortable shoes.\nVisit Fushimi Inari at dawn.",
                wasRepaired = false,
                repairNotes = emptyList(),
            )

        val rendered = parser.render(original)
        val parsed = parser.parse(rendered, fallback)

        assertFalse("Well-formed note must not be marked as repaired", parsed.wasRepaired)
        assertTrue("Repair notes must be empty for well-formed note", parsed.repairNotes.isEmpty())
        assertEquals(original.id, parsed.id)
        assertEquals(original.title, parsed.title)
        assertEquals(original.type, parsed.type)
        assertEquals(original.created, parsed.created)
        assertEquals(original.modified, parsed.modified)
        assertEquals(original.pinned, parsed.pinned)
        assertEquals(original.color, parsed.color)
        assertEquals(original.tags, parsed.tags)
        assertEquals(original.history, parsed.history)
        assertEquals(original.checksum, parsed.checksum)
        assertEquals(original.app, parsed.app)
        assertEquals(original.unknownFields, parsed.unknownFields)
        assertEquals(original.body, parsed.body)

        // Second round trip should match rendered output exactly
        val reRendered = parser.render(parsed)
        assertEquals(rendered, reRendered)
    }

    @Test
    fun missingClosingFenceRepairsWithoutDataLoss() {
        val rawFile =
            """
            ---
            id: 0191ebc2-841e-7b28-b072-46ebc605cf52
            title: Unclosed Fence Note
            type: note
            Here is the body that was never closed with three dashes!
            # Important Heading
            Don't lose this text.
            """.trimIndent()

        val parsed = parser.parse(rawFile, fallback)

        assertTrue(parsed.wasRepaired)
        assertTrue(parsed.repairNotes.any { it.contains("opening fence found but no closing '---' fence") })
        assertEquals(rawFile, parsed.body)
        assertEquals("Important Heading", parsed.title)
        assertEquals(fallback.fileCreated, parsed.created)
        assertEquals(fallback.fileModified, parsed.modified)
        assertNotNull(parsed.id)
    }

    @Test
    fun unknownKeySurvivesParseAndRender() {
        val rawFile =
            """
            ---
            id: 0191ebc2-841e-7b28-b072-46ebc605cf52
            title: Note with Custom Metadata
            type: note
            created: '2026-09-10T08:00:00Z'
            modified: '2026-09-10T08:00:00Z'
            pinned: false
            color: null
            tags: []
            history: 0
            checksum: null
            app: Locus 1.0.0
            custom_field: 42
            author: Ada Lovelace
            ---
            Note body here.
            """.trimIndent()

        val parsed = parser.parse(rawFile, fallback)

        assertFalse(parsed.wasRepaired)
        assertEquals(42, parsed.unknownFields["custom_field"])
        assertEquals("Ada Lovelace", parsed.unknownFields["author"])

        val rendered = parser.render(parsed)
        assertTrue("Rendered frontmatter must contain custom_field: 42", rendered.contains("custom_field: 42"))
        assertTrue("Rendered frontmatter must contain author: Ada Lovelace", rendered.contains("author: Ada Lovelace"))

        // Re-parse to ensure complete preservation
        val reParsed = parser.parse(rendered, fallback)
        assertEquals(42, reParsed.unknownFields["custom_field"])
        assertEquals("Ada Lovelace", reParsed.unknownFields["author"])
        assertEquals("Note body here.", reParsed.body)
    }

    @Test
    fun checklistBodyWithNoTypeFieldInfersChecklist() {
        val rawFile =
            """
            ---
            id: 0191ebc2-841e-7b28-b072-46ebc605cf52
            title: Groceries
            created: '2026-09-10T08:00:00Z'
            modified: '2026-09-10T08:00:00Z'
            ---
            - [ ] Apples
            - [x] Milk
            - [ ] Bread
            """.trimIndent()

        val parsed = parser.parse(rawFile, fallback)

        assertEquals(NoteType.CHECKLIST, parsed.type)
        assertEquals("Groceries", parsed.title)
    }

    @Test
    fun bodyWithHeadingAndNoTitleFieldInfersTitle() {
        val rawFile =
            """
            ---
            id: 0191ebc2-841e-7b28-b072-46ebc605cf52
            type: note
            created: '2026-09-10T08:00:00Z'
            modified: '2026-09-10T08:00:00Z'
            ---
            # Inferred Document Title

            Paragraph of body text.
            """.trimIndent()

        val parsed = parser.parse(rawFile, fallback)

        assertEquals("Inferred Document Title", parsed.title)
    }

    @Test
    fun malformedYamlNeverThrowsAndPreservesBody() {
        val rawFile =
            """
            ---
            : this is [not valid: yaml: {{{:::
              bad indentation
            ---
            Crucial user text that must never be lost.
            More lines of text.
            """.trimIndent()

        val parsed = parser.parse(rawFile, fallback)

        assertTrue(parsed.wasRepaired)
        assertTrue(parsed.repairNotes.any { it.contains("frontmatter block present but not valid YAML") })
        assertEquals("Crucial user text that must never be lost.\nMore lines of text.", parsed.body)
        assertEquals("Untitled", parsed.title)
        assertNotNull(parsed.id)
        assertEquals(fallback.fileCreated, parsed.created)
        assertEquals(fallback.fileModified, parsed.modified)
    }

    @Test
    fun missingFrontmatterParsesSafely() {
        val rawFile =
            """
            # Grocery List
            - [ ] Eggs
            - [ ] Butter
            """.trimIndent()

        val parsed = parser.parse(rawFile, fallback)

        assertTrue(parsed.wasRepaired)
        assertEquals("Grocery List", parsed.title)
        assertEquals(NoteType.CHECKLIST, parsed.type)
        assertEquals(fallback.fileCreated, parsed.created)
        assertEquals(fallback.fileModified, parsed.modified)
        assertEquals(fallback.appVersion, parsed.app)
        assertEquals(rawFile, parsed.body)
    }

    @Test
    fun unrecognizedTypeValueDefaultsToNoteWithRepair() {
        val rawFile =
            """
            ---
            id: 0191ebc2-841e-7b28-b072-46ebc605cf52
            title: Test
            type: unknown_future_type
            created: '2026-09-10T08:00:00Z'
            modified: '2026-09-10T08:00:00Z'
            ---
            Body text
            """.trimIndent()

        val parsed = parser.parse(rawFile, fallback)

        assertTrue(parsed.wasRepaired)
        assertEquals(NoteType.NOTE, parsed.type)
        assertTrue(parsed.repairNotes.any { it.contains("unrecognized type value; defaulted to note") })
    }

    @Test
    fun missingOrBlankIdGeneratesNewId() {
        val rawFile =
            """
            ---
            id: "   "
            title: Empty ID
            type: note
            created: '2026-09-10T08:00:00Z'
            modified: '2026-09-10T08:00:00Z'
            ---
            Body text
            """.trimIndent()

        val parsed = parser.parse(rawFile, fallback)

        assertTrue(parsed.wasRepaired)
        assertTrue(parsed.repairNotes.any { it.contains("id missing/invalid; generated new id") })
        assertTrue(parsed.id.isNotBlank())
    }

    @Test
    fun handEditedUnquotedTimestampParsesWithoutFalseRepair() {
        val rawFile =
            """
            ---
            id: 0191ebc2-841e-7b28-b072-46ebc605cf52
            title: Hand Edited Timestamps
            type: note
            created: 2026-09-10T08:00:00Z
            modified: 2026-09-11T09:30:00Z
            pinned: false
            tags: []
            history: 0
            app: Locus 1.0.0
            ---
            Body content
            """.trimIndent()

        val parsed = parser.parse(rawFile, fallback)

        assertFalse("Hand-edited unquoted ISO-8601 timestamps must not trigger repair", parsed.wasRepaired)
        assertEquals(Instant.parse("2026-09-10T08:00:00Z"), parsed.created)
        assertEquals(Instant.parse("2026-09-11T09:30:00Z"), parsed.modified)
    }
}
