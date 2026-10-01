package dev.rortega.orchardnotes.notes.doc

/** Replace [deleteLength] UTF-16 units at [start] (old-text offset) with [insertText]. */
data class Splice(val start: Int, val deleteLength: Int, val insertText: String)

/**
 * Text diffing for CRDT edits, ported from icloud-md (MIT). Edits must touch only the
 * characters that actually changed: text between two edits keeps its original runs
 * and authorship, or devices holding unmerged history can end up duplicating it.
 */
object TextDiff {

    /** Minimal single-splice diff over UTF-16 units that never splits a surrogate pair. */
    fun computeSplice(oldText: String, newText: String): Splice {
        var prefix = 0
        val maxPrefix = minOf(oldText.length, newText.length)
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
        while (prefix > 0 && Character.isHighSurrogate(oldText[prefix - 1])) prefix--

        var suffix = 0
        val maxSuffix = minOf(oldText.length, newText.length) - prefix
        while (suffix < maxSuffix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
        while (suffix > 0 && Character.isLowSurrogate(oldText[oldText.length - suffix])) suffix--

        return Splice(prefix, oldText.length - prefix - suffix, newText.substring(prefix, newText.length - suffix))
    }

    /**
     * Multi-hunk diff: a line-level LCS finds each changed region, then [computeSplice]
     * tightens every hunk to character precision. Splices are ascending and
     * non-overlapping, in old-text offsets.
     */
    fun computeSplices(oldText: String, newText: String): List<Splice> {
        if (oldText == newText) return emptyList()
        val oldLines = splitLinesInclusive(oldText)
        val newLines = splitLinesInclusive(newText)
        val oldOffsets = lineStartOffsets(oldLines)

        val splices = mutableListOf<Splice>()
        for (hunk in lineHunks(oldLines, newLines)) {
            val oldHunk = oldLines.subList(hunk.oldStart, hunk.oldEnd).joinToString("")
            val newHunk = newLines.subList(hunk.newStart, hunk.newEnd).joinToString("")
            val inner = computeSplice(oldHunk, newHunk)
            if (inner.deleteLength == 0 && inner.insertText.isEmpty()) continue
            splices += Splice(oldOffsets[hunk.oldStart] + inner.start, inner.deleteLength, inner.insertText)
        }
        return splices
    }

    private data class Hunk(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)

    /** Lines with their "\n" terminators attached, so line indexes map to offsets by accumulation. */
    internal fun splitLinesInclusive(text: String): List<String> {
        val lines = text.split('\n').map { "$it\n" }.toMutableList()
        val last = lines.last()
        if (last == "\n") lines.removeAt(lines.lastIndex) else lines[lines.lastIndex] = last.dropLast(1)
        return lines
    }

    /** `result[i]` is where line i starts; one extra trailing entry for appends past the last line. */
    private fun lineStartOffsets(lines: List<String>): IntArray {
        val offsets = IntArray(lines.size + 1)
        for (i in lines.indices) offsets[i + 1] = offsets[i] + lines[i].length
        return offsets
    }

    /** The gaps between longest-common-subsequence matches of two line lists. */
    private fun lineHunks(a: List<String>, b: List<String>): List<Hunk> {
        // Common prefix/suffix lines are matches; only the middle needs the quadratic LCS.
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++

        val aMid = a.subList(prefix, a.size - suffix)
        val bMid = b.subList(prefix, b.size - suffix)
        val matches = mutableListOf<Pair<Int, Int>>()
        if (aMid.isNotEmpty() && bMid.isNotEmpty() && aMid.size.toLong() * bMid.size <= MAX_LCS_CELLS) {
            val n = aMid.size
            val m = bMid.size
            // lengths[i][j] = LCS length of aMid[i..] and bMid[j..]
            val lengths = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    lengths[i][j] = if (aMid[i] == bMid[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    aMid[i] == bMid[j] -> {
                        matches += (prefix + i) to (prefix + j)
                        i++
                        j++
                    }
                    lengths[i + 1][j] >= lengths[i][j + 1] -> i++
                    else -> j++
                }
            }
        }
        // Too large for the LCS table: the whole middle becomes one (still correct) hunk.

        val hunks = mutableListOf<Hunk>()
        var lastA = prefix
        var lastB = prefix
        for ((ai, bi) in matches + ((a.size - suffix) to (b.size - suffix))) {
            if (ai > lastA || bi > lastB) hunks += Hunk(lastA, ai, lastB, bi)
            lastA = ai + 1
            lastB = bi + 1
        }
        return hunks
    }

    private const val MAX_LCS_CELLS = 4_000_000L
}
