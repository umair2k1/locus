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
    const val NOTE_ID_ARG = "noteId"

    fun editorRoute(noteId: String): String = "editor/$noteId"

    fun searchRoute(noteIds: Collection<String> = emptyList()): String =
        if (noteIds.isEmpty()) "search" else "search?noteIds=${noteIds.joinToString(",")}"
}
