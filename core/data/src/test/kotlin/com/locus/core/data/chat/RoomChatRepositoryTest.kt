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

package com.locus.core.data.chat

import android.content.Context
import androidx.room.Room
import com.locus.core.data.db.LocusDatabase
import com.locus.core.data.time.AndroidDispatcherProvider
import com.locus.core.domain.chat.ChatMessage
import com.locus.core.domain.chat.ChatRole
import com.locus.core.domain.chat.CitedSource
import com.locus.core.domain.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RoomChatRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: LocusDatabase
    private lateinit var repository: RoomChatRepository
    private val clock =
        object : Clock {
            var currentTime: Instant = Instant.ofEpochMilli(1_700_000_000_000L)

            override fun now(): Instant = currentTime
        }
    private val dispatchers = AndroidDispatcherProvider()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database =
            Room
                .inMemoryDatabaseBuilder(
                    context,
                    LocusDatabase::class.java,
                ).allowMainThreadQueries()
                .build()

        repository =
            RoomChatRepository(
                chatDao = database.chatDao(),
                clock = clock,
                dispatchers = dispatchers,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun createTwoSessionsAndAppendMessages_keepsHistoriesIndependentAndFlowObservable() =
        runTest {
            // Create two independent sessions
            clock.currentTime = Instant.ofEpochMilli(1_700_000_000_000L)
            val session1 = repository.createSession("Session 1")
            clock.currentTime = Instant.ofEpochMilli(1_700_000_001_000L)
            val session2 = repository.createSession("Session 2")

            val sessions = repository.observeSessions().first()
            assertEquals(2, sessions.size)
            // Ordered by modifiedAt DESC -> session2 first, then session1
            assertEquals("Session 2", sessions[0].name)
            assertEquals("Session 1", sessions[1].name)

            // Append messages to session 1
            val s1m1 =
                ChatMessage(
                    id = "s1m1",
                    sessionId = session1.id,
                    role = ChatRole.USER,
                    content = "Hello from session 1",
                    citations = emptyList(),
                    timestamp = Instant.ofEpochMilli(1_700_000_002_000L),
                )
            val s1m2 =
                ChatMessage(
                    id = "s1m2",
                    sessionId = session1.id,
                    role = ChatRole.ASSISTANT,
                    content = "Response in session 1 [1]",
                    citations =
                        listOf(
                            CitedSource(
                                noteId = "note-123",
                                noteTitle = "Architecture Notes",
                                headingPath = listOf("Overview", "Room"),
                            ),
                        ),
                    timestamp = Instant.ofEpochMilli(1_700_000_003_000L),
                )
            repository.appendMessage(session1.id, s1m1)
            repository.appendMessage(session1.id, s1m2)

            // Append messages to session 2
            val s2m1 =
                ChatMessage(
                    id = "s2m1",
                    sessionId = session2.id,
                    role = ChatRole.USER,
                    content = "Hello from session 2",
                    citations = emptyList(),
                    timestamp = Instant.ofEpochMilli(1_700_000_004_000L),
                )
            repository.appendMessage(session2.id, s2m1)

            // Observe messages of session 1
            val s1Messages = repository.observeMessages(session1.id).first()
            assertEquals(2, s1Messages.size)
            assertEquals("s1m1", s1Messages[0].id)
            assertEquals(ChatRole.USER, s1Messages[0].role)
            assertEquals("Hello from session 1", s1Messages[0].content)
            assertTrue(s1Messages[0].citations.isEmpty())

            assertEquals("s1m2", s1Messages[1].id)
            assertEquals(ChatRole.ASSISTANT, s1Messages[1].role)
            assertEquals("Response in session 1 [1]", s1Messages[1].content)
            assertEquals(1, s1Messages[1].citations.size)
            val cited = s1Messages[1].citations[0]
            assertEquals("note-123", cited.noteId)
            assertEquals("Architecture Notes", cited.noteTitle)
            assertEquals(listOf("Overview", "Room"), cited.headingPath)

            // Observe messages of session 2
            val s2Messages = repository.observeMessages(session2.id).first()
            assertEquals(1, s2Messages.size)
            assertEquals("s2m1", s2Messages[0].id)
            assertEquals(ChatRole.USER, s2Messages[0].role)
            assertEquals("Hello from session 2", s2Messages[0].content)
            assertTrue(s2Messages[0].citations.isEmpty())
        }

    @Test
    fun dataSurvivesDatabaseCloseAndReopen_usingRealFileBackedInstance() =
        runTest {
            val dbName = "file_backed_test_chat.db"
            context.deleteDatabase(dbName)

            // 1. Create file-backed DB and add data
            var fileDb =
                Room
                    .databaseBuilder(
                        context,
                        LocusDatabase::class.java,
                        dbName,
                    ).allowMainThreadQueries()
                    .build()

            var fileRepo =
                RoomChatRepository(
                    chatDao = fileDb.chatDao(),
                    clock = clock,
                    dispatchers = dispatchers,
                )

            val session = fileRepo.createSession("Persistent Session")
            val message =
                ChatMessage(
                    id = "msg-persist-1",
                    sessionId = session.id,
                    role = ChatRole.USER,
                    content = "This must survive close/reopen",
                    citations =
                        listOf(
                            CitedSource(
                                noteId = "n-1",
                                noteTitle = "Persistent Note",
                                headingPath = listOf("Heading 1"),
                            ),
                        ),
                    timestamp = Instant.ofEpochMilli(1_700_000_100_000L),
                )
            fileRepo.appendMessage(session.id, message)

            // Verify it exists before close
            val preCloseSessions = fileRepo.observeSessions().first()
            assertEquals(1, preCloseSessions.size)
            assertEquals("Persistent Session", preCloseSessions[0].name)

            val preCloseMessages = fileRepo.observeMessages(session.id).first()
            assertEquals(1, preCloseMessages.size)
            assertEquals("This must survive close/reopen", preCloseMessages[0].content)

            // 2. Close the database
            fileDb.close()

            // 3. Reopen the database from the same file
            fileDb =
                Room
                    .databaseBuilder(
                        context,
                        LocusDatabase::class.java,
                        dbName,
                    ).allowMainThreadQueries()
                    .build()

            fileRepo =
                RoomChatRepository(
                    chatDao = fileDb.chatDao(),
                    clock = clock,
                    dispatchers = dispatchers,
                )

            // 4. Assert data survived
            val postReopenSessions = fileRepo.observeSessions().first()
            assertEquals(1, postReopenSessions.size)
            assertEquals(session.id, postReopenSessions[0].id)
            assertEquals("Persistent Session", postReopenSessions[0].name)

            val postReopenMessages = fileRepo.observeMessages(session.id).first()
            assertEquals(1, postReopenMessages.size)
            assertEquals("msg-persist-1", postReopenMessages[0].id)
            assertEquals(ChatRole.USER, postReopenMessages[0].role)
            assertEquals("This must survive close/reopen", postReopenMessages[0].content)
            assertEquals(1, postReopenMessages[0].citations.size)
            assertEquals("n-1", postReopenMessages[0].citations[0].noteId)
            assertEquals("Persistent Note", postReopenMessages[0].citations[0].noteTitle)
            assertEquals(listOf("Heading 1"), postReopenMessages[0].citations[0].headingPath)

            fileDb.close()
            context.deleteDatabase(dbName)
        }

    @Test
    fun appendMessage_autoGeneratesIdIfBlank() =
        runTest {
            val session = repository.createSession("Auto ID Session")
            val message =
                ChatMessage(
                    id = "",
                    sessionId = session.id,
                    role = ChatRole.USER,
                    content = "No ID provided",
                    citations = emptyList(),
                    timestamp = clock.now(),
                )

            repository.appendMessage(session.id, message)

            val messages = repository.observeMessages(session.id).first()
            assertEquals(1, messages.size)
            assertTrue(messages[0].id.isNotBlank())
            assertEquals("No ID provided", messages[0].content)
        }

    @Test
    fun deleteSession_cascadesToMessages() =
        runTest {
            val session = repository.createSession("To Be Deleted")
            val message =
                ChatMessage(
                    id = "cascaded-msg",
                    sessionId = session.id,
                    role = ChatRole.USER,
                    content = "Will be deleted",
                    citations = emptyList(),
                    timestamp = clock.now(),
                )
            repository.appendMessage(session.id, message)

            assertEquals(1, repository.observeMessages(session.id).first().size)

            database.chatDao().deleteSession(session.id)

            assertTrue(repository.observeSessions().first().none { it.id == session.id })
            assertTrue(repository.observeMessages(session.id).first().isEmpty())
        }
}
