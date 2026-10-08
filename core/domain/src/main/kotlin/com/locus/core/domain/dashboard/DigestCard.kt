package com.locus.core.domain.dashboard

import java.time.Instant

enum class DigestPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
}

data class DigestItem(
    val noteId: String,
    val noteTitle: String,
    val summary: String,
)

data class DigestCard(
    val id: String,
    val period: DigestPeriod,
    val items: List<DigestItem>,
    val overallSummary: String,
    val computedAt: Instant = Instant.now(),
)
