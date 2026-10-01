package dev.rortega.orchardnotes.notes

import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.InlineSpan
import dev.rortega.orchardnotes.notes.doc.InlineStyle
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import dev.rortega.orchardnotes.notes.doc.ParagraphKind

/**
 * Three-way merge that never conflicts, for notes several people edit at once: concurrent
 * edits combine the way Apple's CRDT combines them. Text deleted on either side is gone,
 * text inserted on either side is kept (theirs first where both inserted at the same
 * place, once where both inserted the same thing), and formatting follows whichever side
 * changed it. As in Apple's format, a paragraph's style belongs to the newline ending it.
 */
object LiveMerge {

    class Result(
        val paragraphs: List<FormatParagraph>,
        private val oursOffsets: IntArray,
    ) {
        /** Where an offset in ours' text (a cursor, say) ended up in the merged text. */
        fun mapOursOffset(offset: Int): Int = oursOffsets[offset.coerceIn(0, oursOffsets.lastIndex)]
    }

    fun merge(base: List<FormatParagraph>, ours: List<FormatParagraph>, theirs: List<FormatParagraph>): Result {
        val b = Flat.of(base)
        val o = Flat.of(ours)
        // Theirs is usually a stored version, which can't keep an empty last line's style:
        // losing it there isn't a change (an item just started at the end of a list stays).
        val t = Flat.of(NoteFormat.keepEmptyLastLineStyle(from = base, onto = theirs))
        val toOurs = Alignment.align(b.text, o.text)
        val toTheirs = Alignment.align(b.text, t.text)
        val oursInserts = insertionsByGap(toOurs, o.text.length, b.text.length)
        val theirsInserts = insertionsByGap(toTheirs, t.text.length, b.text.length)

        val out = StringBuilder()
        val styles = ArrayList<InlineStyle>()
        // For each emitted newline, the attributes of the paragraph it ends.
        val endsParagraph = ArrayList<FormatParagraph?>()
        val oursOffsets = IntArray(o.text.length + 1)

        fun emit(char: Char, style: InlineStyle, paragraph: FormatParagraph?) {
            out.append(char)
            styles += style
            endsParagraph += paragraph
        }
        fun emitOurs(j: Int) {
            oursOffsets[j] = out.length
            emit(o.text[j], o.styles[j], o.attributesAt(j).takeIf { o.text[j] == '\n' })
        }
        fun emitTheirs(k: Int) = emit(t.text[k], t.styles[k], t.attributesAt(k).takeIf { t.text[k] == '\n' })

        for (gap in 0..b.text.length) {
            val mine = oursInserts[gap]
            val other = theirsInserts[gap]
            if (!mine.isEmpty() && o.text.slice(mine) == t.text.slice(other)) {
                mine.forEach(::emitOurs)
            } else {
                other.forEach(::emitTheirs)
                mine.forEach(::emitOurs)
            }
            if (gap == b.text.length) break

            val j = toOurs[gap]
            val k = toTheirs[gap]
            if (j >= 0) oursOffsets[j] = out.length
            if (j < 0 || k < 0) continue
            val style = if (o.styles[j] != b.styles[gap]) o.styles[j] else t.styles[k]
            val paragraph = if (b.text[gap] == '\n') {
                val ourParagraph = o.attributesAt(j)
                if (ourParagraph != b.attributesAt(gap)) ourParagraph else t.attributesAt(k)
            } else {
                null
            }
            emit(b.text[gap], style, paragraph)
        }
        oursOffsets[o.text.length] = out.length
        // Every version ends with a virtual newline, so the output must too. Where the base ends
        // with a newline of its own, the two can align either way round: if each side kept a
        // different one, both are gone and the last paragraph would be lost with them.
        if (out.lastOrNull() != '\n') {
            val ourEnd = o.attributesAt(o.text.lastIndex)
            emit('\n', InlineStyle.Plain, if (ourEnd != b.attributesAt(b.text.lastIndex)) ourEnd else t.attributesAt(t.text.lastIndex))
        }

        val paragraphs = mutableListOf<FormatParagraph>()
        var start = 0
        for (i in out.indices) {
            if (out[i] != '\n') continue
            val attributes = endsParagraph[i] ?: Flat.EMPTY_PARAGRAPH
            paragraphs += attributes.copy(text = out.substring(start, i), spans = spans(styles, start, i))
            start = i + 1
        }
        val textLength = out.length - 1
        val oursTextLength = o.text.length - 1
        return Result(
            ParagraphMerge.withOffsets(paragraphs),
            IntArray(oursTextLength + 1) { minOf(oursOffsets[it], textLength) },
        )
    }

