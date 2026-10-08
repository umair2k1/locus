package com.locus.core.domain.dashboard

import java.time.Instant

data class ActionItem(
    val id: String,
    val noteId: String,
    val noteTitle: String,
    val task: String,
)

data class ActionItemCard(
    val id: String,
    val items: List<ActionItem>,
    val computedAt: Instant = Instant.now(),
)
