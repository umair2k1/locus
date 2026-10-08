package com.locus.core.domain.templates

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RenderTemplateUseCaseTest {
    private lateinit var useCase: RenderTemplateUseCase

    @Before
    fun setUp() {
        useCase = RenderTemplateUseCase()
    }

    @Test
    fun render_substitutesBothPlaceholdersWhenProvided() {
        val template = "Selected: '{{selection}}' in Note: '{{note}}'."
        val result =
            useCase.render(
                templateBody = template,
                selection = "highlighted paragraph",
                note = "Full note body content",
            )
        assertEquals("Selected: 'highlighted paragraph' in Note: 'Full note body content'.", result)
    }

    @Test
    fun render_replacesUnusedNotePlaceholderWithEmptyStringWithoutCrash() {
        val template = "Process this: {{selection}} (Context: {{note}})"
        val result =
            useCase.render(
                templateBody = template,
                selection = "only a selection",
                note = null,
            )
        assertEquals("Process this: only a selection (Context: )", result)
    }

    @Test
    fun render_replacesUnusedSelectionPlaceholderWithEmptyStringWithoutCrash() {
        val template = "Full note: {{note}}, selection: {{selection}}"
        val result =
            useCase.render(
                templateBody = template,
                selection = null,
                note = "My note",
            )
        assertEquals("Full note: My note, selection: ", result)
    }

    @Test
    fun render_bothNull_rendersEmptyStringsWithoutCrash() {
        val template = "{{selection}} - {{note}}"
        val result =
            useCase.render(
                templateBody = template,
                selection = null,
                note = null,
            )
        assertEquals(" - ", result)
    }
}
