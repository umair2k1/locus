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

import okio.BufferedSource

data class SseEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
)

object SseParsing {
    private const val DATA_PREFIX = "data:"
    private const val EVENT_PREFIX = "event:"
    private const val ID_PREFIX = "id:"

    fun parseLines(lines: Sequence<String>): Sequence<SseEvent> =
        sequence {
            var currentEvent: String? = null
            var currentId: String? = null
            val dataBuilder = StringBuilder()

            for (rawLine in lines) {
                val line = rawLine.trimEnd('\r', '\n')
                if (line.isEmpty()) {
                    if (dataBuilder.isNotEmpty()) {
                        yield(
                            SseEvent(
                                data = dataBuilder.toString(),
                                event = currentEvent,
                                id = currentId,
                            ),
                        )
                        dataBuilder.clear()
                        currentEvent = null
                        currentId = null
                    }
                } else if (line.startsWith(":")) {
                    // Comment line, ignore per SSE spec
                } else if (line.startsWith(DATA_PREFIX)) {
                    val rawValue = line.substring(DATA_PREFIX.length)
                    val value = if (rawValue.startsWith(' ')) rawValue.substring(1) else rawValue
                    if (dataBuilder.isNotEmpty()) {
                        dataBuilder.append("\n")
                    }
                    dataBuilder.append(value)
                } else if (line.startsWith(EVENT_PREFIX)) {
                    val rawValue = line.substring(EVENT_PREFIX.length)
                    currentEvent = if (rawValue.startsWith(' ')) rawValue.substring(1) else rawValue
                } else if (line.startsWith(ID_PREFIX)) {
                    val rawValue = line.substring(ID_PREFIX.length)
                    currentId = if (rawValue.startsWith(' ')) rawValue.substring(1) else rawValue
                }
            }

            if (dataBuilder.isNotEmpty()) {
                yield(
                    SseEvent(
                        data = dataBuilder.toString(),
                        event = currentEvent,
                        id = currentId,
                    ),
                )
            }
        }

    fun parseSource(source: BufferedSource): Sequence<SseEvent> {
        val lineSequence =
            sequence {
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    yield(line)
                }
            }
        return parseLines(lineSequence)
    }
}
