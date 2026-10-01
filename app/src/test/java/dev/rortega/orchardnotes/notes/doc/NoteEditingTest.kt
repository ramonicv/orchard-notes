package dev.rortega.orchardnotes.notes.doc

import dev.rortega.orchardnotes.notes.Fixtures
import dev.rortega.orchardnotes.notes.doc.TestDocs.REPLICA_A
import dev.rortega.orchardnotes.notes.doc.TestDocs.REPLICA_B
import dev.rortega.orchardnotes.notes.doc.TestDocs.reencodeAndDecode
import dev.rortega.orchardnotes.notes.doc.TestDocs.run
import dev.rortega.orchardnotes.notes.doc.TestDocs.visibleText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from icloud-md's noteDocument.test.ts (MIT). */
class NoteEditingTest {
    private fun edit(doc: NoteDocument, text: String, replica: ByteArray = REPLICA_B) = NoteEditing.applyTextEdit(doc, text, replica)

    private fun children(doc: NoteDocument) = doc.runs.map { it.children.toList() }

    @Test
    fun appendingWithOurOwnReplicaExtendsOurTrailingRun() {
        val doc = TestDocs.simple("Hello")
        val runCount = doc.runs.size
        assertTrue(edit(doc, "Hello there", REPLICA_A))
        assertEquals("Hello there", doc.text)
        assertEquals(runCount, doc.runs.size)
        assertEquals(listOf(11L, 1L), doc.replicas[0].counters)
        assertEquals(1, doc.attributeRuns.size)
        assertEquals(11, doc.attributeRuns[0].length)
        assertEquals("Hello there", visibleText(doc))
        assertEquals("Hello there", reencodeAndDecode(doc))
    }

    @Test
    fun appendingAsANewReplicaAddsAReplicaEntryAndARun() {
        val doc = TestDocs.simple("Hello")
        assertTrue(edit(doc, "Hello!"))
        assertEquals(2, doc.replicas.size)
        assertEquals(listOf(6L, 1L), doc.replicas[1].counters)
        val inserted = doc.runs[doc.runs.size - 2]
        assertEquals(CharId(2, 5), inserted.coord)
        assertEquals(1, inserted.length)
        assertEquals("Hello!", reencodeAndDecode(doc))
        doc.validate()
    }

    @Test
    fun midTextInsertionSplitsTheContainingRun() {
        val doc = TestDocs.simple("Hello world")
        assertTrue(edit(doc, "Hello brave world"))
        assertEquals(
            listOf(Triple(1, 0L, 6), Triple(2, 11L, 6), Triple(1, 6L, 5)),
            doc.runs.filter { it.length > 0 }.map { Triple(it.coord.replica, it.coord.clock, it.length) },
        )
        assertEquals("Hello brave world", reencodeAndDecode(doc))
        doc.validate()
    }

    @Test
    fun deletionTombstonesWithApplesDeletionBias() {
        val doc = TestDocs.simple("Hello brave world")
        assertTrue(edit(doc, "Hello world"))
        val tombstones = doc.runs.filter { it.tombstone }
        assertEquals(1, tombstones.size)
        assertEquals(6, tombstones[0].length)
        assertEquals(6L, tombstones[0].coord.clock)
        assertEquals(CharId(2, 8), tombstones[0].anchor)
        assertEquals(listOf(17L, 9L), doc.replicas[1].counters)
        assertEquals("Hello world", reencodeAndDecode(doc))
        doc.validate()
    }

    @Test
    fun deletionSpanningRunsTombstonesEachPiece() {
        val doc = TestDocs.make(
            "aaabbbccc",
            listOf(run(1, 0, 3, 0, 2), run(2, 0, 3, 0, 3), run(1, 3, 3, 0, 4)),
            listOf(6, 3),
        )
        assertTrue(edit(doc, "aacc", REPLICA_A))
        assertEquals("aacc", visibleText(doc))
        val tombstoned = doc.runs.filter { it.tombstone }
        assertEquals(3, tombstoned.size)
        assertEquals(5, tombstoned.sumOf { it.length })
        doc.validate()
    }

    @Test
    fun editsNeverSplitASurrogatePair() {
        val doc = TestDocs.simple("ab😀cd")
        assertTrue(edit(doc, "ab😁cd"))
        assertEquals("ab😁cd", visibleText(doc))
        assertEquals("ab😁cd", reencodeAndDecode(doc))
        doc.validate()
    }

