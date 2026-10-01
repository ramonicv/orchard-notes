package dev.rortega.orchardnotes.notes.doc

import java.nio.ByteBuffer
import java.util.UUID

/*
 * Write-side formatting: given a document whose text already matches the desired
 * paragraphs (NoteEditing.applyTextEdit first), rewrites the attribute runs of every
 * paragraph whose formatting differs and records the change as a CRDT formatting op.
 *
 * Adapted from icloud-md's formatReconcile.ts (MIT, Copyright (c) 2026 Adam Coddington).
 * Edits are clone-and-overlay: untouched paragraphs keep their runs byte-for-byte; a
 * changed paragraph's runs are split at paragraph/span boundaries, each piece cloned
 * from the run underneath, and only the fields that actually differ are overwritten.
 * Colors, fonts, highlights, attachment links and unknown fields ride along untouched.
 * Unlike icloud-md (which compares through a Markdown projection), comparisons here are
 * exact, because the editor expresses formatting directly.
 */
object FormatReconcile {

    sealed interface Result {
        data class Ok(val changed: Boolean) : Result
        data class Refused(val reason: String) : Result
    }

    fun reconcile(
        doc: NoteDocument,
        desired: List<FormatParagraph>,
        replicaId: ByteArray,
        newUuid: () -> ByteArray = ::randomUuidBytes,
    ): Result {
        val current = when (val decoded = NoteFormat.decode(doc.text, doc.attributeRuns)) {
            is FormatResult.Ok -> decoded.paragraphs
            is FormatResult.Unsupported -> return Result.Refused(decoded.reason)
        }
        if (current.size != desired.size || current.indices.any { current[it].text != desired[it].text }) {
            return Result.Refused("the note's paragraphs don't line up with the edited text")
        }
        val lastIndex = current.lastIndex

        // Checklist identity: a line inserted next to a checklist item inherits its run,
        // uuid included, and two items must never share one (Apple merges state per uuid).
        val owners = mutableSetOf<String>()
        val needsFreshTodoUuid = mutableSetOf<Int>()
        for (i in current.indices) {
            if (current[i].kind != ParagraphKind.Checklist || desired[i].kind != ParagraphKind.Checklist) continue
            val uuid = todoUuidOf(doc, current[i], i == lastIndex)?.takeIf { it.isNotEmpty() } ?: continue
            if (!owners.add(uuid.toHex())) needsFreshTodoUuid += i
        }
        // An explicit startingListItemNumber of 0 makes Apple number the list from 0; repair it.
        val needsStartRepair = current.indices.filter { i ->
            current[i].kind == ParagraphKind.NumberedList && desired[i].kind == ParagraphKind.NumberedList &&
                hasExplicitZeroStart(doc, current[i], i == lastIndex)
        }.toSet()

        val changed = desired.indices.filter { i ->
            i in needsFreshTodoUuid || i in needsStartRepair ||
                !NoteFormat.paragraphsEqual(current[i], desired[i], current.getOrNull(i - 1), desired.getOrNull(i - 1))
        }
        if (changed.isEmpty()) return Result.Ok(changed = false)

        val plans = changed.map { i ->
            val start = current[i].start
            val end = start + current[i].text.length + if (i == lastIndex) 0 else 1
            Plan(
                current = current[i],
                desired = desired[i],
                start = start,
                end = end,
                currentSpans = spanIntervals(current[i], end),
                desiredSpans = spanIntervals(desired[i], end),
                todoUuid = newUuid(),
                forceFreshTodoUuid = i in needsFreshTodoUuid,
            )
        }
        doc.attributeRuns = rewriteRuns(doc.attributeRuns, plans)
        NoteEditing.applyFormattingOp(doc, plans.filter { it.end > it.start }.map { it.start until it.end }, replicaId)
        return Result.Ok(changed = true)
    }

    private class SpanInterval(val start: Int, val end: Int, val style: InlineStyle)

    private class Plan(
        val current: FormatParagraph,
        val desired: FormatParagraph,
        /** The paragraph's range, including its trailing newline (which carries the paragraph style). */
        val start: Int,
        val end: Int,
        val currentSpans: List<SpanInterval>,
        val desiredSpans: List<SpanInterval>,
        val todoUuid: ByteArray,
        val forceFreshTodoUuid: Boolean,
    )

