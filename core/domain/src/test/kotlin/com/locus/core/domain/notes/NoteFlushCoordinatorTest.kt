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

import com.locus.core.domain.time.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class NoteFlushCoordinatorTest {
    private class FakeNoteFileWriter : NoteFileWriter {
        var shouldFail: Boolean = false
        var failureException: Exception = IOException("Disk write failed")
        val writeCalls = mutableListOf<WriteCall>()

        data class WriteCall(
            val noteId: String,
            val path: String,
            val content: String,
        )

        override suspend fun atomicWrite(
            noteId: String,
            path: String,
            content: String,
        ): Result<FlushReceipt> {
            writeCalls.add(WriteCall(noteId, path, content))
            return if (shouldFail) {
                Result.failure(failureException)
            } else {
                Result.success(
                    FlushReceipt(
                        noteId = noteId,
                        checksum = Checksum.sha256(content),
                        flushedAt = 123456789L,
                    ),
                )
            }
        }
    }

    private class FakeIndexUpdateQueue : IndexUpdateQueue {
        val enqueuedReceipts = mutableListOf<FlushReceipt>()

        override suspend fun enqueue(receipt: FlushReceipt) {
            enqueuedReceipts.add(receipt)
        }
    }

    private class TestDispatcherProvider(
        private val dispatcher: CoroutineDispatcher,
    ) : DispatcherProvider {
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
        override val main: CoroutineDispatcher get() = dispatcher
        override val mainImmediate: CoroutineDispatcher get() = dispatcher
    }

    @Test
    fun onEdit_alone_neverCallsWriterBeforeDebounceElapses() =
        runTest {
            val fileWriter = FakeNoteFileWriter()
            val indexQueue = FakeIndexUpdateQueue()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue = indexQueue,
                    dispatchers = TestDispatcherProvider(testDispatcher),
                    scope = backgroundScope,
                    debounce = 600.milliseconds,
                )

            coordinator.onEdit("note-1", "/notes/1.md", "Hello")
            runCurrent()
            assertEquals(0, fileWriter.writeCalls.size)

            advanceTimeBy(599)
            runCurrent()
            assertEquals(0, fileWriter.writeCalls.size)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, fileWriter.writeCalls.size)
            assertEquals("Hello", fileWriter.writeCalls[0].content)
            assertEquals(1, indexQueue.enqueuedReceipts.size)
        }

    @Test
    fun secondOnEdit_withinDebounceWindow_resetsTimer() =
        runTest {
            val fileWriter = FakeNoteFileWriter()
            val indexQueue = FakeIndexUpdateQueue()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue = indexQueue,
                    dispatchers = TestDispatcherProvider(testDispatcher),
                    scope = backgroundScope,
                    debounce = 600.milliseconds,
                )

            coordinator.onEdit("note-1", "/notes/1.md", "v1")
            advanceTimeBy(400)
            runCurrent()
            assertEquals(0, fileWriter.writeCalls.size)

            coordinator.onEdit("note-1", "/notes/1.md", "v2")
            advanceTimeBy(400)
            runCurrent()
            assertEquals(0, fileWriter.writeCalls.size)

            advanceTimeBy(200)
            runCurrent()
            assertEquals(1, fileWriter.writeCalls.size)
            assertEquals("v2", fileWriter.writeCalls[0].content)
            assertEquals(1, indexQueue.enqueuedReceipts.size)
        }

    @Test
    fun enqueue_isCalledIfAndOnlyIf_atomicWriteReturnedSuccess() =
        runTest {
            val fileWriter = FakeNoteFileWriter()
            val indexQueue = FakeIndexUpdateQueue()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue = indexQueue,
                    dispatchers = TestDispatcherProvider(testDispatcher),
                    scope = backgroundScope,
                    debounce = 600.milliseconds,
                )

            // Case 1: atomicWrite fails
            fileWriter.shouldFail = true
            coordinator.onEdit("note-fail", "/notes/fail.md", "bad content")
            advanceTimeBy(600)
            runCurrent()
            assertEquals(1, fileWriter.writeCalls.size)
            assertTrue("Index queue MUST NOT receive receipt when write fails", indexQueue.enqueuedReceipts.isEmpty())

            // Case 2: atomicWrite succeeds
            fileWriter.shouldFail = false
            coordinator.onEdit("note-success", "/notes/success.md", "good content")
            advanceTimeBy(600)
            runCurrent()
            assertEquals(2, fileWriter.writeCalls.size)
            assertEquals(1, indexQueue.enqueuedReceipts.size)
            assertEquals("note-success", indexQueue.enqueuedReceipts[0].noteId)
            assertEquals(Checksum.sha256("good content"), indexQueue.enqueuedReceipts[0].checksum)
        }

    @Test
    fun forceFlush_bypassesDebounceImmediately() =
        runTest {
            val fileWriter = FakeNoteFileWriter()
            val indexQueue = FakeIndexUpdateQueue()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue = indexQueue,
                    dispatchers = TestDispatcherProvider(testDispatcher),
                    scope = backgroundScope,
                    debounce = 600.milliseconds,
                )

            coordinator.onEdit("note-1", "/notes/1.md", "urgent content")
            runCurrent()
            assertEquals(0, fileWriter.writeCalls.size)

            val result = coordinator.forceFlush("note-1", FlushTrigger.EDITOR_CLOSE)
            assertNotNull(result)
            assertTrue(result!!.isSuccess)
            assertEquals(1, fileWriter.writeCalls.size)
            assertEquals("urgent content", fileWriter.writeCalls[0].content)
            assertEquals(1, indexQueue.enqueuedReceipts.size)

            advanceTimeBy(1000)
            runCurrent()
            assertEquals(1, fileWriter.writeCalls.size)
        }

    @Test
    fun failingWriter_leavesSessionDirty_subsequentForceFlushRetriesAndSucceeds() =
        runTest {
            val fileWriter = FakeNoteFileWriter()
            val indexQueue = FakeIndexUpdateQueue()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue = indexQueue,
                    dispatchers = TestDispatcherProvider(testDispatcher),
                    scope = backgroundScope,
                    debounce = 600.milliseconds,
                )

            fileWriter.shouldFail = true
            coordinator.onEdit("note-1", "/notes/1.md", "retry content")
            advanceTimeBy(600)
            runCurrent()
            assertEquals(1, fileWriter.writeCalls.size)
            assertEquals(0, indexQueue.enqueuedReceipts.size)

            // Recover writer and forceFlush
            fileWriter.shouldFail = false
            val retryResult = coordinator.forceFlush("note-1", FlushTrigger.ON_STOP)
            assertNotNull(retryResult)
            assertTrue(retryResult!!.isSuccess)
            assertEquals(2, fileWriter.writeCalls.size)
            assertEquals("retry content", fileWriter.writeCalls[1].content)
            assertEquals(1, indexQueue.enqueuedReceipts.size)

            // Session cleaned up after success
            val cleanResult = coordinator.forceFlush("note-1", FlushTrigger.ON_STOP)
            assertNull(cleanResult)
        }

    @Test
    fun forceFlushAll_flushesAllDirtySessions() =
        runTest {
            val fileWriter = FakeNoteFileWriter()
            val indexQueue = FakeIndexUpdateQueue()
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = fileWriter,
                    indexQueue = indexQueue,
                    dispatchers = TestDispatcherProvider(testDispatcher),
                    scope = backgroundScope,
                    debounce = 600.milliseconds,
                )

            coordinator.onEdit("note-1", "/notes/1.md", "c1")
            coordinator.onEdit("note-2", "/notes/2.md", "c2")
            runCurrent()
            assertEquals(0, fileWriter.writeCalls.size)

            coordinator.forceFlushAll(FlushTrigger.PRE_BACKUP)
            assertEquals(2, fileWriter.writeCalls.size)
            assertEquals(2, indexQueue.enqueuedReceipts.size)
        }

    @Test(expected = IllegalArgumentException::class)
    fun forceFlush_rejectsDebouncedTrigger() =
        runTest {
            val coordinator =
                NoteFlushCoordinator(
                    fileWriter = FakeNoteFileWriter(),
                    indexQueue = FakeIndexUpdateQueue(),
                    dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                    scope = backgroundScope,
                )
            coordinator.forceFlush("note-1", FlushTrigger.DEBOUNCED)
        }
}
