package dev.rortega.orchardnotes.notes.doc

/*
 * CRDT edit operations on a NoteDocument, ported from icloud-md's noteDocument.ts
 * (https://github.com/coddingtonbear/icloud-md, MIT License, Copyright (c) 2026 Adam
 * Coddington). The rules mirror Apple's own TTMergeableString behaviour as recovered
 * from the iCloud web client and verified against live captures:
 *
 * - Deleted text is tombstoned, never removed: other devices need it to merge.
 * - New text gets character ids from our replica's text clock; appending right after
 *   our own newest run extends it instead of adding a run.
 * - Runs are split and spliced into the child-edge DAG exactly like Apple's
 *   `splitTopoSubstring_atIndex` / `insertAttributedString_after_before`.
 * - Deletions and formatting changes are "formatting ops": they restamp the affected
 *   substrings' style timestamps from the replica's style clock (deletions with a +8
 *   bias), so they win last-writer-wins merges against older concurrent restyling.
 */
object NoteEditing {

    /**
     * Replaces the document's text with [newText], one minimal splice per changed
     * region. Returns false if the text is unchanged.
     */
    fun applyTextEdit(doc: NoteDocument, newText: String, replicaId: ByteArray): Boolean {
        val oldText = doc.text
        if (oldText == newText) return false
        doc.validate()

        val splices = TextDiff.computeSplices(oldText, newText)
        val replicaIndex = ensureReplica(doc, replicaId)
        // Apple captures its style clock once per save pass, so every stamp shares one floor.
        val styleClockFloor = styleClockSeed(doc, replicaIndex)
        var maxAssignedStyleClock = -1L
        var structuralChange = false
        var insertedNewRun = false

        // Splices carry old-text offsets; applying them in order shifts later ones by the net change so far.
        var delta = 0
        for (splice in splices) {
            val start = splice.start + delta
            if (splice.deleteLength > 0) {
                maxAssignedStyleClock = maxOf(
                    maxAssignedStyleClock,
                    tombstoneVisibleRange(doc, start, splice.deleteLength, replicaIndex, styleClockFloor),
                )
                structuralChange = true
            }
            if (splice.insertText.isNotEmpty()) {
                val newRun = insertVisibleText(doc, start, splice.insertText, replicaIndex)
                insertedNewRun = insertedNewRun || newRun
                structuralChange = structuralChange || newRun
            }
            adjustAttributeRuns(doc, start, splice.deleteLength, splice.insertText.length)
            delta += splice.insertText.length - splice.deleteLength
        }

        // The second counter is the style clock: a pure extension of our trailing run leaves it
        // alone, a new run raises it to at least 1, restamped tombstones push it past their stamps.
        if (structuralChange) {
            val counters = doc.replicas[replicaIndex - 1].counters
            counters.ensureSize(2)
            counters[1] = maxOf(maxAssignedStyleClock + 1, styleClockFloor, if (insertedNewRun) 1L else 0L)
        }

        doc.text = newText
        doc.validate()
        return true
    }

    /**
     * Applies one formatting operation over the given visible ranges: every substring
     * overlapping a range (split at its boundaries) is restamped, and the replica's style
     * clock moves past the highest stamp. The caller rewrites the attribute runs.
     */
    fun applyFormattingOp(doc: NoteDocument, ranges: List<IntRange>, replicaId: ByteArray) {
        val replicaIndex = ensureReplica(doc, replicaId)
        val counters = doc.replicas[replicaIndex - 1].counters
        val styleClockFloor = styleClockSeed(doc, replicaIndex)
        var maxAssigned = -1L
        for (range in ranges) {
            maxAssigned = maxOf(maxAssigned, restampVisibleRange(doc, range.first, range.last + 1, replicaIndex, styleClockFloor))
        }
        counters.ensureSize(2)
        counters[1] = maxOf(maxAssigned + 1, styleClockFloor)
    }

    /**
     * The document for a brand-new note's first save: the minimal skeleton Apple's
     * client starts from (a zero-length replica-0 origin run linked to the end
     * sentinel, empty clock table), with [text] inserted by the normal edit path.
     */
    fun buildInitialDocument(text: String, replicaId: ByteArray): NoteDocument {
        require(text.isNotEmpty()) { "A new note needs some text; refusing to create an empty document" }
        val doc = NoteDocument.empty(
            text = "",
            runs = mutableListOf(
                TextRun(CharId(0, 0), 0, CharId(0, 0), false, mutableListOf(1)),
                TextRun(CharId(0, TextRun.SENTINEL_CLOCK), 0, CharId(0, TextRun.SENTINEL_CLOCK), false, mutableListOf()),
            ),
            replicas = mutableListOf(),
            attributeRuns = mutableListOf(),
        )
        applyTextEdit(doc, text, replicaId)
        return doc
    }

    // --- child-edge surgery ---------------------------------------------------

    /** Adds [delta] to every child edge pointing at an index >= [threshold]. */
    private fun shiftChildEdges(runs: List<TextRun>, threshold: Int, delta: Int) {
        for (run in runs) {
            for (i in run.children.indices) {
                if (run.children[i] >= threshold) run.children[i] += delta
            }
        }
    }