    /** Spans as absolute intervals; the trailing newline takes the last span's style. */
    private fun spanIntervals(paragraph: FormatParagraph, paragraphEnd: Int): List<SpanInterval> {
        val out = mutableListOf<SpanInterval>()
        var at = paragraph.start
        for (span in NoteFormat.mergeSpans(paragraph.spans)) {
            out += SpanInterval(at, at + span.length, span.style)
            at += span.length
        }
        val last = out.lastOrNull()
        if (last != null && last.end < paragraphEnd) {
            out[out.lastIndex] = SpanInterval(last.start, paragraphEnd, last.style)
        } else if (last == null && paragraphEnd > at) {
            out += SpanInterval(at, paragraphEnd, InlineStyle.Plain)
        }
        return out
    }

    private fun runsOverlapping(doc: NoteDocument, paragraph: FormatParagraph, isLast: Boolean): Sequence<AttributeRun> = sequence {
        val start = paragraph.start
        val end = start + paragraph.text.length + if (isLast) 0 else 1
        var offset = 0
        for (run in doc.attributeRuns) {
            val runStart = offset
            offset += run.length
            if (runStart < end && offset > start) yield(run)
        }
    }

    private fun todoUuidOf(doc: NoteDocument, paragraph: FormatParagraph, isLast: Boolean): ByteArray? =
        runsOverlapping(doc, paragraph, isLast).firstNotNullOfOrNull { it.paragraphStyle?.todo?.uuid }

    private fun hasExplicitZeroStart(doc: NoteDocument, paragraph: FormatParagraph, isLast: Boolean): Boolean =
        runsOverlapping(doc, paragraph, isLast).any { run ->
            run.paragraphStyle?.let { it.hasStartingListItemNumber && it.startingListItemNumber == 0 } == true
        }

    private fun rewriteRuns(runs: List<AttributeRun>, plans: List<Plan>): MutableList<AttributeRun> {
        val boundaries = sortedSetOf<Int>()
        for (plan in plans) {
            boundaries += plan.start
            boundaries += plan.end
            (plan.currentSpans + plan.desiredSpans).forEach {
                boundaries += it.start
                boundaries += it.end
            }
        }

        val out = mutableListOf<AttributeRun>()
        // Only pieces minted here may merge afterwards; untouched runs stay verbatim.
        val rewritten = mutableSetOf<AttributeRun>()
        var offset = 0
        for (run in runs) {
            val runStart = offset
            val runEnd = offset + run.length
            offset = runEnd
            if (plans.none { runStart < it.end && runEnd > it.start }) {
                out += run
                continue
            }
            val cuts = listOf(runStart) + boundaries.filter { it in (runStart + 1) until runEnd } + runEnd
            for (i in 0 until cuts.size - 1) {
                val pieceStart = cuts[i]
                val piece = run.copy().also { it.length = cuts[i + 1] - pieceStart }
                plans.firstOrNull { pieceStart >= it.start && cuts[i + 1] <= it.end }?.let { overlay(piece, it, pieceStart) }
                rewritten += piece
                out += piece
            }
        }
        return mergeRewritten(out, rewritten)
    }

    private fun overlay(piece: AttributeRun, plan: Plan, pieceStart: Int) {
        overlayParagraphStyle(piece, plan)
        val current = plan.currentSpans.firstOrNull { pieceStart >= it.start && pieceStart < it.end }?.style ?: InlineStyle.Plain
        val desired = plan.desiredSpans.firstOrNull { pieceStart >= it.start && pieceStart < it.end }?.style ?: InlineStyle.Plain
        if (current != desired) overlayInlineStyle(piece, current, desired)
    }

    /**
     * Same kind: keep the existing style (iOS uuids and other fields stay put) and set only
     * the fields that differ. Different kind: a fresh style in the web client's shape.
     */
    private fun overlayParagraphStyle(piece: AttributeRun, plan: Plan) {
        val current = plan.current
        val desired = plan.desired
        if (current.kind != desired.kind) {
            piece.paragraphStyle = freshParagraphStyle(desired, piece, plan)
            return
        }
        val needIndent = desired.kind.isList && current.indent != desired.indent
        val needQuote = current.blockQuoteLevel != desired.blockQuoteLevel
        val needDone = desired.kind == ParagraphKind.Checklist && current.done != desired.done
        val needStart = desired.kind == ParagraphKind.NumberedList &&
            NoteFormat.effectiveStart(current.startNumber) != NoteFormat.effectiveStart(desired.startNumber)
        val needIdentity = desired.kind == ParagraphKind.Checklist && plan.forceFreshTodoUuid
        val existing = piece.paragraphStyle
        val needStartRepair = existing != null && existing.hasStartingListItemNumber && existing.startingListItemNumber == 0
        if (!needIndent && !needQuote && !needDone && !needStart && !needIdentity && !needStartRepair) return

        // A Body paragraph with no explicit style gaining e.g. a quote level: write Body explicitly.
        val style = existing ?: ParagraphStyle().apply {
            this.style = ParagraphKind.Body.wireStyle
            alignment = ALIGNMENT_NATURAL
        }
        if (needIndent) style.indent = desired.indent
        if (needQuote) style.blockQuoteLevel = desired.blockQuoteLevel
        if (needStart) setStartNumber(style, desired.startNumber)
        if (style.hasStartingListItemNumber && style.startingListItemNumber == 0) style.clearStartingListItemNumber()
        if (needIdentity) {
            style.todo = Todo(plan.todoUuid, desired.done)
        } else if (needDone) {
            style.todo = Todo(style.todo?.uuid ?: plan.todoUuid, desired.done)
        }
        piece.paragraphStyle = style
    }

