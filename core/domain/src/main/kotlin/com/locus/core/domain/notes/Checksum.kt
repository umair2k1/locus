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

import java.security.MessageDigest

object Checksum {
    private val hexDigits = "0123456789abcdef".toCharArray()
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_MASK = 0x0F
    private const val NIBBLE_SHIFT = 4

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        val result = CharArray(hash.size * 2)
        for (i in hash.indices) {
            val byteVal = hash[i].toInt() and BYTE_MASK
            result[i * 2] = hexDigits[byteVal ushr NIBBLE_SHIFT]
            result[i * 2 + 1] = hexDigits[byteVal and NIBBLE_MASK]
        }
        return String(result)
    }
}