    /**
     * For each base gap (before base char `g`, or at the end), the side's characters
     * inserted there. Inserted text goes before the next base character the side kept, so
     * it follows any base text the side deleted in between.
     */
    private fun insertionsByGap(alignment: IntArray, sideLength: Int, baseLength: Int): Array<IntRange> {
        val gaps = Array(baseLength + 1) { IntRange.EMPTY }
        var previous = -1
        for (g in 0 until baseLength) {
            val match = alignment[g]
            if (match < 0) continue
            if (match > previous + 1) gaps[g] = (previous + 1) until match
            previous = match
        }
        if (sideLength > previous + 1) gaps[baseLength] = (previous + 1) until sideLength
        return gaps
    }

    private fun spans(styles: List<InlineStyle>, start: Int, end: Int): List<InlineSpan> {
        val spans = mutableListOf<InlineSpan>()
        for (i in start until end) {
            val last = spans.lastOrNull()
            if (last != null && last.style == styles[i]) {
                spans[spans.lastIndex] = last.copy(length = last.length + 1)
            } else {
                spans += InlineSpan(1, styles[i])
            }
        }
        return spans
    }

    /** A note as one string ending in a newline, with each character's inline style and paragraph. */
    private class Flat(
        val text: String,
        val styles: Array<InlineStyle>,
        private val paragraphOf: IntArray,
        private val attributes: List<FormatParagraph>,
    ) {
        /** The paragraph attributes (no text) of the paragraph containing, or ended by, char [i]. */
        fun attributesAt(i: Int): FormatParagraph = attributes[paragraphOf[i]]

        companion object {
            val EMPTY_PARAGRAPH = FormatParagraph(ParagraphKind.Body, text = "", spans = emptyList())

            fun of(paragraphs: List<FormatParagraph>): Flat {
                val list = paragraphs.ifEmpty { listOf(EMPTY_PARAGRAPH) }
                val text = StringBuilder()
                val styles = ArrayList<InlineStyle>()
                val paragraphOf = ArrayList<Int>()
                list.forEachIndexed { index, paragraph ->
                    text.append(paragraph.text).append('\n')
                    var covered = 0
                    for (span in paragraph.spans) {
                        repeat(minOf(span.length, paragraph.text.length - covered)) { styles += span.style }
                        covered = minOf(covered + span.length, paragraph.text.length)
                    }
                    repeat(paragraph.text.length - covered) { styles += InlineStyle.Plain }
                    styles += InlineStyle.Plain
                    repeat(paragraph.text.length + 1) { paragraphOf += index }
                }
                return Flat(
                    text.toString(),
                    styles.toTypedArray(),
                    paragraphOf.toIntArray(),
                    list.map { it.copy(text = "", spans = emptyList(), start = 0) },
                )
            }
        }
    }
}

/**
 * Character alignment of two texts along a shortest edit script (Myers' diff): lines first,
 * so unchanged lines anchor everything, then characters within each changed stretch. Fast
 * when the texts differ in few places, however long they are.
 */
internal object Alignment {
    /** Beyond this many edits a stretch counts as replaced wholesale (keeps time and memory bounded). */
    private const val MAX_EDITS = 1_000

    /** For each index of [a], the index of the matching character in [b], or -1 if it was deleted. */
    fun align(a: String, b: String): IntArray {
        val result = IntArray(a.length) { -1 }
        var prefix = 0
        while (prefix < a.length && prefix < b.length && a[prefix] == b[prefix]) {
            result[prefix] = prefix
            prefix++
        }
        var suffix = 0
        while (suffix < a.length - prefix && suffix < b.length - prefix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) {
            result[a.length - 1 - suffix] = b.length - 1 - suffix
            suffix++
        }
        alignLines(a, prefix, a.length - suffix, b, prefix, b.length - suffix, result)
        keepSurrogatePairsWhole(a, b, result)
        return result
    }

    private fun alignLines(a: String, aStart: Int, aEnd: Int, b: String, bStart: Int, bEnd: Int, result: IntArray) {
        if (aStart >= aEnd || bStart >= bEnd) return
        val aLines = lineStarts(a, aStart, aEnd)
        val bLines = lineStarts(b, bStart, bEnd)
        val n = aLines.size - 1
        val m = bLines.size - 1
        val lineMatches = if (n == 1 && m == 1) null else {
            val ids = HashMap<String, Int>()
            fun ids(text: String, starts: IntArray, count: Int) =
                IntArray(count) { ids.getOrPut(text.substring(starts[it], starts[it + 1])) { ids.size } }
            Myers.matches(ids(a, aLines, n), ids(b, bLines, m), MAX_EDITS)
        }
        if (lineMatches == null) {
            alignChars(a, aStart, aEnd, b, bStart, bEnd, result)
            return
        }
        var hunkA = 0
        var hunkB = 0
        for ((i, j) in lineMatches) {
            alignChars(a, aLines[hunkA], aLines[i], b, bLines[hunkB], bLines[j], result)
            for (c in 0 until aLines[i + 1] - aLines[i]) result[aLines[i] + c] = bLines[j] + c
            hunkA = i + 1
            hunkB = j + 1
        }
        alignChars(a, aLines[hunkA], aLines[n], b, bLines[hunkB], bLines[m], result)
    }

