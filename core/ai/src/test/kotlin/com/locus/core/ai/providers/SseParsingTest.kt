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

package com.locus.core.ai.providers

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParsingTest {
    @Test
    fun parseLines_parsesSingleDataEvent() {
        val lines =
            sequenceOf(
                "data: hello world",
                "",
            )
        val events = SseParsing.parseLines(lines).toList()

        assertEquals(1, events.size)
        assertEquals("hello world", events[0].data)
        assertNull(events[0].event)
        assertNull(events[0].id)
    }

    @Test
    fun parseLines_parsesMultiLineData() {
        val lines =
            sequenceOf(
                "data: line 1",
                "data: line 2",
                "",
            )
        val events = SseParsing.parseLines(lines).toList()

        assertEquals(1, events.size)
        assertEquals("line 1\nline 2", events[0].data)
    }

    @Test
    fun parseLines_parsesEventAndId() {
        val lines =
            sequenceOf(
                "event: custom_type",
                "id: msg_42",
                "data: content",
                "",
            )
        val events = SseParsing.parseLines(lines).toList()

        assertEquals(1, events.size)
        assertEquals("content", events[0].data)
        assertEquals("custom_type", events[0].event)
        assertEquals("msg_42", events[0].id)
    }

    @Test
    fun parseLines_ignoresComments() {
        val lines =
            sequenceOf(
                ": keep-alive",
                "data: valid",
                "",
            )
        val events = SseParsing.parseLines(lines).toList()

        assertEquals(1, events.size)
        assertEquals("valid", events[0].data)
    }

    @Test
    fun parseSource_readsFromBufferedSource() {
        val buffer = Buffer()
        buffer.writeUtf8("data: event1\n\ndata: event2\n\n")

        val events = SseParsing.parseSource(buffer).toList()

        assertEquals(2, events.size)
        assertEquals("event1", events[0].data)
        assertEquals("event2", events[1].data)
    }
}
