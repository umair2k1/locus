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

fun ParsedNote.toDomain(folderPath: String): Note =
    Note(
        id = id,
        title = title,
        type = type,
        folderPath = folderPath,
        pinned = pinned,
        color = color,
        tags = tags,
        created = created,
        modified = modified,
        checksum = checksum ?: Checksum.sha256(body),
    )