    /**
     * A fresh paragraph style shaped like the web client's: fields written explicitly
     * (zeros included), alignment Natural, empty uuid. `startingListItemNumber` is only
     * written for a numbered list that doesn't start at 1 (Apple omits it otherwise).
     */
    private fun freshParagraphStyle(desired: FormatParagraph, piece: AttributeRun, plan: Plan): ParagraphStyle {
        val msg = dev.rortega.orchardnotes.notes.proto.ProtoMessage()
        msg.setVarint(ParagraphStyle.STYLE, desired.kind.wireStyle.toLong())
        msg.setVarint(ParagraphStyle.ALIGNMENT, ALIGNMENT_NATURAL.toLong())
        msg.setVarint(ParagraphStyle.WRITING_DIRECTION, 0)
        msg.setVarint(ParagraphStyle.INDENT, if (desired.kind.isList) desired.indent.toLong() else 0)
        if (desired.kind == ParagraphKind.Checklist) {
            val uuid = (if (plan.forceFreshTodoUuid) null else piece.paragraphStyle?.todo?.uuid)?.takeIf { it.isNotEmpty() }
                ?: plan.todoUuid
            msg.setMessage(ParagraphStyle.TODO, Todo(uuid, desired.done).encode())
        }
        msg.setVarint(ParagraphStyle.PARAGRAPH_HINTS, 0)
        if (desired.kind == ParagraphKind.NumberedList && NoteFormat.effectiveStart(desired.startNumber) != 1) {
            msg.setVarint(ParagraphStyle.START_NUMBER, desired.startNumber.toLong())
        }
        msg.setVarint(ParagraphStyle.BLOCK_QUOTE, desired.blockQuoteLevel.toLong())
        msg.setBytes(ParagraphStyle.UUID, ByteArray(0))
        return ParagraphStyle(msg)
    }

    private fun setStartNumber(style: ParagraphStyle, startNumber: Int) {
        if (NoteFormat.effectiveStart(startNumber) == 1) style.clearStartingListItemNumber() else style.startingListItemNumber = startNumber
    }

    /** Overwrites only the inline fields that differ; bold/italic also get the web client's font name. */
    private fun overlayInlineStyle(piece: AttributeRun, current: InlineStyle, desired: InlineStyle) {
        if (current.bold != desired.bold || current.italic != desired.italic) {
            piece.fontHints = (if (desired.bold) 1 else 0) or (if (desired.italic) 2 else 0)
            piece.font = when {
                desired.bold && desired.italic -> AttributeRun.fontNamed("SFUIText-BoldItalic")
                desired.bold -> AttributeRun.fontNamed("SFUIText-Bold")
                desired.italic -> AttributeRun.fontNamed("SFUIText-LightItalic")
                else -> null
            }
        }
        if (current.strikethrough != desired.strikethrough) piece.strikethrough = if (desired.strikethrough) 1 else 0
        if (current.underline != desired.underline) piece.underline = if (desired.underline) 1 else 0
        if (current.link != desired.link) piece.link = desired.link
    }

    /** Merges adjacent rewritten runs that encode identically apart from their length. */
    private fun mergeRewritten(runs: List<AttributeRun>, rewritten: Set<AttributeRun>): MutableList<AttributeRun> {
        val out = mutableListOf<AttributeRun>()
        for (run in runs) {
            val previous = out.lastOrNull()
            if (previous != null && previous in rewritten && run in rewritten &&
                previous.attachmentInfo == null && run.attachmentInfo == null && previous.sameFormattingAs(run)
            ) {
                previous.length += run.length
            } else {
                out += run
            }
        }
        return out
    }

    private const val ALIGNMENT_NATURAL = 4

    fun randomUuidBytes(): ByteArray {
        val uuid = UUID.randomUUID()
        return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
