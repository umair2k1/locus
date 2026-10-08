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
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class NoteMapperTest {
    @Test
    fun toDomain_mapsAllFieldsCorrectly() {
        val now = Instant.parse("2026-09-16T12:00:00Z")
        val parsed =
            ParsedNote(
                id = "0191ebc2-841e-7b28-b072-46ebc605cf52",
                title = "Test Note",
                type = NoteType.NOTE,
                created = now,
                modified = now.plusSeconds(60),
                pinned = true,
                color = "#FFD54F",
                tags = listOf("work", "project"),
                history = 1,
                checksum = "sha256-existing-checksum",
                app = "Locus 1.0.0",
                unknownFields = emptyMap(),
                body = "This is the body content.",
                wasRepaired = false,
                repairNotes = emptyList(),
            )

        val note = parsed.toDomain(folderPath = "Personal/Ideas")

        assertEquals("0191ebc2-841e-7b28-b072-46ebc605cf52", note.id)
        assertEquals("Test Note", note.title)
        assertEquals(NoteType.NOTE, note.type)
        assertEquals("Personal/Ideas", note.folderPath)
        assertEquals(true, note.pinned)
        assertEquals("#FFD54F", note.color)
        assertEquals(listOf("work", "project"), note.tags)
        assertEquals(now, note.created)
        assertEquals(now.plusSeconds(60), note.modified)
        assertEquals("sha256-existing-checksum", note.checksum)
    }

    @Test
    fun toDomain_computesChecksumWhenNullInParsedNote() {
        val now = Instant.now()
        val bodyText = "# Shopping List\n- [ ] Milk\n- [ ] Bread"
        val parsed =
            ParsedNote(
                id = "0191ebc2-841e-7b28-b072-46ebc605cf53",
                title = "Shopping List",
                type = NoteType.CHECKLIST,
                created = now,
                modified = now,
                pinned = false,
                color = null,
                tags = emptyList(),
                history = 0,
                checksum = null,
                app = "Locus 1.0.0",
                unknownFields = emptyMap(),
                body = bodyText,
                wasRepaired = false,
                repairNotes = emptyList(),
            )

        val note = parsed.toDomain(folderPath = "")

        assertEquals("", note.folderPath)
        assertNull(note.color)
        assertEquals(Checksum.sha256(bodyText), note.checksum)
    }
}
