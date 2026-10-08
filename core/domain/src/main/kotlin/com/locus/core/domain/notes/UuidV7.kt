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
import java.security.SecureRandom
import java.util.UUID

object UuidV7 {
    private val random = SecureRandom()

    private const val TIMESTAMP_SHIFT = 16
    private const val VERSION_SHIFT = 12
    private const val VERSION_7 = 0x7L
    private const val RAND_A_MASK = 0x0FFFL

    private const val VARIANT_SHIFT = 62
    private const val VARIANT_RFC4122 = 0x2L
    private const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL

    fun generate(clock: Clock): String {
        val timestamp = clock.now().toEpochMilli()
        val randA = random.nextLong() and RAND_A_MASK
        val randB = random.nextLong() and RAND_B_MASK

        val msb = (timestamp shl TIMESTAMP_SHIFT) or (VERSION_7 shl VERSION_SHIFT) or randA
        val lsb = (VARIANT_RFC4122 shl VARIANT_SHIFT) or randB

        return UUID(msb, lsb).toString()
    }
}
