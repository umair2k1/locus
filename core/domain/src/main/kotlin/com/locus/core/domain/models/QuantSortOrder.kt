package com.locus.core.domain.models

enum class QuantSortOrder(
    val displayName: String,
) {
    SIZE_ASC("Smallest"),
    SIZE_DESC("Largest"),
    NAME("Name (A-Z)"),
}
