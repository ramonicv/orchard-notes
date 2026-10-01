package dev.rortega.orchardnotes.notes.doc

import dev.rortega.orchardnotes.notes.doc.TestDocs.REPLICA_A
import dev.rortega.orchardnotes.notes.doc.TestDocs.REPLICA_B
import dev.rortega.orchardnotes.notes.proto.ProtoMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapted from icloud-md's formatReconcile.test.ts (MIT). */
class FormatReconcileTest {
    private val todoUuid = ByteArray(16) { 0x77 }

    /** A consistent one-replica document whose attribute runs the test supplies. */
    private fun docWith(text: String, vararg runs: AttributeRun): NoteDocument = TestDocs.make(
        text,
        listOf(TestDocs.run(1, 0, text.length, 0, 2)),
        listOf(text.length.toLong()),
    ).apply {
        replicas[0].counters[1] = 3
        attributeRuns = runs.toMutableList()
    }

    private fun styled(length: Int, style: Int, configure: ParagraphStyle.() -> Unit = {}) = AttributeRun.plain(length).apply {
        paragraphStyle = ParagraphStyle().apply {
            this.style = style
            alignment = 4
            configure()
        }
    }

    /** Desired paragraphs: one per line, plain spans unless given. */
    private fun desired(vararg lines: Pair<String, FormatParagraph.() -> FormatParagraph>): List<FormatParagraph> {
        var offset = 0
        return lines.map { (text, transform) ->
            FormatParagraph(kind = ParagraphKind.Body, text = text, spans = listOf(InlineSpan(text.length, InlineStyle.Plain)), start = offset)
                .transform()
                .also { offset += text.length + 1 }
        }
    }

    private val plain: FormatParagraph.() -> FormatParagraph = { this }

    @Test
    fun matchingFormattingIsANoOp() {
        val doc = docWith("plain line", AttributeRun.plain(10))
        val runsBefore = doc.attributeRuns
        assertEquals(FormatReconcile.Result.Ok(false), FormatReconcile.reconcile(doc, desired("plain line" to plain), REPLICA_A))
        assertSame(runsBefore, doc.attributeRuns)
        assertEquals(listOf(10L, 3L), doc.replicas[0].counters)
    }

    @Test
    fun checklistToggleKeepsTheTodoUuidAndBumpsOnlyTheOpClock() {
        val doc = docWith("buy milk", styled(8, 103) { todo = Todo(todoUuid, false) })
        val result = FormatReconcile.reconcile(doc, desired("buy milk" to { copy(kind = ParagraphKind.Checklist, done = true) }), REPLICA_A)
        assertEquals(FormatReconcile.Result.Ok(true), result)
        assertEquals(1, doc.attributeRuns.size)
        val todo = doc.attributeRuns[0].paragraphStyle!!.todo!!
        assertTrue(todo.done)
        assertArrayEquals(todoUuid, todo.uuid)
        assertEquals(listOf(8L, 4L), doc.replicas[0].counters)
        assertEquals(CharId(1, 3), doc.runs.first { it.length > 0 }.anchor)
    }

    @Test
    fun stylingAParagraphKeepsOpaqueFields() {
        val run = AttributeRun.plain(17).apply {
            msg.setMessage(AttributeRun.COLOR, ProtoMessage().apply { setVarint(1, 7) })
            msg.setVarint(13, 42)
        }
        val doc = docWith("make me a heading", run)
        val result = FormatReconcile.reconcile(doc, desired("make me a heading" to { copy(kind = ParagraphKind.Heading) }), REPLICA_A)
        assertEquals(FormatReconcile.Result.Ok(true), result)
        val rewritten = doc.attributeRuns.single()
        assertEquals(1, rewritten.paragraphStyle!!.style)
        assertEquals(4, rewritten.paragraphStyle!!.alignment)
        assertEquals(7L, rewritten.msg.message(AttributeRun.COLOR)!!.varint(1))
        assertEquals(42L, rewritten.msg.varint(13))
    }

