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

import android.content.Context
import androidx.room.Room
import com.locus.core.data.db.LocusDatabase
import com.locus.core.data.db.NoteDao
import com.locus.core.data.db.NoteIndexEntity
import com.locus.core.domain.notes.NoteType
import com.locus.core.domain.search.SearchScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RoomKeywordSearchTest {
    private lateinit var db: LocusDatabase
    private lateinit var noteDao: NoteDao
    private lateinit var search: RoomKeywordSearch

    @Before
    fun createDb() {
        val context: Context = RuntimeEnvironment.getApplication()
        db =
            Room
                .inMemoryDatabaseBuilder(context, LocusDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        noteDao = db.noteDao()
        search = RoomKeywordSearch(noteDao)
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun searchAppleAgainstThreeNoteFixtureReturnsExactlyThatNote() =
        runTest {
            val now = Instant.ofEpochMilli(1_700_000_000_000L)
            val note1 =
                NoteIndexEntity(
                    id = "018f1234-5678-7000-8000-000000000001",
                    title = "Apple Tart Recipe",
                    type = NoteType.NOTE,
                    folderPath = "recipes",
                    pinned = false,
                    color = null,
                    tags = listOf("food"),
                    created = now,
                    modified = now,
                    checksum = "check1",
                    bodyPreview = "Bake with sliced apples, cinnamon, and puff pastry.",
                )
            val note2 =
                NoteIndexEntity(
                    id = "018f1234-5678-7000-8000-000000000002",
                    title = "Banana Bread",
                    type = NoteType.NOTE,
                    folderPath = "recipes",
                    pinned = false,
                    color = null,
                    tags = listOf("baking"),
                    created = now,
                    modified = now,
                    checksum = "check2",
                    bodyPreview = "Mash ripe bananas and mix with flour and walnuts.",
                )
            val note3 =
                NoteIndexEntity(
                    id = "018f1234-5678-7000-8000-000000000003",
                    title = "Carrot Soup",
                    type = NoteType.NOTE,
                    folderPath = "recipes",
                    pinned = false,
                    color = null,
                    tags = listOf("soup"),
                    created = now,
                    modified = now,
                    checksum = "check3",
                    bodyPreview = "Simmer carrots with ginger and vegetable broth.",
                )

            noteDao.upsert(note1)
            noteDao.upsert(note2)
            noteDao.upsert(note3)

            val results = search.search("apple", SearchScope())

            assertEquals(1, results.size)
            assertEquals(note1.id, results[0].noteId)
            assertEquals("Apple Tart Recipe", results[0].title)
            assertEquals(note1.bodyPreview, results[0].snippet)
            assertTrue(results[0].score > 0.0)
        }

    @Test
    fun searchWithFolderScopeFiltersCorrectly() =
        runTest {
            val now = Instant.ofEpochMilli(1_700_000_000_000L)
            val note1 =
                NoteIndexEntity(
                    id = "018f1234-5678-7000-8000-000000000001",
                    title = "Apple Pie",
                    type = NoteType.NOTE,
                    folderPath = "desserts/pies",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = now,
                    modified = now,
                    checksum = "c1",
                    bodyPreview = "Classic apple pie with flaky crust.",
                )
            val note2 =
                NoteIndexEntity(
                    id = "018f1234-5678-7000-8000-000000000002",
                    title = "Apple Tree Care",
                    type = NoteType.NOTE,
                    folderPath = "gardening",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = now,
                    modified = now,
                    checksum = "c2",
                    bodyPreview = "Pruning apple trees in early spring.",
                )

            noteDao.upsert(note1)
            noteDao.upsert(note2)

            // Scoping to "desserts" matches "desserts/pies"
            val dessertsResults = search.search("apple", SearchScope(folderPaths = setOf("desserts")))
            assertEquals(1, dessertsResults.size)
            assertEquals(note1.id, dessertsResults[0].noteId)

            // Scoping to "gardening" matches only note2
            val gardeningResults = search.search("apple", SearchScope(folderPaths = setOf("gardening")))
            assertEquals(1, gardeningResults.size)
            assertEquals(note2.id, gardeningResults[0].noteId)

            // Scoping to nonexistent folder returns nothing
            val otherResults = search.search("apple", SearchScope(folderPaths = setOf("finance")))
            assertTrue(otherResults.isEmpty())
        }

    @Test
    fun searchWithNoteIdsScopeFiltersCorrectly() =
        runTest {
            val now = Instant.ofEpochMilli(1_700_000_000_000L)
            val note1 =
                NoteIndexEntity(
                    id = "note-id-1",
                    title = "Apple Juice",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = now,
                    modified = now,
                    checksum = "c1",
                    bodyPreview = "Freshly pressed apple juice.",
                )
            val note2 =
                NoteIndexEntity(
                    id = "note-id-2",
                    title = "Apple Cider",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = now,
                    modified = now,
                    checksum = "c2",
                    bodyPreview = "Spiced hot apple cider for autumn.",
                )

            noteDao.upsert(note1)
            noteDao.upsert(note2)

            val results = search.search("apple", SearchScope(noteIds = setOf("note-id-2")))
            assertEquals(1, results.size)
            assertEquals("note-id-2", results[0].noteId)
        }

    @Test
    fun searchWithTimeRangeScopeFiltersCorrectly() =
        runTest {
            val t1 = Instant.ofEpochMilli(1_700_000_000_000L)
            val t2 = Instant.ofEpochMilli(1_700_050_000_000L)
            val t3 = Instant.ofEpochMilli(1_700_100_000_000L)

            val note1 =
                NoteIndexEntity(
                    id = "note-1",
                    title = "Early Apple Note",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = t1,
                    modified = t1,
                    checksum = "c1",
                    bodyPreview = "Early apple thoughts.",
                )
            val note2 =
                NoteIndexEntity(
                    id = "note-2",
                    title = "Mid Apple Note",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = t2,
                    modified = t2,
                    checksum = "c2",
                    bodyPreview = "Mid apple thoughts.",
                )

            noteDao.upsert(note1)
            noteDao.upsert(note2)

            // after filter
            val afterResults = search.search("apple", SearchScope(after = t2))
            assertEquals(1, afterResults.size)
            assertEquals("note-2", afterResults[0].noteId)

            // before filter
            val beforeResults = search.search("apple", SearchScope(before = t1))
            assertEquals(1, beforeResults.size)
            assertEquals("note-1", beforeResults[0].noteId)

            // time window
            val windowResults = search.search("apple", SearchScope(after = t1, before = t3))
            assertEquals(2, windowResults.size)
        }

    @Test
    fun blankOrWhitespaceQueryReturnsEmptyList() =
        runTest {
            assertTrue(search.search("", SearchScope()).isEmpty())
            assertTrue(search.search("   ", SearchScope()).isEmpty())
        }

    @Test
    fun searchWithSpecialCharactersDoesNotCrash() =
        runTest {
            val now = Instant.ofEpochMilli(1_700_000_000_000L)
            val note =
                NoteIndexEntity(
                    id = "note-special",
                    title = "Special characters in apple note",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = now,
                    modified = now,
                    checksum = "cs",
                    bodyPreview = "Testing quotes and brackets.",
                )
            noteDao.upsert(note)

            // Malformed FTS query with unbalanced quotes - should not crash
            val results = search.search("apple \" brackets", SearchScope())
            assertEquals(1, results.size)
            assertEquals("note-special", results[0].noteId)

            // Syntax error query with unmatched operators - returns safely without crashing
            val emptyResults = search.search("AND OR NOT () \"", SearchScope())
            assertTrue(emptyResults.isEmpty())
        }

    @Test
    fun searchNaturalLanguageQuestionWithStopWordsFindsMatchingNote() =
        runTest {
            val now = Instant.ofEpochMilli(1_700_000_000_000L)
            val bikeNote =
                NoteIndexEntity(
                    id = "bike-note-id",
                    title = "bike refuel date",
                    type = NoteType.NOTE,
                    folderPath = "",
                    pinned = false,
                    color = null,
                    tags = emptyList(),
                    created = now,
                    modified = now,
                    checksum = "cs-bike",
                    bodyPreview = "bike refueled - 8 sept 2026 - 1241.43 rs",
                )
            noteDao.upsert(bikeNote)

            val results = search.search("when was my bike refueled?", SearchScope())
            assertEquals(1, results.size)
            assertEquals("bike-note-id", results[0].noteId)
            assertEquals("bike refuel date", results[0].title)
        }
}
