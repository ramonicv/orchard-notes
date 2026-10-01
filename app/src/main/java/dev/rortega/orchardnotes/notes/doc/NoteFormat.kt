package dev.rortega.orchardnotes.notes.doc

import kotlinx.serialization.Serializable

/*
 * The semantic formatting model between Apple's attribute runs and this app's UI.
 *
 * Wire values (as documented by icloud-md, verified against captured traffic):
 * paragraph style 0 = Title, 1 = Heading, 2 = Subheading, 3 = Body (absent also means
 * Body), 4 = Monospaced, 100 = bulleted list, 101 = dashed list, 102 = numbered list,
 * 103 = checklist (with `todo {uuid, done}`); `indent` is list nesting depth;
 * `fontHints` bit 1 = bold, bit 2 = italic; underline and strikethrough are flags;
 * `link` covers exactly the linked range.
 *
 * A paragraph's style comes from the run covering its trailing newline (or its last
 * character for the final, unterminated line).
 */

enum class ParagraphKind(val wireStyle: Int) {
    Title(0),
    Heading(1),
    Subheading(2),
    Body(3),
    Monospaced(4),
    BulletList(100),
    DashList(101),
    NumberedList(102),
    Checklist(103),
    ;

    val isList: Boolean get() = this == BulletList || this == DashList || this == NumberedList || this == Checklist

    companion object {
        fun fromWire(style: Int): ParagraphKind? = entries.firstOrNull { it.wireStyle == style }
    }
}

@Serializable
data class InlineStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    /** Link target; empty means not a link. */
    val link: String = "",
) {
    companion object {
        val Plain = InlineStyle()
    }
}

@Serializable
data class InlineSpan(val length: Int, val style: InlineStyle)

@Serializable
data class FormatParagraph(
    val kind: ParagraphKind,
    /** List nesting depth (0 = top level). */
    val indent: Int = 0,
    val blockQuoteLevel: Int = 0,
    /** Checklist state; only meaningful for [ParagraphKind.Checklist]. */
    val done: Boolean = false,
    /** `startingListItemNumber`; 0 means the default (1). */
    val startNumber: Int = 0,
    /** Paragraph text without its trailing newline. */
    val text: String,
    /** Spans covering [text] exactly, in order. */
    val spans: List<InlineSpan>,
    /** UTF-16 offset of [text] within the note text. */
    val start: Int = 0,
)

sealed interface FormatResult {
    data class Ok(val paragraphs: List<FormatParagraph>) : FormatResult
    data class Unsupported(val reason: String) : FormatResult
}

object NoteFormat {

    fun inlineStyleOf(run: AttributeRun): InlineStyle = InlineStyle(
        bold = run.fontHints and 1 != 0,
        italic = run.fontHints and 2 != 0,
        underline = run.underline == 1,
        strikethrough = run.strikethrough == 1,
        link = run.link,
    )

    fun paragraphKindOf(style: ParagraphStyle?): ParagraphKind? =
        if (style == null || !style.hasStyle) ParagraphKind.Body else ParagraphKind.fromWire(style.style)

