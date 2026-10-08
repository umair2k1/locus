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

package com.locus.core.data.db

import android.content.Context
import androidx.room.Room
import com.locus.core.domain.notes.NoteType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class NoteDaoTest {
    private lateinit var db: LocusDatabase
    private lateinit var noteDao: NoteDao

    @Before
    fun createDb() {
        val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
        db =
            Room
                .inMemoryDatabaseBuilder(context, LocusDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        noteDao = db.noteDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun upsertThenObserveAllEmitsIt() =
        runTest {
            val now = Instant.ofEpochMilli(System.currentTimeMillis())
            val note =
                NoteIndexEntity(
                    id = "018f1234-5678-7000-8000-000000000001",
                    title = "Meeting Notes",
                    type = NoteType.NOTE,
                    folderPath = "/",
                    pinned = false,
                    color = null,
                    tags = listOf("work", "meeting"),
                    created = now,
                    modified = now,
                    checksum = "abc123hash",
                    bodyPreview = "Discussed Q3 roadmap and project timelines.",
                )

            noteDao.upsert(note)

            val notes = noteDao.observeAll().first()
            assertEquals(1, notes.size)
            assertEquals(note, notes[0])
        }

    @Test
    fun ftsSearchOnTitleSubstringMatches() =
        runTest {
            val now = Instant.ofEpochMilli(System.currentTimeMillis())
            val note1 =
                NoteIndexEntity(
                    id = "018f1234-5678-7000-8000-000000000001",
                    title = "Grocery Shopping List",
                    type = NoteType.CHECKLIST,
                    folderPath = "/lists",
                    pinned = true,
                    color = "#FFE082",
                    tags = listOf("shopping"),
                    created = now,
                    modified = now,
                    checksum = "check1",
                    bodyPreview = "Milk, Eggs, Bread",
                )
            val note2 =
                NoteIndexEntity(
                    id = "018f1234-5678-7000-8000-000000000002",
                    title = "Book Recommendations",
                    type = NoteType.NOTE,
                    folderPath = "/reading",
                    pinned = false,
                    color = null,
                    tags = listOf("books"),
                    created = now,
                    modified = now,
                    checksum = "check2",
                    bodyPreview = "Sci-fi and fantasy books to read",
                )

            noteDao.upsert(note1)
            noteDao.upsert(note2)

            val results = noteDao.ftsSearch("Grocery*")
            assertEquals(1, results.size)
            assertEquals("018f1234-5678-7000-8000-000000000001", results[0].id)
            assertEquals("Grocery Shopping List", results[0].title)

            val results2 = noteDao.ftsSearch("Shopping*")
            assertEquals(1, results2.size)
            assertEquals(note1.id, results2[0].id)
        }

    @Test
    fun deleteByIdRemovesNote() =
        runTest {
            val now = Instant.ofEpochMilli(System.currentTimeMillis())
            val note =
                NoteIndexEntity(
                    id = "018f1234-5678-7000-8000-000000000003",
                    title = "To be deleted",
                    type = NoteType.NOTE,
                    folderPath = "/",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = now,
                    modified = now,
                    checksum = "del",
                    bodyPreview = "Goodbye",
                )

            noteDao.upsert(note)
            assertEquals(1, noteDao.observeAll().first().size)

            noteDao.deleteById(note.id)
            assertTrue(noteDao.observeAll().first().isEmpty())
        }

    @Test
    fun observeByFolderFiltersCorrectly() =
        runTest {
            val now = Instant.ofEpochMilli(System.currentTimeMillis())
            val noteA =
                NoteIndexEntity(
                    id = "1",
                    title = "Folder A Note",
                    type = NoteType.NOTE,
                    folderPath = "/folderA",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = now,
                    modified = now,
                    checksum = "1",
                    bodyPreview = "",
                )
            val noteB =
                NoteIndexEntity(
                    id = "2",
                    title = "Folder B Note",
                    type = NoteType.NOTE,
                    folderPath = "/folderB",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = now,
                    modified = now,
                    checksum = "2",
                    bodyPreview = "",
                )

            noteDao.upsert(noteA)
            noteDao.upsert(noteB)

            val folderANotes = noteDao.observeByFolder("/folderA").first()
            assertEquals(1, folderANotes.size)
            assertEquals("1", folderANotes[0].id)

            val folderBNotes = noteDao.observeByFolder("/folderB").first()
            assertEquals(1, folderBNotes.size)
            assertEquals("2", folderBNotes[0].id)
        }
}
