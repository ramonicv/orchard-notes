package dev.rortega.orchardnotes.ui.editor

import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.InlineSpan
import dev.rortega.orchardnotes.notes.doc.InlineStyle
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import dev.rortega.orchardnotes.notes.doc.OBJECT_REPLACEMENT_CHARACTER
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import dev.rortega.orchardnotes.notes.doc.Splice
import dev.rortega.orchardnotes.notes.doc.TextDiff

/** Paragraph-level attributes of one line. */
data class LineStyle(
    val kind: ParagraphKind = ParagraphKind.Body,
    val indent: Int = 0,
    val blockQuoteLevel: Int = 0,
    val done: Boolean = false,
    val startNumber: Int = 0,
)

/** Inline style over [length] UTF-16 units of the full text (newlines included). */
data class StyleRun(val length: Int, val style: InlineStyle)

/** Which inline attribute a toolbar button toggles. */
enum class InlineAttribute { Bold, Italic, Underline, Strikethrough }

/**
 * The editor's document: full text, one [LineStyle] per line, and inline style runs.
 * Immutable; every operation returns a new state. Text changes arrive as whole new
 * strings from the text field and are mapped onto paragraphs the way Apple Notes
 * behaves (see [applyTextChange]).
 */
data class EditorState(
    val text: String,
    val lines: List<LineStyle>,
    val runs: List<StyleRun>,
    /** Style for the next typed characters, set by toolbar toggles with no selection. */
    val typingStyle: InlineStyle? = null,
) {
    init {
        require(lines.size == text.count { it == '\n' } + 1) { "One LineStyle per line" }
    }

    fun lineStart(index: Int): Int {
        var offset = 0
        repeat(index) { offset = text.indexOf('\n', offset) + 1 }
        return offset
    }

    fun lineIndexAt(offset: Int): Int = text.substring(0, offset.coerceIn(0, text.length)).count { it == '\n' }

    /** Line ranges `[start, end)` (excluding the newline) for every line. */
    fun lineRanges(): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (true) {
            val end = text.indexOf('\n', start)
            if (end == -1) {
                ranges += start until text.length
                return ranges
            }
            ranges += start until end
            start = end + 1
        }
    }

    fun styleAt(offset: Int): InlineStyle {
        var at = 0
        for (run in runs) {
            if (offset < at + run.length) return run.style
            at += run.length
        }
        return runs.lastOrNull()?.style ?: InlineStyle.Plain
    }

    /** The inline style typing at [cursor] would produce. */
    fun effectiveTypingStyle(cursor: Int): InlineStyle =
        typingStyle ?: if (cursor > 0) styleAt(cursor - 1).copy(link = "") else styleAt(0).copy(link = "")

    /**
     * Applies an edit made in the text field. Returns the new state and, when the
     * change was adjusted (e.g. Enter on an empty list item leaves the list instead of
     * adding a line, or an attachment was about to be deleted), the text actually kept.
     */
    fun applyTextChange(newText: String, cursorAfter: Int): Pair<EditorState, String?> {
        if (newText == text) return this to null
        val splice = spliceTo(newText, cursorAfter)
        val deleted = text.substring(splice.start, splice.start + splice.deleteLength)
        // Attachments are tied to their placeholder; they can't be removed or created here.
        if (OBJECT_REPLACEMENT_CHARACTER in deleted) return this to text
        val inserted = splice.insertText.replace(OBJECT_REPLACEMENT_CHARACTER.toString(), "")
        if (inserted != splice.insertText) {
            val cursor = cursorAfter - (splice.insertText.length - inserted.length)
            return applyTextChange(text.substring(0, splice.start) + inserted + text.substring(splice.start + splice.deleteLength), cursor)
                .let { (state, _) -> state to state.text }
        }

        val lineIndex = lineIndexAt(splice.start)
        val line = lines[lineIndex]
        val lineRange = lineRanges()[lineIndex]

        // Enter on an empty list item ends the list instead of adding another empty item.
        if (inserted == "\n" && splice.deleteLength == 0 && line.kind.isList && lineRange.isEmpty()) {
            return copy(lines = lines.toMutableList().also { it[lineIndex] = LineStyle(blockQuoteLevel = line.blockQuoteLevel) }) to text
        }

        val removedNewlines = deleted.count { it == '\n' }
        val addedNewlines = inserted.count { it == '\n' }
        val newLines = lines.toMutableList()
        // Joining lines keeps the upper line's style.
        repeat(removedNewlines) { newLines.removeAt(lineIndex + 1) }
        if (addedNewlines > 0) {
            val column = splice.start - lineRange.first
            val onlyNewlines = inserted.all { it == '\n' }
            val continuation = continuationOf(line)
            if (column == 0 && onlyNewlines && splice.deleteLength == 0 && lineRange.isEmpty().not()) {
                // Enter at the start of a line pushes the line down with its style; new lines above are plain.
                repeat(addedNewlines) { newLines.add(lineIndex, continuation.copy(done = false)) }
            } else {
                repeat(addedNewlines) { newLines.add(lineIndex + 1, continuation) }
            }
        }

        val typing = effectiveTypingStyle(splice.start)
        val newRuns = spliceRuns(runs, splice.start, splice.deleteLength, inserted.length, typing)
        val state = EditorState(newText.takeIf { inserted == splice.insertText } ?: text, newLines, newRuns, typingStyle = null)
        return state to null
    }

    /**
     * The edit that turned [text] into [newText]. Next to a repeated character the same edit
     * fits in more than one place: Return at the end of "eggs" in "eggs\n" gives the same
     * text as Return at the start of the line below, and backspace on an empty line can read
     * as deleting the newline after it. Which line the edit belongs to decides which line's
     * style is kept, so the cursor, which ends just after what was typed (or where text was
     * deleted), says where it was made.
     */
    private fun spliceTo(newText: String, cursorAfter: Int): Splice {
        // As late as the edit fits; it fits earlier for as long as the text after it still matches.
        val splice = TextDiff.computeSplice(text, newText)
        var commonSuffix = 0
        val maxSuffix = minOf(text.length, newText.length)
        while (commonSuffix < maxSuffix && text[text.length - 1 - commonSuffix] == newText[newText.length - 1 - commonSuffix]) commonSuffix++
        val earliest = maxOf(0, text.length - splice.deleteLength - commonSuffix)
        val start = (cursorAfter - splice.insertText.length).coerceIn(earliest, splice.start)
        val end = start + splice.deleteLength
        // Never between the two halves of a surrogate pair.
        if (start == splice.start || text.getOrNull(start)?.isLowSurrogate() == true || text.getOrNull(end)?.isLowSurrogate() == true) return splice
        return Splice(start, splice.deleteLength, newText.substring(start, start + splice.insertText.length))
    }

    /** Style for a line created by pressing Enter at the end of [line]. */
    private fun continuationOf(line: LineStyle): LineStyle = when (line.kind) {
        ParagraphKind.Title, ParagraphKind.Heading, ParagraphKind.Subheading -> LineStyle(blockQuoteLevel = line.blockQuoteLevel)
        ParagraphKind.Checklist -> line.copy(done = false, startNumber = 0)
        else -> line.copy(startNumber = 0)
    }

    /** Applies [kind] to every line touched by the selection; toggles back to Body for lists. */
    fun setLineKind(selection: IntRange, kind: ParagraphKind): EditorState {
        val indexes = lineIndexesIn(selection)
        val allAlready = indexes.all { lines[it].kind == kind }
        val target = if (allAlready && kind.isList) ParagraphKind.Body else kind
        return copy(
            lines = lines.mapIndexed { i, line ->
                if (i !in indexes) line else line.copy(kind = target, done = if (target == ParagraphKind.Checklist) line.done else false, indent = if (target.isList) line.indent else 0)
            },
        )
    }

    fun toggleDone(lineIndex: Int): EditorState {
        val line = lines.getOrNull(lineIndex)?.takeIf { it.kind == ParagraphKind.Checklist } ?: return this
        return copy(lines = lines.toMutableList().also { it[lineIndex] = line.copy(done = !line.done) })
    }

    /** Indents (or with negative [delta], outdents) list lines; others change quote level. */
    fun indent(selection: IntRange, delta: Int): EditorState = copy(
        lines = lines.mapIndexed { i, line ->
            when {
                i !in lineIndexesIn(selection) -> line
                line.kind.isList -> line.copy(indent = (line.indent + delta).coerceIn(0, MAX_INDENT))
                else -> line.copy(blockQuoteLevel = (line.blockQuoteLevel + delta).coerceIn(0, 1))
            }
        },
    )

    /** Toggles an inline attribute on the selection, or for the next typed text if it's empty. */
    fun toggleInline(selection: IntRange, attribute: InlineAttribute): EditorState {
        if (selection.isEmpty()) {
            val current = effectiveTypingStyle(selection.first)
            return copy(typingStyle = current.toggled(attribute, !current.has(attribute)))
        }
        val enable = !(selection).all { styleAt(it).has(attribute) || text[it] == '\n' }
        return copy(runs = mapRuns(selection.first, selection.last + 1) { it.toggled(attribute, enable) })
    }

    fun isActive(selection: IntRange, attribute: InlineAttribute): Boolean =
        if (selection.isEmpty()) effectiveTypingStyle(selection.first).has(attribute) else selection.all { styleAt(it).has(attribute) || text[it] == '\n' }

    fun lineIndexesIn(selection: IntRange): IntRange {
        val first = lineIndexAt(selection.first)
        val last = lineIndexAt(if (selection.isEmpty()) selection.first else selection.last)
        return first..last
    }

    /** The paragraph model the save pipeline consumes. */
    fun toParagraphs(): List<FormatParagraph> {
        val ranges = lineRanges()
        return ParagraphMerge.withOffsets(
            ranges.mapIndexed { i, range ->
                val line = lines[i]
                FormatParagraph(
                    kind = line.kind,
                    indent = if (line.kind.isList) line.indent else 0,
                    blockQuoteLevel = line.blockQuoteLevel,
                    done = line.kind == ParagraphKind.Checklist && line.done,
                    startNumber = line.startNumber,
                    text = text.substring(range.first, range.last + 1),
                    spans = spansIn(range.first, range.last + 1),
                )
            },
        )
    }

    private fun spansIn(start: Int, end: Int): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        var at = 0
        for (run in runs) {
            val from = maxOf(at, start)
            val to = minOf(at + run.length, end)
            if (from < to) spans += InlineSpan(to - from, run.style)
            at += run.length
        }
        return NoteFormat.mergeSpans(spans)
    }

    private fun mapRuns(start: Int, end: Int, transform: (InlineStyle) -> InlineStyle): List<StyleRun> {
        val out = mutableListOf<StyleRun>()
        var at = 0
        for (run in runs) {
            val runStart = at
            val runEnd = at + run.length
            at = runEnd
            val cuts = listOf(runStart, start.coerceIn(runStart, runEnd), end.coerceIn(runStart, runEnd), runEnd)
            for (i in 0 until 3) {
                val length = cuts[i + 1] - cuts[i]
                if (length == 0) continue
                out += StyleRun(length, if (i == 1) transform(run.style) else run.style)
            }
        }
        return mergeRuns(out)
    }

    companion object {
        const val MAX_INDENT = 4

        fun fromParagraphs(paragraphs: List<FormatParagraph>): EditorState {
            val source = paragraphs.ifEmpty { listOf(FormatParagraph(kind = ParagraphKind.Title, text = "", spans = emptyList())) }
            val runs = mutableListOf<StyleRun>()
            source.forEachIndexed { i, paragraph ->
                runs += paragraph.spans.map { StyleRun(it.length, it.style) }
                val covered = paragraph.spans.sumOf { it.length }
                if (covered < paragraph.text.length) runs += StyleRun(paragraph.text.length - covered, InlineStyle.Plain)
                if (i < source.lastIndex) {
                    // The newline carries the line's last style, like Apple's runs.
                    runs += StyleRun(1, paragraph.spans.lastOrNull()?.style?.copy(link = "") ?: InlineStyle.Plain)
                }
            }
            return EditorState(
                text = source.joinToString("\n") { it.text },
                lines = source.map { LineStyle(it.kind, it.indent, it.blockQuoteLevel, it.done, it.startNumber) },
                runs = mergeRuns(runs),
            )
        }

        /** A new note starts on an empty Title line. */
        fun newNote() = EditorState("", listOf(LineStyle(ParagraphKind.Title)), emptyList())

        internal fun spliceRuns(runs: List<StyleRun>, start: Int, deleteLength: Int, insertLength: Int, insertStyle: InlineStyle): List<StyleRun> {
            val out = mutableListOf<StyleRun>()
            var at = 0
            var inserted = insertLength == 0
            for (run in runs) {
                val runStart = at
                val runEnd = at + run.length
                at = runEnd
                val keepBefore = (minOf(runEnd, start) - runStart).coerceAtLeast(0)
                val keepAfter = (runEnd - maxOf(runStart, start + deleteLength)).coerceAtLeast(0)
                if (keepBefore > 0) out += StyleRun(keepBefore, run.style)
                if (!inserted && runEnd >= start) {
                    out += StyleRun(insertLength, insertStyle)
                    inserted = true
                }
                if (keepAfter > 0) out += StyleRun(keepAfter, run.style)
            }
            if (!inserted) out += StyleRun(insertLength, insertStyle)
            return mergeRuns(out)
        }

        internal fun mergeRuns(runs: List<StyleRun>): List<StyleRun> {
            val out = mutableListOf<StyleRun>()
            for (run in runs) {
                if (run.length == 0) continue
                val previous = out.lastOrNull()
                if (previous != null && previous.style == run.style) {
                    out[out.lastIndex] = previous.copy(length = previous.length + run.length)
                } else {
                    out += run
                }
            }
            return out
        }
    }
}

internal fun InlineStyle.has(attribute: InlineAttribute): Boolean = when (attribute) {
    InlineAttribute.Bold -> bold
    InlineAttribute.Italic -> italic
    InlineAttribute.Underline -> underline
    InlineAttribute.Strikethrough -> strikethrough
}

internal fun InlineStyle.toggled(attribute: InlineAttribute, enabled: Boolean): InlineStyle = when (attribute) {
    InlineAttribute.Bold -> copy(bold = enabled)
    InlineAttribute.Italic -> copy(italic = enabled)
    InlineAttribute.Underline -> copy(underline = enabled)
    InlineAttribute.Strikethrough -> copy(strikethrough = enabled)
}