    @Test
    fun unchangedTextIsANoOp() {
        val doc = TestDocs.simple("same")
        val before = doc.encode()
        assertFalse(edit(doc, "same"))
        assertArrayEquals(before, doc.encode())
    }

    @Test
    fun consecutiveEditsFromTheSameReplicaKeepExtendingOneRun() {
        val doc = TestDocs.simple("v1")
        edit(doc, "v1 v2")
        val runs = doc.runs.size
        edit(doc, "v1 v2 v3")
        assertEquals(runs, doc.runs.size)
        assertEquals("v1 v2 v3", reencodeAndDecode(doc))
    }

    @Test
    fun linearDocumentsStayALinearChain() {
        val doc = TestDocs.simple("Hello world")
        edit(doc, "Hello brave world")
        val nonSentinel = doc.runs.filterNot { it.isSentinel }
        assertEquals(nonSentinel.indices.map { listOf(it + 1) }, nonSentinel.map { it.children })
    }

    private fun branched() = TestDocs.make(
        "aaabbbccc",
        listOf(run(1, 0, 3, 0, 2, 3), run(2, 0, 3, 0, 4), run(1, 3, 3, 0, 4)),
        listOf(6, 3),
    )

    @Test
    fun deletionInsideOneBranchPreservesTheOtherBranchesEdges() {
        val doc = branched()
        assertTrue(edit(doc, "aaabbccc", REPLICA_A))
        assertEquals("aaabbccc", reencodeAndDecode(doc))
        doc.validate()
        assertEquals(listOf(listOf(1), listOf(2, 4), listOf(3), listOf(5), listOf(5), listOf()), children(doc))
        assertEquals(listOf(false, false, false, true, false, false), doc.runs.map { it.tombstone })
        assertEquals(CharId(1, 8), doc.runs[3].anchor)
    }

    @Test
    fun insertBetweenUnlinkedBranchesTakesOverThePredecessorsChildren() {
        val doc = branched()
        assertTrue(edit(doc, "aaabbbXXccc", REPLICA_A))
        assertEquals("aaabbbXXccc", reencodeAndDecode(doc))
        doc.validate()
        assertEquals(listOf(listOf(1), listOf(2, 4), listOf(3), listOf(5), listOf(5), listOf()), children(doc))
        assertEquals(CharId(1, 6), doc.runs[3].coord)
        assertEquals(2, doc.runs[3].length)
    }

    @Test
    fun formattingOpSplittingABranchNodeKeepsBothEdgesOnTheTail() {
        val doc = branched()
        NoteEditing.applyFormattingOp(doc, listOf(1 until 2), REPLICA_A)
        doc.validate()
        assertEquals(
            listOf(listOf(1), listOf(2), listOf(3), listOf(4, 5), listOf(6), listOf(6), listOf()),
            children(doc),
        )
        assertEquals(CharId(1, 1), doc.runs[2].anchor)
        assertEquals("aaabbbccc", reencodeAndDecode(doc))
    }

    @Test
    fun structuralEditsAdvanceTheStyleClock() {
        val doc = TestDocs.simple("Hello brave world")
        edit(doc, "Hello world", REPLICA_A)
        assertEquals(9L, doc.replicas[0].counters[1])
        assertEquals(emptyList<Int>(), doc.runs.last().children)
    }

    @Test
    fun computeSpliceFindsMinimalEdits() {
        assertEquals(Splice(2, 0, "X"), TextDiff.computeSplice("abc", "abXc"))
        assertEquals(Splice(1, 1, ""), TextDiff.computeSplice("abc", "ac"))
        assertEquals(Splice(1, 1, "X"), TextDiff.computeSplice("abc", "aXc"))
        assertEquals(Splice(3, 0, " def"), TextDiff.computeSplice("abc", "abc def"))
        assertEquals(Splice(0, 0, "new"), TextDiff.computeSplice("", "new"))
    }

    @Test
    fun computeSplicesKeepsSeparatedEditsSeparate() {
        assertEquals(emptyList<Splice>(), TextDiff.computeSplices("same", "same"))
        assertEquals(listOf(Splice(4, 0, "XX ")), TextDiff.computeSplices("abc def", "abc XX def"))
        assertEquals(
            listOf(Splice(3, 0, " EDIT"), Splice(13, 0, ", EDITED")),
            TextDiff.computeSplices("one\ntwo\nthree\n", "one EDIT\ntwo\nthree, EDITED\n"),
        )
    }

