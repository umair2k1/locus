package com.locus.core.domain.templates

data class PromptTemplate(
    val id: String,
    val title: String,
    val templateBody: String,
    val createdAt: Long = System.currentTimeMillis(),
)
