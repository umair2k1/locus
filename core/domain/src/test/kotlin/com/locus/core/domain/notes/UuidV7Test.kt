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

import com.locus.core.domain.time.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UuidV7Test {
    @Test
    fun generate_setsVersionNibbleTo7() {
        val clock = Clock { Instant.ofEpochMilli(1_700_000_000_000L) }
        val uuid = UuidV7.generate(clock)

        val groups = uuid.split("-")
        assertEquals(5, groups.size)
        assertEquals('7', groups[2][0])
        assertEquals('7', uuid[14])
    }

    @Test
    fun generate_setsVariantToRfc4122() {
        val clock = Clock { Instant.ofEpochMilli(1_700_000_000_000L) }
        val uuid = UuidV7.generate(clock)

        val groups = uuid.split("-")
        val variantNibble = groups[3][0]
        assertTrue(
            "Expected variant nibble in [8, 9, a, b], but was '$variantNibble'",
            variantNibble in listOf('8', '9', 'a', 'b'),
        )
    }

    @Test
    fun generate_matchesRfc9562Format() {
        val clock = Clock { Instant.ofEpochMilli(1_700_000_000_000L) }
        val uuid = UuidV7.generate(clock)

        val regex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        assertTrue("UUID '$uuid' does not match RFC-9562 UUIDv7 format", uuid.matches(regex))
    }

    @Test
    fun generate_twoCallsOneMsApart_produceLexicographicallyIncreasingIds() {
        var currentInstant = Instant.ofEpochMilli(1_700_000_000_000L)
        val clock = Clock { currentInstant }

        val firstId = UuidV7.generate(clock)
        currentInstant = currentInstant.plusMillis(1)
        val secondId = UuidV7.generate(clock)

        assertTrue(
            "Expected '$firstId' < '$secondId'",
            firstId < secondId,
        )
    }

    @Test
    fun generate_multipleSequentialMilliseconds_strictlyMonotonic() {
        var currentInstant = Instant.ofEpochMilli(1_710_000_000_000L)
        val clock = Clock { currentInstant }

        val count = 100
        val uuids = ArrayList<String>(count)
        for (i in 0 until count) {
            uuids.add(UuidV7.generate(clock))
            currentInstant = currentInstant.plusMillis(1)
        }

        for (i in 0 until count - 1) {
            val prev = uuids[i]
            val next = uuids[i + 1]
            assertTrue("Expected '$prev' < '$next'", prev < next)
        }
    }

    @Test
    fun generate_embedsCorrectTimestamp() {
        val epochMs = 1_726_483_200_123L
        val clock = Clock { Instant.ofEpochMilli(epochMs) }
        val uuid = UuidV7.generate(clock)

        // 48-bit timestamp is first 8 hex chars + first 4 hex chars of second group
        val tsHex = uuid.substring(0, 8) + uuid.substring(9, 13)
        val extractedMs = tsHex.toLong(16)

        assertEquals(epochMs, extractedMs)
    }
}
