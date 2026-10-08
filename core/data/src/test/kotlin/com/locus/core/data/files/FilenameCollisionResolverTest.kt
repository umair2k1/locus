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

package com.locus.core.data.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FilenameCollisionResolverTest {
    @Test
    fun noCollision_returnsDesiredTitle() {
        val result = FilenameCollisionResolver.resolve("Ideas", emptySet())
        assertEquals("Ideas", result)
    }

    @Test
    fun noCollision_withUnrelatedTitles_returnsDesiredTitle() {
        val existing = setOf("Projects", "Tasks", "Personal")
        val result = FilenameCollisionResolver.resolve("Ideas", existing)
        assertEquals("Ideas", result)
    }

    @Test
    fun singleCollision_producesSuffix2() {
        val existing = setOf("Ideas")
        val result = FilenameCollisionResolver.resolve("Ideas", existing)
        assertEquals("Ideas (2)", result)
    }

    @Test
    fun threeCollisionsInARow_produce2Then3Then4() {
        // First collision
        val firstExisting = setOf("Ideas")
        val secondTitle = FilenameCollisionResolver.resolve("Ideas", firstExisting)
        assertEquals("Ideas (2)", secondTitle)

        // Second collision
        val secondExisting = setOf("Ideas", secondTitle)
        val thirdTitle = FilenameCollisionResolver.resolve("Ideas", secondExisting)
        assertEquals("Ideas (3)", thirdTitle)

        // Third collision
        val thirdExisting = setOf("Ideas", secondTitle, thirdTitle)
        val fourthTitle = FilenameCollisionResolver.resolve("Ideas", thirdExisting)
        assertEquals("Ideas (4)", fourthTitle)
    }

    @Test
    fun gapInSequence_fillsLowestAvailableSuffix() {
        val existing = setOf("Ideas", "Ideas (3)")
        val result = FilenameCollisionResolver.resolve("Ideas", existing)
        assertEquals("Ideas (2)", result)
    }

    @Test
    fun withMdExtension_preservesExtension() {
        val existing = setOf("Ideas.md", "Ideas (2).md", "Ideas (3).md")
        val result = FilenameCollisionResolver.resolve("Ideas.md", existing)
        assertEquals("Ideas (4).md", result)
    }
}