    /** Offsets where each line in [start, end) begins, plus [end]; lines keep their newline. */
    private fun lineStarts(text: String, start: Int, end: Int): IntArray {
        val starts = mutableListOf(start)
        for (i in start until end) if (text[i] == '\n' && i + 1 < end) starts += i + 1
        starts += end
        return starts.toIntArray()
    }

    private fun alignChars(a: String, aStart: Int, aEnd: Int, b: String, bStart: Int, bEnd: Int, result: IntArray) {
        if (aStart >= aEnd || bStart >= bEnd) return
        val x = IntArray(aEnd - aStart) { a[aStart + it].code }
        val y = IntArray(bEnd - bStart) { b[bStart + it].code }
        // Too different to align character by character: the whole stretch counts as replaced.
        val matches = Myers.matches(x, y, MAX_EDITS) ?: return
        for ((i, j) in matches) result[aStart + i] = bStart + j
    }

    /** A surrogate pair is kept or replaced as a whole on both sides, never half-matched. */
    private fun keepSurrogatePairsWhole(a: String, b: String, result: IntArray) {
        var changed = true
        while (changed) {
            changed = false
            val matchedInB = BooleanArray(b.length)
            for (index in result) if (index >= 0) matchedInB[index] = true
            for (i in a.indices) {
                val j = result[i]
                if (j < 0) continue
                val brokenInA = (Character.isHighSurrogate(a[i]) && i + 1 < a.length && result[i + 1] != j + 1) ||
                    (Character.isLowSurrogate(a[i]) && i > 0 && result[i - 1] != j - 1)
                val brokenInB = (Character.isHighSurrogate(b[j]) && j + 1 < b.length && !matchedInB[j + 1]) ||
                    (Character.isLowSurrogate(b[j]) && j > 0 && !matchedInB[j - 1])
                if (brokenInA || brokenInB) {
                    result[i] = -1
                    changed = true
                }
            }
        }
    }
}

/** Myers' O(ND) shortest edit script, as the pairs of indexes it keeps in common. */
internal object Myers {

    /** Matched (index in [a], index in [b]) pairs in ascending order, or null past [maxEdits] edits. */
    fun matches(a: IntArray, b: IntArray, maxEdits: Int): List<Pair<Int, Int>>? {
        val n = a.size
        val m = b.size
        // furthest[d][k + d]: the furthest x reached on diagonal k (x - y) with d edits.
        val furthest = ArrayList<IntArray>()
        for (d in 0..minOf(n + m, maxEdits)) {
            val previous = furthest.lastOrNull()
            val current = IntArray(2 * d + 1)
            for (k in -d..d step 2) {
                val down = k == -d || (k != d && previous!![k - 1 + d - 1] < previous[k + 1 + d - 1])
                var x = when {
                    d == 0 -> 0
                    down -> previous!![k + 1 + d - 1]
                    else -> previous!![k - 1 + d - 1] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) {
                    x++
                    y++
                }
                current[k + d] = x
                if (x >= n && y >= m) {
                    furthest += current
                    return backtrack(furthest, n, m)
                }
            }
            furthest += current
        }
        return null
    }

    private fun backtrack(furthest: List<IntArray>, n: Int, m: Int): List<Pair<Int, Int>> {
        val matches = ArrayList<Pair<Int, Int>>()
        var x = n
        var y = m
        for (d in furthest.lastIndex downTo 1) {
            val previous = furthest[d - 1]
            val k = x - y
            val down = k == -d || (k != d && previous[k - 1 + d - 1] < previous[k + 1 + d - 1])
            val previousK = if (down) k + 1 else k - 1
            val previousX = previous[previousK + d - 1]
            val previousY = previousX - previousK
            // The snake after this edit started where the edit left off.
            val startX = if (down) previousX else previousX + 1
            val startY = startX - k
            while (x > startX && y > startY) {
                x--
                y--
                matches += x to y
            }
            x = previousX
            y = previousY
        }
        while (x > 0 && y > 0) {
            x--
            y--
            matches += x to y
        }
        matches.reverse()
        return matches
    }
}