    @Test
    fun computeSplicesFusionRegression() {
        val remote = "p2 bravo dev-E1\n\np3 charlie\ntyped-on-device tail"
        val local = "p2 bravo EDIT-E1 dev-E1\n\np3 charlie\ntyped-on-device tail\n"
        assertEquals(listOf(Splice(9, 0, "EDIT-E1 "), Splice(remote.length, 0, "\n")), TextDiff.computeSplices(remote, local))
    }

    @Test
    fun multiHunkEditNeverReauthorsAnotherReplicasText() {
        val doc = TestDocs.make("alpha\n\nmid\nbravo-device", listOf(run(1, 0, 11, 0, 2), run(2, 0, 12, 0, 3)), listOf(11, 12))
        assertTrue(edit(doc, "alpha EDIT\n\nmid\nbravo-device\n", REPLICA_A))
        val foreign = doc.runs.filter { it.coord.replica == 2 }
        assertEquals(1, foreign.size)
        assertEquals(CharId(2, 0), foreign[0].coord)
        assertEquals(12, foreign[0].length)
        assertFalse(foreign[0].tombstone)
        assertFalse(doc.runs.any { it.tombstone })
        assertEquals(6, doc.runs.filter { it.coord.replica == 1 && it.coord.clock >= 11 }.sumOf { it.length })
        assertEquals("alpha EDIT\n\nmid\nbravo-device\n", reencodeAndDecode(doc))
    }

    @Test
    fun multiHunkDeletionsShareOneStamp() {
        val doc = TestDocs.simple("aa bb\nmid\ncc dd\n")
        assertTrue(edit(doc, "aa\nmid\ncc\n", REPLICA_A))
        val tombstones = doc.runs.filter { it.tombstone }
        assertEquals(2, tombstones.size)
        tombstones.forEach { assertEquals(CharId(1, 8), it.anchor) }
        assertEquals(9L, doc.replicas[0].counters[1])
    }

    @Test
    fun deletingRestyledTextStampsPastTheOtherReplicasClock() {
        val doc = TestDocs.make("Hellobrave ", listOf(run(1, 0, 5, 0, 2), TestDocs.run(2, 0, 6, 80, 3)), listOf(5, 6))
        doc.replicas[0].counters[1] = 5
        doc.replicas[1].counters[1] = 81
        assertTrue(edit(doc, "Hello", REPLICA_A))
        assertEquals(CharId(1, 88), doc.runs.single { it.tombstone }.anchor)
        assertEquals(89L, doc.replicas[0].counters[1])
        doc.validate()
    }

    @Test
    fun formattingOpRestampsPastTheOtherReplicasClock() {
        val doc = TestDocs.make("Hellobrave ", listOf(run(1, 0, 5, 0, 2), TestDocs.run(2, 0, 6, 80, 3)), listOf(5, 6))
        doc.replicas[0].counters[1] = 5
        doc.replicas[1].counters[1] = 81
        NoteEditing.applyFormattingOp(doc, listOf(5 until 11), REPLICA_A)
        assertEquals(CharId(1, 81), doc.runs.first { it.coord.replica == 2 }.anchor)
        assertEquals(82L, doc.replicas[0].counters[1])
    }

    @Test
    fun initialDocumentMatchesTheCapturedFirstSaveShape() {
        val doc = NoteEditing.buildInitialDocument("Grocery list\nEggs\nMilk\n", ByteArray(16) { 7 })
        doc.validate()
        assertEquals(listOf(doc.text.length.toLong(), 1L), doc.replicas[0].counters)
        assertEquals(3, doc.runs.size)
        assertEquals(1, doc.runs[1].coord.replica)
        assertTrue(doc.runs[2].isSentinel)
        assertTrue(NoteDocument.roundTrips(doc.encode()))
        assertEquals("Grocery list\nEggs\nMilk\n", NoteDocument.parse(doc.encode()).text)
    }

