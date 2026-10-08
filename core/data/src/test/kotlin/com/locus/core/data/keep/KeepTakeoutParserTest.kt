package com.locus.core.data.keep

import com.locus.core.domain.notes.NoteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class KeepTakeoutParserTest {
    private fun readFixture(name: String): String {
        val stream =
            KeepTakeoutParserTest::class.java.classLoader?.getResourceAsStream("keep-fixtures/$name")
                ?: KeepTakeoutParserTest::class.java.getResourceAsStream("/keep-fixtures/$name")
                ?: Thread.currentThread().contextClassLoader?.getResourceAsStream("keep-fixtures/$name")
                ?: java.io.File("src/test/resources/keep-fixtures/$name").takeIf { it.exists() }?.inputStream()
                ?: java.io.File("core/data/src/test/resources/keep-fixtures/$name").takeIf { it.exists() }?.inputStream()
                ?: error("Fixture $name not found")
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun parseJson_textNote_mapsContentAndTimestamps() {
        val json = readFixture("text_note.json")
        val parsed = KeepTakeoutParser.parseJson(json)

        assertNotNull(parsed)
        assertEquals("Architecture Review", parsed!!.title)
        assertEquals("Remember to review the architecture documentation before Friday.", parsed.body)
        assertEquals(NoteType.NOTE, parsed.type)
        assertEquals(false, parsed.isPinned)
        assertEquals(null, parsed.color)
        assertTrue(parsed.tags.isEmpty())
        assertTrue(parsed.created.toEpochMilli() > 0)
        assertTrue(parsed.modified.toEpochMilli() > 0)
    }

    @Test
    fun parseJson_checklistNote_mapsChecklistItems() {
        val json = readFixture("checklist_note.json")
        val parsed = KeepTakeoutParser.parseJson(json)

        assertNotNull(parsed)
        assertEquals("Grocery List", parsed!!.title)
        assertEquals(NoteType.CHECKLIST, parsed.type)
        assertTrue(parsed.body.contains("- [x] Apples"))
        assertTrue(parsed.body.contains("- [ ] Milk"))
        assertTrue(parsed.body.contains("- [ ] Bread"))
    }

    @Test
    fun parseJson_pinnedColoredLabeledNote_mapsPinColorTags() {
        val json = readFixture("pinned_colored_labeled_note.json")
        val parsed = KeepTakeoutParser.parseJson(json)

        assertNotNull(parsed)
        assertEquals("Q3 Planning", parsed!!.title)
        assertEquals(true, parsed.isPinned)
        assertEquals("#FFF475", parsed.color)
        assertEquals(listOf("work", "planning"), parsed.tags)
    }

    @Test
    fun parseHtml_legacyNote_mapsHtmlFields() {
        val html = readFixture("legacy_note.html")
        val parsed = KeepTakeoutParser.parseHtml(html)

        assertNotNull(parsed)
        assertEquals("Meeting Minutes", parsed!!.title)
        assertTrue(parsed.body.contains("Discussed Q4 roadmap and agreed on milestones."))
        assertTrue(parsed.body.contains("Next meeting on Monday."))
        assertEquals(listOf("meeting"), parsed.tags)
    }

    @Test
    fun parseZip_fixtureZip_parsesAllNotes() {
        val f1 = readFixture("text_note.json")
        val f2 = readFixture("checklist_note.json")
        val f3 = readFixture("pinned_colored_labeled_note.json")
        val f4 = readFixture("legacy_note.html")

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("Takeout/Keep/text_note.json"))
            zos.write(f1.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Takeout/Keep/checklist_note.json"))
            zos.write(f2.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Takeout/Keep/pinned_colored_labeled_note.json"))
            zos.write(f3.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Takeout/Keep/legacy_note.html"))
            zos.write(f4.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val parsedNotes = KeepTakeoutParser.parseZip(ByteArrayInputStream(baos.toByteArray()))
        assertEquals(4, parsedNotes.size)
    }
}
