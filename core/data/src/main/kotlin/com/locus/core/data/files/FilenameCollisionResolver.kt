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

/**
 * Pure function resolving filename / title collisions within a folder according to N-2:
 * "Filename collisions within a folder ... are resolved by auto-suffixing (`Title (2).md`, `Title (3).md`, ...)"
 */
object FilenameCollisionResolver {
    private const val MD_EXTENSION = ".md"
    private const val MD_EXTENSION_LENGTH = 3

    fun resolve(
        desiredTitle: String,
        existingTitlesInFolder: Set<String>,
    ): String {
        if (desiredTitle !in existingTitlesInFolder) {
            return desiredTitle
        }

        val hasMdExtension = desiredTitle.endsWith(MD_EXTENSION, ignoreCase = true)
        val (base, ext) =
            if (hasMdExtension) {
                desiredTitle.dropLast(MD_EXTENSION_LENGTH) to desiredTitle.takeLast(MD_EXTENSION_LENGTH)
            } else {
                desiredTitle to ""
            }

        var counter = 2
        while (true) {
            val candidate = "$base ($counter)$ext"
            if (candidate !in existingTitlesInFolder) {
                return candidate
            }
            counter++
        }
    }
}