    /**
     * Splits `runs[index]` at [offset] into head and tail: the tail inherits the head's
     * outgoing edges and the head's only child becomes the tail (Apple's
     * `splitTopoSubstring_atIndex`). The tail lands at `index + 1` and is returned.
     */
    internal fun splitRunAt(runs: MutableList<TextRun>, index: Int, offset: Int): TextRun {
        val run = runs[index]
        if (offset <= 0 || offset >= run.length) {
            throw NoteFormatException("Cannot split run $index at offset $offset; CRDT model out of sync")
        }
        shiftChildEdges(runs, index + 1, 1)
        val tail = TextRun(
            coord = CharId(run.coord.replica, run.coord.clock + offset),
            length = run.length - offset,
            anchor = run.anchor,
            tombstone = run.tombstone,
            children = run.children,
        )
        run.length = offset
        run.children = mutableListOf(index + 1)
        runs.add(index + 1, tail)
        return tail
    }

    /**
     * Inserts a new [run] at [index] and into the DAG between its array neighbours
     * (Apple's `insertAttributedString_after_before`): if the predecessor links to the
     * displaced run, the new run takes that edge's place; otherwise it takes over all of
     * the predecessor's children and becomes its only child.
     */
    internal fun insertRunAt(runs: MutableList<TextRun>, index: Int, run: TextRun) {
        shiftChildEdges(runs, index, 1)
        val predecessor = runs.getOrNull(index - 1)
        if (predecessor != null) {
            val successorEdge = predecessor.children.indexOf(index + 1)
            if (successorEdge != -1) {
                predecessor.children[successorEdge] = index
                run.children = mutableListOf(index + 1)
            } else {
                run.children = predecessor.children
                predecessor.children = mutableListOf(index)
            }
        } else {
            run.children = mutableListOf(index + 1)
        }
        runs.add(index, run)
    }

    // --- editing --------------------------------------------------------------

    /**
     * Tombstones the visible range [start, start + length), splitting runs at its
     * boundaries, and restamps each piece with (us, max(old style clock + 8, floor)).
     * Returns the highest stamp assigned, or -1 when nothing was covered.
     */
    private fun tombstoneVisibleRange(doc: NoteDocument, start: Int, length: Int, replicaIndex: Int, floor: Long): Long =
        overVisibleRange(doc, start, start + length) { target ->
            val assigned = maxOf(target.anchor.clock + 8, floor)
            target.tombstone = true
            target.anchor = CharId(replicaIndex, assigned)
            assigned
        }

    /** Restamps every visible run overlapping [start, end) with (us, max(old + 1, floor)). */
    private fun restampVisibleRange(doc: NoteDocument, start: Int, end: Int, replicaIndex: Int, floor: Long): Long =
        overVisibleRange(doc, start, end) { target ->
            val assigned = maxOf(target.anchor.clock + 1, floor)
            target.anchor = CharId(replicaIndex, assigned)
            assigned
        }

    /** Splits runs at the range boundaries and applies [stamp] to each visible piece inside. */
    private inline fun overVisibleRange(doc: NoteDocument, start: Int, end: Int, stamp: (TextRun) -> Long): Long {
        if (end > visibleLength(doc.runs)) {
            throw NoteFormatException("Range extends past the end of the visible text; CRDT model out of sync")
        }
        val runs = doc.runs
        var visible = 0
        var maxAssigned = -1L
        var i = 0
        while (i < runs.size && visible < end) {
            val run = runs[i]
            if (run.tombstone || run.length == 0 || run.isSentinel) {
                i++
                continue
            }
            val runStart = visible
            val runEnd = runStart + run.length
            if (runEnd <= start) {
                visible = runEnd
                i++
                continue
            }
            var target = run
            var targetIndex = i
            var targetStart = runStart
            if (start > runStart) {
                target = splitRunAt(runs, i, start - runStart)
                targetIndex = i + 1
                targetStart = start
            }
            if (end < targetStart + target.length) splitRunAt(runs, targetIndex, end - targetStart)
            maxAssigned = maxOf(maxAssigned, stamp(target))
            visible = targetStart + target.length
            i = targetIndex + 1
        }
        return maxAssigned
    }

