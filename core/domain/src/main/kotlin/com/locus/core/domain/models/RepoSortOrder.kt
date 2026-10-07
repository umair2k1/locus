package com.locus.core.domain.models

enum class RepoSortOrder(
    val displayName: String,
) {
    DOWNLOADS("Most Downloads"),
    LIKES("Most Likes"),
    NAME("Name (A-Z)"),
}
