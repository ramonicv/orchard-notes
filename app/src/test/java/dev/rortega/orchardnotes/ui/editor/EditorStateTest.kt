package dev.rortega.orchardnotes.ui.editor

import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.InlineSpan
import dev.rortega.orchardnotes.notes.doc.InlineStyle
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorStateTest {
    private fun p(text: String, kind: ParagraphKind = ParagraphKind.Body, done: Boolean = false, spans: List<InlineSpan>? = null) =
        FormatParagraph(kind = kind, text = text, done = done, spans = spans ?: if (text.isEmpty()) emptyList() else listOf(InlineSpan(text.length, InlineStyle.Plain)))

    private fun state(vararg paragraphs: FormatParagraph) = EditorState.fromParagraphs(ParagraphMerge.withOffsets(paragraphs.toList()))

    /** Types [insert] at [at] the way the text field reports it. */
    private fun EditorState.type(at: Int, insert: String): Pair<EditorState, String?> =
        applyTextChange(text.substring(0, at) + insert + text.substring(at), at + insert.length)

    /** Presses backspace with the cursor at [at]. */
    private fun EditorState.backspace(at: Int): Pair<EditorState, String?> =
        applyTextChange(text.removeRange(at - 1, at), at - 1)

    private fun EditorState.kinds() = toParagraphs().map { it.text to it.kind }

    @Test
    fun paragraphsRoundTripThroughTheEditor() {
        val paragraphs = ParagraphMerge.withOffsets(
            listOf(
                p("Groceries", ParagraphKind.Title),
                p("eggs", ParagraphKind.Checklist, done = true),
                p("bold and plain", spans = listOf(InlineSpan(4, InlineStyle(bold = true)), InlineSpan(10, InlineStyle.Plain))),
                p(""),
            ),
        )
        assertTrue(NoteFormat.formatsEqual(paragraphs, EditorState.fromParagraphs(paragraphs).toParagraphs()))
    }

    @Test
    fun enterAfterATitleStartsABodyLine() {
        val (next, adjusted) = state(p("Title", ParagraphKind.Title)).type(5, "\nHello")
        assertNull(adjusted)
        assertEquals(listOf("Title" to ParagraphKind.Title, "Hello" to ParagraphKind.Body), next.kinds())
    }

    @Test
    fun enterInAChecklistAddsAnUncheckedItem() {
        val (next, _) = state(p("eggs", ParagraphKind.Checklist, done = true)).type(4, "\nmilk")
        assertEquals(listOf("eggs" to ParagraphKind.Checklist, "milk" to ParagraphKind.Checklist), next.kinds())
        assertEquals(listOf(true, false), next.toParagraphs().map { it.done })
    }

    @Test
    fun enterOnAnEmptyListItemLeavesTheList() {
        val start = state(p("eggs", ParagraphKind.Checklist), p("", ParagraphKind.Checklist))
        val (next, adjusted) = start.type(5, "\n")
        assertEquals(start.text, adjusted)
        assertEquals(listOf("eggs" to ParagraphKind.Checklist, "" to ParagraphKind.Body), next.kinds())
    }

    @Test
    fun returnAfterTheLastItemOfAListContinuesItWhenALineFollows() {
        // A list saved with Return after its last item comes back followed by a plain empty line.
        val (next, _) = state(p("milk", ParagraphKind.Checklist), p("eggs", ParagraphKind.Checklist), p("")).type(9, "\n")
        assertEquals(listOf("milk" to ParagraphKind.Checklist, "eggs" to ParagraphKind.Checklist, "" to ParagraphKind.Checklist, "" to ParagraphKind.Body), next.kinds())
        val (beforeText, _) = state(p("milk", ParagraphKind.Checklist), p("eggs", ParagraphKind.Checklist), p("Notes")).type(9, "\n")
        assertEquals(listOf("milk" to ParagraphKind.Checklist, "eggs" to ParagraphKind.Checklist, "" to ParagraphKind.Checklist, "Notes" to ParagraphKind.Body), beforeText.kinds())
    }

    @Test
    fun returnOnAnEmptyLastItemLeavesTheListWhenALineFollows() {
        val start = state(p("milk", ParagraphKind.Checklist), p("", ParagraphKind.Checklist), p(""))
        val (next, adjusted) = start.type(5, "\n")
        assertEquals(start.text, adjusted)
        assertEquals(listOf("milk" to ParagraphKind.Checklist, "" to ParagraphKind.Body, "" to ParagraphKind.Body), next.kinds())
    }

    @Test
    fun backspaceOnAnEmptiedLastItemRemovesItAndNotTheLineBelow() {
        // "eggs" deleted from the last item, then backspace again to remove its checkbox.
        val (next, _) = state(p("milk", ParagraphKind.Checklist), p("", ParagraphKind.Checklist), p("")).backspace(5)
        assertEquals(listOf("milk" to ParagraphKind.Checklist, "" to ParagraphKind.Body), next.kinds())
        val (beforeText, _) = state(p("milk", ParagraphKind.Checklist), p("", ParagraphKind.Checklist), p("Notes")).backspace(5)
        assertEquals(listOf("milk" to ParagraphKind.Checklist, "Notes" to ParagraphKind.Body), beforeText.kinds())
    }

    @Test
    fun backspaceAcrossLinesKeepsTheUpperLinesStyle() {
        val start = state(p("Heading", ParagraphKind.Heading), p("body"))
        val (next, _) = start.applyTextChange("Headingbody", 7)
        assertEquals(listOf("Headingbody" to ParagraphKind.Heading), next.kinds())
    }

    @Test
    fun enterAtTheStartOfAHeadingPushesItDown() {
        val (next, _) = state(p("Heading", ParagraphKind.Heading)).type(0, "\n")
        assertEquals(listOf("" to ParagraphKind.Body, "Heading" to ParagraphKind.Heading), next.kinds())
    }

    @Test
    fun pastingSeveralLinesIntoABulletListContinuesIt() {
        val (next, _) = state(p("one", ParagraphKind.BulletList)).type(3, "\ntwo\nthree")
        assertEquals(List(3) { ParagraphKind.BulletList }, next.kinds().map { it.second })
    }

    @Test
    fun boldTogglesOnASelectionAndBackOff() {
        val start = state(p("hello world"))
        val bolded = start.toggleInline(0 until 5, InlineAttribute.Bold)
        assertEquals(listOf(InlineSpan(5, InlineStyle(bold = true)), InlineSpan(6, InlineStyle.Plain)), bolded.toParagraphs()[0].spans)
        assertTrue(bolded.isActive(0 until 5, InlineAttribute.Bold))
        val unbolded = bolded.toggleInline(0 until 5, InlineAttribute.Bold)
        assertEquals(listOf(InlineSpan(11, InlineStyle.Plain)), unbolded.toParagraphs()[0].spans)
    }

    @Test
    fun typingStyleAppliesToTheNextTypedText() {
        val start = state(p("ab"))
        val armed = start.toggleInline(2 until 2, InlineAttribute.Italic)
        assertTrue(armed.isActive(2 until 2, InlineAttribute.Italic))
        val (typed, _) = armed.type(2, "c")
        assertEquals(listOf(InlineSpan(2, InlineStyle.Plain), InlineSpan(1, InlineStyle(italic = true))), typed.toParagraphs()[0].spans)
        // Typing continues in the same style.
        val (more, _) = typed.type(3, "d")
        assertEquals(InlineSpan(2, InlineStyle(italic = true)), more.toParagraphs()[0].spans.last())
    }

    @Test
    fun typingAfterALinkDoesNotExtendTheLink() {
        val start = state(p("site", spans = listOf(InlineSpan(4, InlineStyle(link = "https://example.com")))))
        val (next, _) = start.type(4, "!")
        assertEquals(InlineSpan(1, InlineStyle.Plain), next.toParagraphs()[0].spans.last())
    }

    @Test
    fun attachmentsCantBeDeletedOrInserted() {
        val start = state(p("a￼b"))
        val (afterDelete, adjusted) = start.applyTextChange("ab", 1)
        assertEquals(start.text, adjusted)
        assertEquals(start, afterDelete)
        val (afterInsert, kept) = start.type(0, "￼x")
        assertEquals("xa￼b", kept)
        assertEquals("xa￼b", afterInsert.text)
    }

    @Test
    fun listToggleTurnsListsOffAgain() {
        val start = state(p("one"), p("two"))
        val listed = start.setLineKind(0 until 7, ParagraphKind.NumberedList)
        assertEquals(List(2) { ParagraphKind.NumberedList }, listed.kinds().map { it.second })
        val unlisted = listed.setLineKind(0 until 7, ParagraphKind.NumberedList)
        assertEquals(List(2) { ParagraphKind.Body }, unlisted.kinds().map { it.second })
    }

    @Test
    fun checklistDoneTogglesAndIndentStaysInRange() {
        val start = state(p("todo", ParagraphKind.Checklist))
        assertTrue(start.toggleDone(0).toParagraphs()[0].done)
        assertFalse(start.toggleDone(0).toggleDone(0).toParagraphs()[0].done)
        var indented = start
        repeat(10) { indented = indented.indent(0 until 0, 1) }
        assertEquals(EditorState.MAX_INDENT, indented.toParagraphs()[0].indent)
        assertEquals(0, start.indent(0 until 0, -1).toParagraphs()[0].indent)
    }

    @Test
    fun newNoteStartsWithATitleLine() {
        val (typed, _) = EditorState.newNote().type(0, "Shopping\nbread")
        assertEquals(listOf("Shopping" to ParagraphKind.Title, "bread" to ParagraphKind.Body), typed.kinds())
    }
}
