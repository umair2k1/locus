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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumTest {
    @Test
    fun sha256_matchesKnownVectors() {
        // Empty string SHA-256
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Checksum.sha256(""),
        )
        // Standard "hello" SHA-256
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Checksum.sha256("hello"),
        )
    }

    @Test
    fun sha256_isStable() {
        val input = "# Note Title\n\nBody content with markdown."
        val hash1 = Checksum.sha256(input)
        val hash2 = Checksum.sha256(input)
        assertEquals(hash1, hash2)
    }

    @Test
    fun sha256_changesOnAnyByteDifference() {
        assertNotEquals(Checksum.sha256("a"), Checksum.sha256("b"))
        assertNotEquals(
            Checksum.sha256("hello world"),
            Checksum.sha256("hello World"),
        )
        assertNotEquals(
            Checksum.sha256("note content"),
            Checksum.sha256("note content "),
        )
    }

    @Test
    fun sha256_outputIs64HexChars() {
        val hash = Checksum.sha256("sample note text")
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun sha256_handlesMultiByteUtf8() {
        val hash = Checksum.sha256("Note with emoji 📝 and accents café")
        assertEquals(64, hash.length)
        assertTrue(hash.matches(Regex("^[0-9a-f]{64}$")))
        // Stable across calls
        assertEquals(hash, Checksum.sha256("Note with emoji 📝 and accents café"))
    }
}