    @Test
    fun boldingAWordSplitsRunsAndRestampsOnlyThatParagraph() {
        val doc = docWith("first line\nbold me here", AttributeRun.plain(23))
        val result = FormatReconcile.reconcile(
            doc,
            desired(
                "first line" to plain,
                "bold me here" to { copy(spans = listOf(InlineSpan(4, InlineStyle(bold = true)), InlineSpan(8, InlineStyle.Plain))) },
            ),
            REPLICA_A,
        )
        assertEquals(FormatReconcile.Result.Ok(true), result)
        assertEquals(
            listOf(Triple(11, 0, null), Triple(4, 1, "SFUIText-Bold"), Triple(8, 0, null)),
            doc.attributeRuns.map { Triple(it.length, it.fontHints, it.font?.string(1)) },
        )
        assertTrue(doc.attributeRuns[1].msg.has(AttributeRun.FONT_HINTS))
        assertEquals(
            listOf(11 to CharId(1, 0), 12 to CharId(1, 3)),
            doc.runs.filter { it.length > 0 && !it.tombstone }.map { it.length to it.anchor },
        )
    }

    @Test
    fun aDashListEditedOnlyElsewhereKeepsItsStyle() {
        val doc = docWith("dash item\nplain", styled(10, 101), AttributeRun.plain(5))
        val result = FormatReconcile.reconcile(
            doc,
            desired("dash item" to { copy(kind = ParagraphKind.DashList) }, "plain" to { copy(kind = ParagraphKind.Heading) }),
            REPLICA_A,
        )
        assertEquals(FormatReconcile.Result.Ok(true), result)
        assertEquals(101, doc.attributeRuns[0].paragraphStyle!!.style)
        assertEquals(1, doc.attributeRuns[1].paragraphStyle!!.style)
    }

    @Test
    fun aNewChecklistParagraphGetsAFreshWebClientShapeStyle() {
        val doc = docWith("todo item", AttributeRun.plain(9))
        FormatReconcile.reconcile(doc, desired("todo item" to { copy(kind = ParagraphKind.Checklist) }), REPLICA_A)
        val style = doc.attributeRuns[0].paragraphStyle!!
        assertEquals(103, style.style)
        assertEquals(4, style.alignment)
        assertFalse(style.todo!!.done)
        assertEquals(16, style.todo!!.uuid.size)
        assertEquals(0, style.msg.bytes(ParagraphStyle.UUID)!!.size)
        // Field order matches the web client's encoding.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 8, 9), style.msg.fields.map { it.number })
    }

    @Test
    fun attachmentRunKeepsItsLinkageThroughAParagraphStyleChange() {
        val doc = docWith(
            "a\n￼",
            AttributeRun.plain(2),
            AttributeRun.plain(1).apply { attachmentInfo = AttachmentInfo("A-1", "public.jpeg") },
        )
        val result = FormatReconcile.reconcile(
            doc,
            desired("a" to { copy(blockQuoteLevel = 1) }, "￼" to { copy(blockQuoteLevel = 1) }),
            REPLICA_A,
        )
        assertEquals(FormatReconcile.Result.Ok(true), result)
        val attachment = doc.attributeRuns.single { it.attachmentInfo != null }
        assertEquals(1, attachment.length)
        assertEquals("A-1", attachment.attachmentInfo!!.identifier)
        assertEquals(1, attachment.paragraphStyle!!.blockQuoteLevel)
    }

    @Test
    fun aDifferentReplicaJoinsAtTheObservedClockMaxima() {
        val doc = docWith("check me", styled(8, 103) { todo = Todo(todoUuid, false) })
        FormatReconcile.reconcile(doc, desired("check me" to { copy(kind = ParagraphKind.Checklist, done = true) }), REPLICA_B)
        assertEquals(listOf(8L, 4L), doc.replicas[1].counters)
        assertEquals(CharId(2, 3), doc.runs.first { it.length > 0 }.anchor)
    }

    @Test
    fun createPathTextThenFormatting() {
        val paragraphs = desired(
            "Title" to { copy(kind = ParagraphKind.Title) },
            "" to { copy(spans = emptyList()) },
            "first todo" to { copy(kind = ParagraphKind.Checklist) },
            "second" to { copy(kind = ParagraphKind.Checklist, done = true) },
        )
        val doc = NoteEditing.buildInitialDocument(paragraphs.joinToString("\n") { it.text }, REPLICA_A)
        assertEquals(FormatReconcile.Result.Ok(true), FormatReconcile.reconcile(doc, paragraphs, REPLICA_A))
        val decoded = (NoteFormat.decode(doc.text, doc.attributeRuns) as FormatResult.Ok).paragraphs
        assertEquals(
            listOf(ParagraphKind.Title to false, ParagraphKind.Body to false, ParagraphKind.Checklist to false, ParagraphKind.Checklist to true),
            decoded.map { it.kind to it.done },
        )
        assertTrue(NoteFormat.formatsEqual(paragraphs, decoded))
        assertTrue(NoteDocument.roundTrips(doc.encode()))
    }

