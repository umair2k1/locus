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

import com.locus.core.domain.search.Chunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NoteTest {
    @Test
    fun createNote_holdsExpectedFields() {
        val created = Instant.parse("2026-09-16T12:00:00Z")
        val modified = Instant.parse("2026-09-16T12:30:00Z")
        val note =
            Note(
                id = "0191eb70-0000-7000-8000-000000000001",
                title = "My First Note",
                type = NoteType.NOTE,
                folderPath = "Personal/Ideas",
                pinned = true,
                color = "coral",
                tags = listOf("idea", "work"),
                created = created,
                modified = modified,
                checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            )

        assertEquals("0191eb70-0000-7000-8000-000000000001", note.id)
        assertEquals("My First Note", note.title)
        assertEquals(NoteType.NOTE, note.type)
        assertEquals("Personal/Ideas", note.folderPath)
        assertTrue(note.pinned)
        assertEquals("coral", note.color)
        assertEquals(listOf("idea", "work"), note.tags)
        assertEquals(created, note.created)
        assertEquals(modified, note.modified)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", note.checksum)
    }

    @Test
    fun noteType_hasBothNoteAndChecklist() {
        assertEquals(NoteType.NOTE, NoteType.valueOf("NOTE"))
        assertEquals(NoteType.CHECKLIST, NoteType.valueOf("CHECKLIST"))
    }

    @Test
    fun chunk_holdsExpectedFields() {
        val chunk =
            Chunk(
                noteId = "note-123",
                noteTitle = "Architecture",
                headingPath = listOf("Overview", "Components"),
                text = "Domain models are pure Kotlin.",
                index = 0,
            )

        assertEquals("note-123", chunk.noteId)
        assertEquals("Architecture", chunk.noteTitle)
        assertEquals(listOf("Overview", "Components"), chunk.headingPath)
        assertEquals("Domain models are pure Kotlin.", chunk.text)
        assertEquals(0, chunk.index)
    }

    @Test
    fun note_nullableColorSupport() {
        val now = Instant.now()
        val note =
            Note(
                id = "id",
                title = "title",
                type = NoteType.CHECKLIST,
                folderPath = "",
                pinned = false,
                color = null,
                tags = emptyList(),
                created = now,
                modified = now,
                checksum = "checksum",
            )
        assertNull(note.color)
    }
}