    @Test
    fun initialDocumentSharesTheWebClientsFirstSaveSkeleton() {
        // The captured first save also carries live-typing history (a tombstoned keystroke),
        // so compare the skeleton: origin run, end sentinel, and a single replica.
        val captured = NoteDocument.parse(NoteCompression.decompress(Fixtures.compressed(Fixtures.FIRST_SAVE)))
        val rebuilt = NoteEditing.buildInitialDocument(captured.text, captured.replicas[0].id)
        fun shape(run: TextRun) = listOf(run.coord, run.length, run.anchor, run.tombstone, run.children.size)
        assertEquals(shape(captured.runs.first()), shape(rebuilt.runs.first()))
        assertEquals(shape(captured.runs.last()), shape(rebuilt.runs.last()))
        assertEquals(1, rebuilt.replicas.size)
        assertEquals(captured.replicas[0].counters[1], rebuilt.replicas[0].counters[1])
        assertEquals(captured.text, NoteContent.decode(NoteCompression.compress(rebuilt.encode())).text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun initialDocumentRefusesEmptyText() {
        NoteEditing.buildInitialDocument("", ByteArray(16))
    }

    @Test
    fun editingTheRealMultiReplicaNoteKeepsItConsistent() {
        val doc = NoteDocument.parse(NoteCompression.decompress(Fixtures.compressed(Fixtures.FORMATTED_MULTI_EDIT)))
        val original = doc.text
        val mid = original.indexOf('\n') + 1
        val edited = original.substring(0, mid) + "Added from Android\n" + original.substring(mid).replaceFirst("e", "E")
        assertTrue(NoteEditing.applyTextEdit(doc, edited, ByteArray(16) { 0x42 }))
        doc.validate()
        val reparsed = NoteDocument.parse(doc.encode())
        assertEquals(edited, reparsed.text)
        assertTrue(NoteDocument.roundTrips(doc.encode()))
        // Embeds kept their placeholders.
        assertEquals(original.count { it == OBJECT_REPLACEMENT_CHARACTER }, edited.count { it == OBJECT_REPLACEMENT_CHARACTER })
        assertEquals(
            NoteContent(original, NoteDocument.parse(NoteCompression.decompress(Fixtures.compressed(Fixtures.FORMATTED_MULTI_EDIT))).attributeRuns).attachments().map { it.identifier },
            NoteContent(reparsed.text, reparsed.attributeRuns).attachments().map { it.identifier },
        )
    }

    private fun documentWithEmbed(): NoteDocument = TestDocs.simple("a￼b").apply {
        attributeRuns = mutableListOf(
            AttributeRun.plain(1),
            AttributeRun.plain(1).apply {
                paragraphStyle = ParagraphStyle().apply { style = 3 }
                attachmentInfo = AttachmentInfo("A-1", "public.jpeg")
            },
            AttributeRun.plain(1),
        )
    }

    @Test
    fun insertingAfterAnEmbedNeverGrowsItsRun() {
        val doc = documentWithEmbed()
        assertTrue(edit(doc, "a￼Xb", REPLICA_A))
        doc.validate()
        assertEquals(4, doc.attributeRuns.size)
        assertEquals(1, doc.attributeRuns[1].length)
        assertEquals("A-1", doc.attributeRuns[1].attachmentInfo?.identifier)
        assertEquals(1, doc.attributeRuns[2].length)
        assertNull(doc.attributeRuns[2].attachmentInfo)
        assertEquals(3, doc.attributeRuns[2].paragraphStyle?.style)
    }

    @Test
    fun insertingBeforeALeadingEmbedKeepsItsRun() {
        val doc = TestDocs.simple("￼b").apply {
            attributeRuns = mutableListOf(
                AttributeRun.plain(1).apply { attachmentInfo = AttachmentInfo("A-2", "com.apple.notes.gallery") },
                AttributeRun.plain(1),
            )
        }
        assertTrue(edit(doc, "X￼b", REPLICA_A))
        assertEquals(3, doc.attributeRuns.size)
        assertNull(doc.attributeRuns[0].attachmentInfo)
        assertEquals("A-2", doc.attributeRuns[1].attachmentInfo?.identifier)
    }

    @Test
    fun appendingAfterATrailingEmbedGrowsAFreshRun() {
        val doc = TestDocs.simple("a￼").apply {
            attributeRuns = mutableListOf(
                AttributeRun.plain(1),
                AttributeRun.plain(1).apply { attachmentInfo = AttachmentInfo("A-3", "com.apple.paper") },
            )
        }
        assertTrue(edit(doc, "a￼ tail", REPLICA_A))
        assertEquals(1, doc.attributeRuns[1].length)
        assertEquals(5, doc.attributeRuns[2].length)
        assertNull(doc.attributeRuns[2].attachmentInfo)
    }
}
