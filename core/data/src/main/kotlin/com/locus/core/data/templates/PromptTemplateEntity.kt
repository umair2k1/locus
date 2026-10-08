package com.locus.core.data.templates

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.locus.core.domain.templates.PromptTemplate

@Entity(tableName = "prompt_templates")
data class PromptTemplateEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val templateBody: String,
    val createdAt: Long,
) {
    fun toDomain(): PromptTemplate =
        PromptTemplate(
            id = id,
            title = title,
            templateBody = templateBody,
            createdAt = createdAt,
        )

    companion object {
        fun fromDomain(template: PromptTemplate): PromptTemplateEntity =
            PromptTemplateEntity(
                id = template.id,
                title = template.title,
                templateBody = template.templateBody,
                createdAt = template.createdAt,
            )
    }
}
