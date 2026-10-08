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

package com.locus.app.navigation

object LocusDestinations {
    const val GRID_ROUTE = "grid"
    const val TREE_ROUTE = "tree"
    const val DASHBOARD_ROUTE = "dashboard"
    const val SETTINGS_ROUTE = "settings"
    const val DASHBOARD_SETTINGS_ROUTE = "dashboard_settings"
    const val SEARCH_ROUTE = "search"
    const val SEARCH_PATTERN = "search?noteIds={noteIds}"
    const val TRASH_ROUTE = "trash"
    const val EDITOR_ROUTE = "editor/{noteId}"
    const val CHAT_ROUTE = "chat"
    const val MODEL_MANAGER_ROUTE = "model_manager"
    const val USAGE_SUMMARY_ROUTE = "usage_summary"
    const val AUDIT_JOURNAL_ROUTE = "audit_journal"
    const val PROMPT_TEMPLATES_ROUTE = "prompt_templates"
    const val KEEP_IMPORT_ROUTE = "keep_import"
    const val SUBSCRIPTION_LOGIN_ROUTE = "subscription_login"
    const val NOTE_ID_ARG = "noteId"

    fun editorRoute(noteId: String): String = "editor/$noteId"

    fun searchRoute(noteIds: Collection<String> = emptyList()): String =
        if (noteIds.isEmpty()) "search" else "search?noteIds=${noteIds.joinToString(",")}"
}