    /**
     * Inserts [text] at visible position [start], extending our own newest run when it
     * ends right there, otherwise adding a new run. Returns whether a run was added.
     */
    private fun insertVisibleText(doc: NoteDocument, start: Int, text: String, replicaIndex: Int): Boolean {
        val replica = doc.replicas[replicaIndex - 1]
        val clock = replica.counters.getOrNull(0) ?: throw NoteFormatException("Replica entry has no text clock")
        if (start > visibleLength(doc.runs)) {
            throw NoteFormatException("Insertion point is past the end of the visible text; CRDT model out of sync")
        }

        var visible = 0
        var insertIndex = doc.runs.size
        for (i in doc.runs.indices) {
            val run = doc.runs[i]
            if (run.isSentinel) {
                insertIndex = i
                break
            }
            if (run.tombstone || run.length == 0) {
                insertIndex = i + 1
                continue
            }
            val runEnd = visible + run.length
            if (start < runEnd) {
                val offset = start - visible
                insertIndex = if (offset == 0) {
                    i
                } else {
                    splitRunAt(doc.runs, i, offset)
                    i + 1
                }
                break
            }
            visible = runEnd
            insertIndex = i + 1
        }

        val previous = doc.runs.getOrNull(insertIndex - 1)
        if (previous != null && !previous.tombstone && !previous.isSentinel &&
            previous.coord.replica == replicaIndex && previous.coord.clock + previous.length == clock
        ) {
            previous.length += text.length
            replica.counters[0] = clock + text.length
            return false
        }

        insertRunAt(
            doc.runs,
            insertIndex,
            // Freshly typed text is "never restyled": style timestamp (us, 0).
            TextRun(CharId(replicaIndex, clock), text.length, CharId(replicaIndex, 0), false, mutableListOf()),
        )
        replica.counters[0] = clock + text.length
        return true
    }

    private fun visibleLength(runs: List<TextRun>): Int = runs.filter { !it.tombstone }.sumOf { it.length }

    /**
     * Our 1-based index in the clock table, adding us if needed. A joining replica starts
     * both clocks at the maxima in the table (as Apple's web client does).
     */
    private fun ensureReplica(doc: NoteDocument, replicaId: ByteArray): Int {
        val existing = doc.replicas.indexOfFirst { it.id.contentEquals(replicaId) }
        if (existing != -1) return existing + 1
        val maxText = doc.replicas.maxOfOrNull { it.counters.getOrElse(0) { 0L } } ?: 0L
        val maxStyle = doc.replicas.maxOfOrNull { it.counters.getOrElse(1) { 0L } } ?: 0L
        doc.replicas += ReplicaEntry(replicaId.copyOf(), mutableListOf(maxText, maxStyle))
        return doc.replicas.size
    }

    /**
     * The style-clock floor for one pass: the highest style stamp in the document
     * (ordered by clock, then replica UUID), plus one if its holder would win the UUID
     * tie-break against us, and never below our own counter.
     */
    private fun styleClockSeed(doc: NoteDocument, replicaIndex: Int): Long {
        val ourId = doc.replicas[replicaIndex - 1].id
        var maxClock = -1L
        var maxHolder: ByteArray? = null
        for (run in doc.runs) {
            if (run.anchor.replica == 0) continue
            val holder = doc.replicas.getOrNull(run.anchor.replica - 1)?.id ?: ByteArray(16)
            if (run.anchor.clock > maxClock || (run.anchor.clock == maxClock && maxHolder != null && compareBytes(holder, maxHolder) > 0)) {
                maxClock = run.anchor.clock
                maxHolder = holder
            }
        }
        val seed = if (maxHolder != null) maxClock + if (compareBytes(maxHolder, ourId) >= 0) 1 else 0 else 0L
        return maxOf(seed, doc.replicas[replicaIndex - 1].counters.getOrElse(1) { 0L })
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    /**
     * Keeps attribute runs aligned with a splice: deleted text is cut out of the runs
     * covering it, and inserted text joins the run of the character before it (the
     * first run at position 0). Attachment runs always cover exactly their placeholder,
     * so text inserted next to one gets its own run instead.
     */
    private fun adjustAttributeRuns(doc: NoteDocument, start: Int, deleteLength: Int, insertLength: Int) {
        val end = start + deleteLength
        val out = mutableListOf<AttributeRun>()
        var visible = 0
        for (run in doc.attributeRuns) {
            val runStart = visible
            val runEnd = visible + run.length
            visible = runEnd
            val overlap = maxOf(0, minOf(end, runEnd) - maxOf(start, runStart))
            if (run.length - overlap > 0) {
                out += if (overlap == 0) run else run.copy().also { it.length = run.length - overlap }
            }
        }
        if (visible < end) throw NoteFormatException("Attribute runs are shorter than the deleted range")

        if (insertLength > 0) {
            var grown = false
            var runEnd = 0
            for (i in out.indices) {
                val run = out[i]
                runEnd += run.length
                if (start <= runEnd) {
                    if (run.attachmentInfo == null) {
                        out[i] = run.copy().also { it.length = run.length + insertLength }
                    } else {
                        val piece = run.copy().also {
                            it.attachmentInfo = null
                            it.length = insertLength
                        }
                        out.add(if (start == runEnd) i + 1 else i, piece)
                    }
                    grown = true
                    break
                }
            }
            if (!grown) {
                val last = out.lastOrNull()
                when {
                    last == null -> out += AttributeRun.plain(insertLength)
                    last.attachmentInfo == null -> out[out.lastIndex] = last.copy().also { it.length = last.length + insertLength }
                    else -> out += last.copy().also {
                        it.attachmentInfo = null
                        it.length = insertLength
                    }
                }
            }
        }
        doc.attributeRuns = out
    }

    private fun MutableList<Long>.ensureSize(size: Int) {
        while (this.size < size) add(0L)
    }
}
