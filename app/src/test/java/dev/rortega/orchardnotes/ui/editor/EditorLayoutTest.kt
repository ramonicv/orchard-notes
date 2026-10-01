package dev.rortega.orchardnotes.ui.editor

import androidx.compose.ui.graphics.Color
import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.InlineSpan
import dev.rortega.orchardnotes.notes.doc.InlineStyle
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorLayoutTest {
    private val colors = EditorColors(Color.Red, Color.Gray, Color.Blue, Color.Green)

    private fun layout(vararg lines: Pair<String, ParagraphKind>) = EditorLayout(
        EditorState.fromParagraphs(
            ParagraphMerge.withOffsets(lines.map { (text, kind) -> FormatParagraph(kind = kind, text = text, spans = listOf(InlineSpan(text.length, InlineStyle.Plain))) }),
        ),
        colors,
    )

    @Test
    fun markersAreInsertedAndNumberedListsCount() {
        val layout = layout("Title" to ParagraphKind.Title, "a" to ParagraphKind.NumberedList, "b" to ParagraphKind.NumberedList, "c" to ParagraphKind.Checklist)
        assertEquals("Title\n1. a\n2. b\n○ c", layout.text.text)
        assertEquals(listOf(3), layout.checkboxes.map { it.lineIndex })
    }

    @Test
    fun offsetsMapAroundMarkersBothWays() {
        val layout = layout("x" to ParagraphKind.Body, "item" to ParagraphKind.BulletList)
        // "x\n•  item": original offset 2 (start of "item") lands after the marker.
        val mapping = layout.offsetMapping
        assertEquals(5, mapping.originalToTransformed(2))
        assertEquals(9, mapping.originalToTransformed(6))
        // Positions inside the marker snap to the start of the line's text.
        assertEquals(2, mapping.transformedToOriginal(2))
        assertEquals(2, mapping.transformedToOriginal(4))
        assertEquals(4, mapping.transformedToOriginal(7))
        assertEquals(1, mapping.transformedToOriginal(1))
    }
}