    /**
     * Splits [text] into paragraphs and derives each one's paragraph attributes and
     * inline spans. Runs that under-cover the text leave the tail plain Body; runs that
     * overshoot it, or an unknown paragraph style, make the note unsupported.
     */
    fun decode(text: String, attributeRuns: List<AttributeRun>): FormatResult {
        val covered = attributeRuns.sumOf { it.length }
        if (covered > text.length) return FormatResult.Unsupported("the note's formatting runs overshoot its text")
        val styles = attributeRuns.map { it.paragraphStyle }
        styles.forEach { style ->
            if (style != null && paragraphKindOf(style) == null) {
                return FormatResult.Unsupported("the note uses a paragraph style (${style.style}) this app doesn't understand")
            }
        }

        // Absolute [start, end) interval per non-empty run.
        val starts = IntArray(attributeRuns.size)
        run {
            var offset = 0
            attributeRuns.forEachIndexed { i, run ->
                starts[i] = offset
                offset += run.length
            }
        }
        val inlineStyles = attributeRuns.map(::inlineStyleOf)
        fun runIndexAt(charIndex: Int): Int {
            // Binary search for the last run starting at or before charIndex with non-zero length covering it.
            var lo = 0
            var hi = attributeRuns.size - 1
            var found = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (starts[mid] <= charIndex) {
                    found = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            // Skip back over zero-length runs that share the same start.
            while (found >= 0 && attributeRuns[found].length == 0) found--
            return if (found >= 0 && charIndex < starts[found] + attributeRuns[found].length) found else -1
        }

        val paragraphs = mutableListOf<FormatParagraph>()
        val lines = text.split('\n')
        var offset = 0
        lines.forEachIndexed { lineIndex, line ->
            val lineStart = offset
            val lineEnd = lineStart + line.length
            val hasNewline = lineIndex < lines.lastIndex
            offset = lineEnd + if (hasNewline) 1 else 0

            val anchorIndex = if (hasNewline) lineEnd else lineEnd - 1
            val anchorRun = if (anchorIndex >= lineStart) runIndexAt(anchorIndex) else -1
            val style = if (anchorRun >= 0) styles[anchorRun] else null
            val kind = paragraphKindOf(style) ?: ParagraphKind.Body

            val spans = mutableListOf<InlineSpan>()
            var at = lineStart
            while (at < lineEnd) {
                val runIndex = runIndexAt(at)
                val spanEnd = if (runIndex < 0) {
                    // Uncovered tail, or a gap before the next covering run.
                    lineEnd
                } else {
                    minOf(lineEnd, starts[runIndex] + attributeRuns[runIndex].length)
                }
                val spanStyle = if (runIndex < 0) InlineStyle.Plain else inlineStyles[runIndex]
                val previous = spans.lastOrNull()
                if (previous != null && previous.style == spanStyle) {
                    spans[spans.lastIndex] = previous.copy(length = previous.length + (spanEnd - at))
                } else {
                    spans += InlineSpan(spanEnd - at, spanStyle)
                }
                at = spanEnd
            }

            paragraphs += FormatParagraph(
                kind = kind,
                indent = style?.indent ?: 0,
                blockQuoteLevel = style?.blockQuoteLevel ?: 0,
                done = kind == ParagraphKind.Checklist && style?.todo?.done == true,
                startNumber = style?.startingListItemNumber ?: 0,
                text = line,
                spans = spans,
                start = lineStart,
            )
        }
        return FormatResult.Ok(paragraphs)
    }

    /** Every line as an unstyled Body paragraph. */
    fun plainParagraphs(text: String): List<FormatParagraph> {
        var offset = 0
        return text.split('\n').map { line ->
            FormatParagraph(
                kind = ParagraphKind.Body,
                text = line,
                spans = if (line.isEmpty()) emptyList() else listOf(InlineSpan(line.length, InlineStyle.Plain)),
                start = offset,
            ).also { offset += line.length + 1 }
        }
    }

    /** Merges adjacent spans with equal styles and drops empty ones. */
    fun mergeSpans(spans: List<InlineSpan>): List<InlineSpan> {
        val out = mutableListOf<InlineSpan>()
        for (span in spans) {
            if (span.length == 0) continue
            val previous = out.lastOrNull()
            if (previous != null && previous.style == span.style) {
                out[out.lastIndex] = previous.copy(length = previous.length + span.length)
            } else {
                out += span
            }
        }
        return out
    }

    /**
     * Whether two paragraphs at the same position agree on everything this app
     * edits: kind, text, nesting, quote level, checklist state, numbering, and spans.
     * The numbered-list start only matters at the first item of a group, since later
     * items are numbered by counting.
     */
    fun paragraphsEqual(a: FormatParagraph, b: FormatParagraph, previousA: FormatParagraph?, previousB: FormatParagraph?): Boolean {
        if (a.kind != b.kind || a.text != b.text || a.blockQuoteLevel != b.blockQuoteLevel) return false
        if (a.kind.isList && a.indent != b.indent) return false
        if (a.kind == ParagraphKind.Checklist && a.done != b.done) return false
        if (a.kind == ParagraphKind.NumberedList) {
            val groupStartA = previousA == null || previousA.kind != ParagraphKind.NumberedList || previousA.indent != a.indent
            val groupStartB = previousB == null || previousB.kind != ParagraphKind.NumberedList || previousB.indent != b.indent
            if (groupStartA != groupStartB) return false
            if (groupStartA && effectiveStart(a.startNumber) != effectiveStart(b.startNumber)) return false
        }
        return mergeSpans(a.spans) == mergeSpans(b.spans)
    }

    fun formatsEqual(a: List<FormatParagraph>, b: List<FormatParagraph>): Boolean =
        a.size == b.size && a.indices.all { paragraphsEqual(a[it], b[it], a.getOrNull(it - 1), b.getOrNull(it - 1)) }

    fun effectiveStart(startNumber: Int): Int = if (startNumber == 0) 1 else startNumber
}
