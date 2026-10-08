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

package com.locus.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.locus.core.domain.notes.NoteType
import java.time.Instant

@Entity(tableName = "note_index")
data class NoteIndexEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val type: NoteType,
    val folderPath: String,
    val pinned: Boolean,
    val color: String?,
    val tags: List<String>,
    val created: Instant,
    val modified: Instant,
    val checksum: String,
    val bodyPreview: String = "",
)
