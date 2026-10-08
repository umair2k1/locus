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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class FlushReceipt(
    val noteId: String,
    val checksum: String,
    val flushedAt: Long,
)

interface NoteFileWriter {
    suspend fun atomicWrite(
        noteId: String,
        path: String,
        content: String,
    ): Result<FlushReceipt>
}

interface IndexUpdateQueue {
    suspend fun enqueue(receipt: FlushReceipt)
}

enum class FlushTrigger { DEBOUNCED, EDITOR_CLOSE, ON_STOP, PRE_BACKUP, PRE_AGENT_WRITE }

/**
 * Owns the N-1 write-ordering guarantee: keystrokes -> dirty buffer -> debounced atomic flush (commit
 * point) -> index update enqueued ONLY after the flush Result is a success. "The DB may lag the file; it
 * must never lead it." This is the only class permitted to call [IndexUpdateQueue.enqueue].
 */
class NoteFlushCoordinator(
    private val fileWriter: NoteFileWriter,
    private val indexQueue: IndexUpdateQueue,
    private val dispatchers: DispatcherProvider,
    private val scope: CoroutineScope,
    private val debounce: Duration = 600.milliseconds,
) {
    private data class Session(
        var buffer: String,
        var path: String,
        var pendingJob: Job?,
    )

    private val sessions = mutableMapOf<String, Session>()
    private val lock = Mutex()

    /** Called on every keystroke/edit. Never touches disk directly; only (re)arms the debounce timer. */
    suspend fun onEdit(
        noteId: String,
        path: String,
        content: String,
    ) {
        lock.withLock {
            val session = sessions.getOrPut(noteId) { Session(content, path, null) }
            session.buffer = content
            session.path = path
            session.pendingJob?.cancel()
            session.pendingJob =
                scope.launch(dispatchers.io) {
                    delay(debounce)
                    flush(noteId, FlushTrigger.DEBOUNCED)
                }
        }
    }

    /** Forced (non-debounced) flush: editor close, onStop, pre-backup (N-11), pre-agent-write (C-4). */
    suspend fun forceFlush(
        noteId: String,
        trigger: FlushTrigger,
    ): Result<FlushReceipt>? {
        require(trigger != FlushTrigger.DEBOUNCED) { "forceFlush is for non-debounced triggers only" }
        val hadSession =
            lock.withLock {
                sessions[noteId]?.also {
                    it.pendingJob?.cancel()
                    it.pendingJob = null
                }
            }
        return hadSession?.let { flush(noteId, trigger) }
    }

    /** Flushes every dirty session; used by onStop / pre-backup where the caller doesn't know which notes
     *  are currently dirty. */
    suspend fun forceFlushAll(trigger: FlushTrigger) {
        val ids = lock.withLock { sessions.keys.toList() }
        ids.forEach { forceFlush(it, trigger) }
    }

    @Suppress("UnusedParameter")
    private suspend fun flush(
        noteId: String,
        trigger: FlushTrigger,
    ): Result<FlushReceipt> {
        val session =
            lock.withLock { sessions[noteId] }
                ?: return Result.failure(IllegalStateException("no dirty session for $noteId"))
        val result =
            withContext(dispatchers.io) {
                fileWriter.atomicWrite(noteId, session.path, session.buffer)
            }
        result.onSuccess { receipt ->
            indexQueue.enqueue(receipt)
            lock.withLock { sessions.remove(noteId) }
        }
        // On failure the session stays dirty (never lost); DB/FTS/embedding stay untouched.
        return result
    }
}