    @Test
    fun formattingAfterATextEditComposes() {
        val doc = docWith("old text", AttributeRun.plain(8))
        assertTrue(NoteEditing.applyTextEdit(doc, "new heading", REPLICA_A))
        FormatReconcile.reconcile(doc, desired("new heading" to { copy(kind = ParagraphKind.Heading) }), REPLICA_A)
        assertEquals(ParagraphKind.Heading, (NoteFormat.decode(doc.text, doc.attributeRuns) as FormatResult.Ok).paragraphs[0].kind)
    }

    @Test
    fun duplicateInheritedTodoUuidIsReminted() {
        val doc = docWith(
            "todo two\nstep2 verify line",
            styled(9, 103) { todo = Todo(todoUuid, false) },
            styled(17, 103) { todo = Todo(todoUuid, false) },
        )
        val checklist: FormatParagraph.() -> FormatParagraph = { copy(kind = ParagraphKind.Checklist) }
        assertEquals(
            FormatReconcile.Result.Ok(true),
            FormatReconcile.reconcile(doc, desired("todo two" to checklist, "step2 verify line" to checklist), REPLICA_A),
        )
        assertArrayEquals(todoUuid, doc.attributeRuns.first().paragraphStyle!!.todo!!.uuid)
        val second = doc.attributeRuns.last().paragraphStyle!!.todo!!.uuid
        assertEquals(16, second.size)
        assertFalse(second.contentEquals(todoUuid))
    }

    @Test
    fun numberedListsOmitTheDefaultStartAndWriteRealOnes() {
        val doc = docWith("num one\nnum two", AttributeRun.plain(15))
        val numbered: FormatParagraph.() -> FormatParagraph = { copy(kind = ParagraphKind.NumberedList) }
        FormatReconcile.reconcile(doc, desired("num one" to numbered, "num two" to numbered), REPLICA_A)
        doc.attributeRuns.forEach {
            assertEquals(102, it.paragraphStyle!!.style)
            assertFalse(it.paragraphStyle!!.hasStartingListItemNumber)
        }
        val started = docWith("five", AttributeRun.plain(4))
        FormatReconcile.reconcile(started, desired("five" to { copy(kind = ParagraphKind.NumberedList, startNumber = 5) }), REPLICA_A)
        assertEquals(5, started.attributeRuns[0].paragraphStyle!!.startingListItemNumber)
    }

    @Test
    fun anExplicitZeroStartIsRepaired() {
        val doc = docWith(
            "num one\nnum two",
            styled(8, 102) { startingListItemNumber = 0 },
            styled(7, 102) { startingListItemNumber = 0 },
        )
        val numbered: FormatParagraph.() -> FormatParagraph = { copy(kind = ParagraphKind.NumberedList) }
        assertEquals(
            FormatReconcile.Result.Ok(true),
            FormatReconcile.reconcile(doc, desired("num one" to numbered, "num two" to numbered), REPLICA_A),
        )
        doc.attributeRuns.forEach { assertFalse(it.paragraphStyle!!.hasStartingListItemNumber) }
    }

    @Test
    fun removingALinkDropsTheField() {
        val doc = docWith("docs", AttributeRun.plain(4).apply { link = "https://example.com/" })
        FormatReconcile.reconcile(doc, desired("docs" to plain), REPLICA_A)
        assertNull(doc.attributeRuns.single().msg.first(AttributeRun.LINK))
    }

    @Test
    fun misalignedParagraphsRefuse() {
        val doc = docWith("one line", AttributeRun.plain(8))
        assertTrue(FormatReconcile.reconcile(doc, desired("different text" to plain), REPLICA_A) is FormatReconcile.Result.Refused)
    }
}
