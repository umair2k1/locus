package com.locus.core.domain.templates

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RenderTemplateUseCase
    @Inject
    constructor() {
        fun render(
            templateBody: String,
            selection: String? = null,
            note: String? = null,
        ): String {
            val safeSelection = selection.orEmpty()
            val safeNote = note.orEmpty()
            return templateBody
                .replace("{{selection}}", safeSelection)
                .replace("{{note}}", safeNote)
        }
    }
