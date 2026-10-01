package dev.rortega.orchardnotes.notes.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteFormatTest {
    private fun run(length: Int, style: Int? = null, configure: AttributeRun.() -> Unit = {}) =
        AttributeRun.plain(length).apply {
            if (style != null) paragraphStyle = ParagraphStyle().apply { this.style = style }
            configure()
        }

    private fun ok(result: FormatResult) = (result as FormatResult.Ok).paragraphs

    private fun paragraph(text: String, kind: ParagraphKind = ParagraphKind.Body, startNumber: Int = 0, done: Boolean = false) =
        FormatParagraph(kind = kind, text = text, spans = listOf(InlineSpan(text.length, InlineStyle.Plain)), startNumber = startNumber, done = done)

    @Test
    fun mapsEveryWireStyleToItsKind() {
        val text = "t\nh\ns\nb\nm\nu\nd\nn\nc"
        val paragraphs = ok(
            NoteFormat.decode(
                text,
                listOf(0, 1, 2, 3, 4, 100, 101, 102).map { run(2, it) } +
                    run(1, 103) { paragraphStyle = paragraphStyle!!.apply { todo = Todo(ByteArray(16), true) } },
            ),
        )
        assertEquals(ParagraphKind.entries.toList(), paragraphs.map { it.kind })
        assertTrue(paragraphs[8].done)
    }

    @Test
    fun absentStyleAndExplicitBodyAreBothBody() {
        assertEquals(ParagraphKind.Body, ok(NoteFormat.decode("x", listOf(run(1, 3))))[0].kind)
        assertEquals(ParagraphKind.Body, ok(NoteFormat.decode("x", listOf(run(1))))[0].kind)
        val indentOnly = run(1) { paragraphStyle = ParagraphStyle().apply { indent = 2 } }
        val decoded = ok(NoteFormat.decode("x", listOf(indentOnly)))[0]
        assertEquals(ParagraphKind.Body, decoded.kind)
        assertEquals(2, decoded.indent)
    }

    @Test
    fun refusesUnknownParagraphStyle() {
        val result = NoteFormat.decode("x", listOf(run(1, 7)))
        assertTrue((result as FormatResult.Unsupported).reason.contains("paragraph style (7)"))
    }

    @Test
    fun toleratesUnderCoverageButRefusesOvershoot() {
        val under = ok(NoteFormat.decode("covered and not", listOf(run(7) { fontHints = 1 })))
        assertEquals(
            listOf(InlineSpan(7, InlineStyle(bold = true)), InlineSpan(8, InlineStyle.Plain)),
            under[0].spans,
        )
        assertTrue(NoteFormat.decode("ab", listOf(run(5))) is FormatResult.Unsupported)
    }

    @Test
    fun paragraphStyleComesFromTheNewlineRunAndRunsMaySpanLines() {
        val paragraphs = ok(NoteFormat.decode("one\ntwo\nthree", listOf(run(8, 1), run(5, 2))))
        assertEquals(listOf(ParagraphKind.Heading, ParagraphKind.Heading, ParagraphKind.Subheading), paragraphs.map { it.kind })
        assertEquals(listOf(0, 4, 8), paragraphs.map { it.start })
    }

    @Test
    fun adjacentEqualInlineRunsMerge() {
        val paragraphs = ok(
            NoteFormat.decode(
                "abcdef",
                listOf(run(2) { fontHints = 3 }, run(2) { fontHints = 3; msg.setVarint(13, 9) }, run(2) { underline = 1 }),
            ),
        )
        assertEquals(
            listOf(InlineSpan(4, InlineStyle(bold = true, italic = true)), InlineSpan(2, InlineStyle(underline = true))),
            paragraphs[0].spans,
        )
    }

    @Test
    fun checklistStateMattersForEquality() {
        assertFalse(
            NoteFormat.formatsEqual(
                listOf(paragraph("todo", ParagraphKind.Checklist, done = false)),
                listOf(paragraph("todo", ParagraphKind.Checklist, done = true)),
            ),
        )
    }

    @Test
    fun numberedStartMattersOnlyAtAGroupsFirstItem() {
        val a = listOf(paragraph("one", ParagraphKind.NumberedList, 5), paragraph("two", ParagraphKind.NumberedList, 0))
        val b = listOf(paragraph("one", ParagraphKind.NumberedList, 5), paragraph("two", ParagraphKind.NumberedList, 9))
        val c = listOf(paragraph("one", ParagraphKind.NumberedList, 4), paragraph("two", ParagraphKind.NumberedList, 0))
        assertTrue(NoteFormat.formatsEqual(a, b))
        assertFalse(NoteFormat.formatsEqual(a, c))
    }

    @Test
    fun realFormattedFixtureDecodes() {
        val content = NoteContent.decode(dev.rortega.orchardnotes.notes.Fixtures.compressed(dev.rortega.orchardnotes.notes.Fixtures.FORMATTED_MULTI_EDIT))
        val paragraphs = ok(content.format())
        assertEquals(content.text.split('\n').size, paragraphs.size)
        assertTrue(paragraphs.any { p -> p.spans.any { it.style.bold } })
        assertTrue(content.attachments().isNotEmpty())
    }
}
