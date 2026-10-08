package com.locus.core.domain.dashboard

import java.time.Instant

data class ClusterCard(
    val id: String,
    val label: String,
    val noteIds: List<String>,
    val noteTitles: List<String> = emptyList(),
    val computedAt: Instant = Instant.now(),
)
