package dev.rortega.orchardnotes.notes

import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.NoteFormat

/**
 * Three-way merge of a note at paragraph granularity (text and formatting together):
 * regions changed only locally take the local version, regions changed only remotely
 * take the remote version, and regions changed differently on both sides are a
 * conflict. Used when an edit made here meets a note that changed elsewhere since.
 */
object ParagraphMerge {

    sealed interface Result {
        data class Merged(val paragraphs: List<FormatParagraph>) : Result
        data object Conflict : Result
    }

    fun merge(base: List<FormatParagraph>, ours: List<FormatParagraph>, theirs: List<FormatParagraph>): Result {
        val b = base.map(::key)
        val o = ours.map(::key)
        val t = theirs.map(::key)
        val toOurs = lcsMatches(b, o)
        val toTheirs = lcsMatches(b, t)

        val merged = mutableListOf<FormatParagraph>()
        var bi = 0
        var oi = 0
        var ti = 0
        while (true) {
            // The next base paragraph both sides kept, in order.
            val stable = (bi until b.size).firstOrNull { i ->
                val om = toOurs[i]
                val tm = toTheirs[i]
                om != null && tm != null && om >= oi && tm >= ti
            }
            val bEnd = stable ?: b.size
            val oEnd = stable?.let { toOurs[it]!! } ?: o.size
            val tEnd = stable?.let { toTheirs[it]!! } ?: t.size
            val baseChunk = b.subList(bi, bEnd)
            val oursChunk = o.subList(oi, oEnd)
            val theirsChunk = t.subList(ti, tEnd)
            when {
                oursChunk == baseChunk -> merged += theirs.subList(ti, tEnd)
                theirsChunk == baseChunk || theirsChunk == oursChunk -> merged += ours.subList(oi, oEnd)
                else -> return Result.Conflict
            }
            if (stable == null) break
            merged += theirs[tEnd]
            bi = bEnd + 1
            oi = oEnd + 1
            ti = tEnd + 1
        }
        return Result.Merged(withOffsets(merged))
    }

    /** Recomputes `start` offsets for a paragraph list joined with newlines. */
    fun withOffsets(paragraphs: List<FormatParagraph>): List<FormatParagraph> {
        var offset = 0
        return paragraphs.map { paragraph -> paragraph.copy(start = offset).also { offset += paragraph.text.length + 1 } }
    }

    /** A paragraph's identity for merging: everything but its position. */
    private fun key(paragraph: FormatParagraph): FormatParagraph =
        paragraph.copy(start = 0, spans = NoteFormat.mergeSpans(paragraph.spans))

    /** For each index of [a], the matched index in [b] along one longest common subsequence. */
    private fun <T> lcsMatches(a: List<T>, b: List<T>): Array<Int?> {
        val n = a.size
        val m = b.size
        val lengths = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lengths[i][j] = if (a[i] == b[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
        }
        val matches = arrayOfNulls<Int>(n)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> matches[i++] = j++
                lengths[i + 1][j] >= lengths[i][j + 1] -> i++
                else -> j++
            }
        }
        return matches
    }
}
