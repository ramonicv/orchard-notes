package dev.rortega.orchardnotes.ui.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import dev.rortega.orchardnotes.notes.doc.OBJECT_REPLACEMENT_CHARACTER
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import dev.rortega.orchardnotes.ui.theme.NoteTextStyles

data class EditorColors(val accent: Color, val muted: Color, val link: Color, val attachment: Color)

/** A checklist circle drawn in the text, as a range of the transformed text. */
data class CheckboxMarker(val lineIndex: Int, val range: IntRange)

/**
 * How [EditorState] looks inside the text field: list markers and checkboxes inserted
 * before their lines, heading sizes, inline styles, and attachment placeholders. The
 * offset mapping keeps the cursor out of the markers.
 */
class EditorLayout(val state: EditorState, colors: EditorColors) {
    val text: AnnotatedString
    val checkboxes: List<CheckboxMarker>
    private val originalLineStarts: IntArray
    private val transformedTextStarts: IntArray
    private val transformedBlockStarts: IntArray

    init {
        val ranges = state.lineRanges()
        originalLineStarts = IntArray(ranges.size) { ranges[it].first }
        transformedTextStarts = IntArray(ranges.size)
        transformedBlockStarts = IntArray(ranges.size)
        val boxes = mutableListOf<CheckboxMarker>()
        val counters = mutableMapOf<Int, Int>()

        text = buildAnnotatedString {
            ranges.forEachIndexed { index, range ->
                val line = state.lines[index]
                val blockStart = length
                transformedBlockStarts[index] = blockStart

                val marker = (indentPrefix(line) + (markerFor(line, counters) ?: "")).ifEmpty { null }
                if (marker != null) {
                    append(marker)
                    val markerColor = if (line.kind == ParagraphKind.Checklist) {
                        if (line.done) colors.accent else colors.muted
                    } else {
                        colors.muted
                    }
                    addStyle(SpanStyle(color = markerColor), blockStart, length)
                    if (line.kind == ParagraphKind.Checklist) {
                        val circle = blockStart + marker.length - 2
                        boxes += CheckboxMarker(index, circle until circle + 1)
                    }
                }
                val textStart = length
                transformedTextStarts[index] = textStart
                append(state.text.substring(range.first, range.last + 1).replace(OBJECT_REPLACEMENT_CHARACTER, ATTACHMENT_GLYPH))
                val textEnd = length

                // Inline styles from the runs covering this line.
                var at = 0
                for (run in state.runs) {
                    val from = maxOf(at, range.first)
                    val to = minOf(at + run.length, range.last + 1)
                    at += run.length
                    if (from >= to) continue
                    inlineSpan(run.style, colors)?.let { addStyle(it, textStart + from - range.first, textStart + to - range.first) }
                }
                for (i in range) {
                    if (state.text[i] == OBJECT_REPLACEMENT_CHARACTER) {
                        addStyle(SpanStyle(color = colors.attachment, fontWeight = FontWeight.Bold), textStart + i - range.first, textStart + i - range.first + 1)
                    }
                }
                lineSpan(line.kind)?.let { addStyle(it, blockStart, textEnd) }
                if (line.kind == ParagraphKind.Checklist && line.done) addStyle(SpanStyle(color = colors.muted), textStart, textEnd)

                // No per-line ParagraphStyle: in a text field each one splits the text into separate
                // paragraphs and adds blank lines. Indentation lives in the marker instead.
                if (index < ranges.lastIndex) append('\n')
            }
        }
        checkboxes = boxes
    }

    val offsetMapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int {
            val line = lineContaining(originalLineStarts, offset)
            return transformedTextStarts[line] + (offset - originalLineStarts[line])
        }

        override fun transformedToOriginal(offset: Int): Int {
            val line = lineContaining(transformedBlockStarts, offset)
            val intoText = (offset - transformedTextStarts[line]).coerceAtLeast(0)
            return (originalLineStarts[line] + intoText).coerceAtMost(state.text.length)
        }
    }

    private fun lineContaining(starts: IntArray, offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun markerFor(line: LineStyle, counters: MutableMap<Int, Int>): String? {
        if (!line.kind.isList) {
            counters.clear()
            return null
        }
        counters.keys.filter { it > line.indent }.forEach(counters::remove)
        return when (line.kind) {
            ParagraphKind.NumberedList -> {
                val next = counters[line.indent]?.plus(1) ?: NoteFormat.effectiveStart(line.startNumber)
                counters[line.indent] = next
                "$next. "
            }
            ParagraphKind.Checklist -> {
                counters.remove(line.indent)
                if (line.done) "◉ " else "○ "
            }
            ParagraphKind.DashList -> {
                counters.remove(line.indent)
                "–  "
            }
            else -> {
                counters.remove(line.indent)
                "•  "
            }
        }
    }

    private fun lineSpan(kind: ParagraphKind): SpanStyle? = when (kind) {
        ParagraphKind.Title -> NoteTextStyles.title.toSpanStyle()
        ParagraphKind.Heading -> NoteTextStyles.heading.toSpanStyle()
        ParagraphKind.Subheading -> NoteTextStyles.subheading.toSpanStyle()
        ParagraphKind.Monospaced -> SpanStyle(fontFamily = FontFamily.Monospace)
        else -> null
    }

    /** Quote bar and list nesting, drawn as leading characters. */
    private fun indentPrefix(line: LineStyle): String =
        "▎ ".repeat(line.blockQuoteLevel) + if (line.kind.isList) "\u2003".repeat(line.indent) else ""

    private fun inlineSpan(style: dev.rortega.orchardnotes.notes.doc.InlineStyle, colors: EditorColors): SpanStyle? {
        if (style == dev.rortega.orchardnotes.notes.doc.InlineStyle.Plain) return null
        val decorations = buildList {
            if (style.underline || style.link.isNotEmpty()) add(TextDecoration.Underline)
            if (style.strikethrough) add(TextDecoration.LineThrough)
        }
        return SpanStyle(
            fontWeight = if (style.bold) FontWeight.Bold else null,
            fontStyle = if (style.italic) FontStyle.Italic else null,
            textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
            color = if (style.link.isNotEmpty()) colors.link else Color.Unspecified,
        )
    }

    companion object {
        /** Stands in for an attachment's U+FFFC placeholder (same length, so offsets don't shift). */
        const val ATTACHMENT_GLYPH = '▣'
    }
}

/** Renders the editor through an [EditorLayout] built for the current state. */
class EditorVisualTransformation(private val layout: EditorLayout) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        if (text.text == layout.state.text) {
            TransformedText(layout.text, layout.offsetMapping)
        } else {
            // The field and the model are momentarily out of step; show the raw text this frame.
            TransformedText(text, OffsetMapping.Identity)
        }
}
