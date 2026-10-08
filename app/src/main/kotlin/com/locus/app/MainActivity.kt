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

package com.locus.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import com.locus.app.navigation.LocusDestinations
import com.locus.app.navigation.LocusNavGraph
import com.locus.app.theme.LocusTheme
import com.locus.core.domain.notes.NoteRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var noteRepository: NoteRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val navRoute = intent?.getStringExtra(EXTRA_NAV_ROUTE)
        val noteId = intent?.getStringExtra(EXTRA_NOTE_ID)
        val startDestination =
            when {
                navRoute != null -> navRoute
                noteId != null -> LocusDestinations.editorRoute(noteId)
                else -> LocusDestinations.GRID_ROUTE
            }
        setContent {
            LaunchedEffect(Unit) {
                runCatching {
                    noteRepository.rescan()
                }
            }
            LocusTheme {
                LocusNavGraph(startDestination = startDestination)
            }
        }
    }

    companion object {
        const val EXTRA_NAV_ROUTE = "nav_route"
        const val EXTRA_NOTE_ID = "note_id"
    }
}
