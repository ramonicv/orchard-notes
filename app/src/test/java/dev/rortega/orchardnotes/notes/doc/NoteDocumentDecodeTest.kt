package dev.rortega.orchardnotes.notes.doc

import dev.rortega.orchardnotes.notes.Fixtures
import dev.rortega.orchardnotes.notes.proto.ProtoMessage
import dev.rortega.orchardnotes.notes.proto.ProtoWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteDocumentDecodeTest {

    @Test
    fun everyRealFixtureRoundTripsByteForByte() {
        for (name in Fixtures.ALL) {
            val raw = NoteCompression.decompress(Fixtures.compressed(name))
            assertTrue("$name should round-trip", NoteDocument.roundTrips(raw))
            NoteDocument.parse(raw).validate()
        }
    }

    @Test
    fun strictAndLenientDecodersAgreeOnText() {
        for (name in Fixtures.ALL) {
            val compressed = Fixtures.compressed(name)
            val strict = NoteDocument.parse(NoteCompression.decompress(compressed))
            val lenient = NoteContent.decode(compressed)
            assertEquals(strict.text, lenient.text)
            assertEquals(strict.attributeRuns.size, lenient.attributeRuns.size)
        }
    }

    @Test
    fun multiEditFixtureHasTheDocumentedShape() {
        val doc = NoteDocument.parse(NoteCompression.decompress(Fixtures.compressed(Fixtures.FORMATTED_MULTI_EDIT)))
        assertEquals(58, doc.attributeRuns.size)
        assertEquals(75, doc.runs.size)
        assertEquals(6, doc.replicas.size)
        assertEquals(21, doc.runs.count { it.tombstone })
        // A real branch node: concurrent inserts after the same run.
        assertTrue(doc.runs.any { it.children.size == 2 })
        assertTrue(doc.runs.first().coord == CharId(0, 0))
        assertTrue(doc.runs.last().isSentinel)
    }

    @Test
    fun firstSaveFixtureMatchesTheWebClientsCreateShape() {
        val doc = NoteDocument.parse(NoteCompression.decompress(Fixtures.compressed(Fixtures.FIRST_SAVE)))
        assertEquals("Test Note (2026\n", doc.text)
        assertEquals(1, doc.replicas.size)
        assertEquals(listOf(17L, 1L), doc.replicas[0].counters)
    }

    @Test
    fun unicodeFixtureKeepsUtf16Lengths() {
        val content = NoteContent.decode(Fixtures.compressed(Fixtures.UNICODE))
        assertEquals(content.text.length, content.attributeRuns.sumOf { it.length })
        assertTrue(content.text.any { Character.isSurrogate(it) })
    }

    @Test
    fun aDocumentWithoutReplicaClockTableIsRefused() {
        val string = ProtoWriter().apply { stringField(2, "hi") }.toByteArray()
        val version = ProtoWriter().apply { varintField(2, 0); bytesField(3, string) }.toByteArray()
        val raw = ProtoWriter().apply { bytesField(2, version) }.toByteArray()
        assertTrue(runCatching { NoteDocument.parse(raw) }.exceptionOrNull()!!.message!!.contains("replica clock table"))
        assertFalse(NoteDocument.roundTrips(raw))
        // ...but it is still readable.
        assertEquals("hi", NoteContent.decodeRaw(raw).text)
    }

    @Test
    fun anUnknownStringFieldOnlyFailsTheGateIfItsPositionBreaksTheRoundTrip() {
        val doc = TestDocs.simple("hi")
        val encoded = doc.encode()
        // Append an unknown field 6 to topotext.String: it re-encodes at the end, so it round-trips.
        val wrapper = ProtoMessage.parse(encoded)
        val version = wrapper.message(2)!!
        val string = ProtoMessage.parse(version.bytes(3)!!)
        string.setString(6, "future")
        version.setMessage(3, string)
        wrapper.setMessage(2, version)
        val withUnknown = wrapper.encode()
        assertTrue(NoteDocument.roundTrips(withUnknown))
    }

    @Test
    fun invariantValidationRejectsInconsistentDocuments() {
        val wrongText = TestDocs.simple("hello").apply { text = "hello!" }
        assertTrue(runCatching { wrongText.validate() }.exceptionOrNull()!!.message!!.contains("do not match"))

        val clockPastCounter = TestDocs.simple("hello").apply { replicas[0].counters[0] = 3 }
        assertTrue(runCatching { clockPastCounter.validate() }.exceptionOrNull()!!.message!!.contains("exceed"))

        val backward = TestDocs.simple("hello").apply { runs[1].children = mutableListOf(0) }
        assertTrue(runCatching { backward.validate() }.exceptionOrNull()!!.message!!.contains("child edge to 0"))

        val dangling = TestDocs.simple("hello").apply { runs[1].children = mutableListOf() }
        assertTrue(runCatching { dangling.validate() }.exceptionOrNull()!!.message!!.contains("no child edge"))
    }

    @Test
    fun attachmentsAreFoundAtTheirPlaceholders() {
        val content = NoteContent(
            "a￼b",
            listOf(
                AttributeRun.plain(1),
                AttributeRun.plain(1).apply { attachmentInfo = AttachmentInfo("ATT-1", "public.jpeg") },
                AttributeRun.plain(1),
            ),
        )
        assertEquals(listOf(PlacedAttachment(1, "ATT-1", "public.jpeg")), content.attachments())
    }
}

/** Synthetic documents in the shape observed in captured web-client saves. */
object TestDocs {
    val REPLICA_A = ByteArray(16) { 0xaa.toByte() }
    val REPLICA_B = ByteArray(16) { 0xbb.toByte() }

    fun sentinel() = TextRun(CharId(0, TextRun.SENTINEL_CLOCK), 0, CharId(0, TextRun.SENTINEL_CLOCK), false, mutableListOf())

    fun make(text: String, contentRuns: List<TextRun>, replicaClocks: List<Long>): NoteDocument {
        val runs = mutableListOf(TextRun(CharId(0, 0), 0, CharId(0, 0), false, mutableListOf(1)))
        runs += contentRuns
        runs += sentinel()
        val replicas = mutableListOf(ReplicaEntry(REPLICA_A, mutableListOf(replicaClocks[0], 1L)))
        replicaClocks.drop(1).forEach { replicas += ReplicaEntry(REPLICA_B, mutableListOf(it, 1L)) }
        return NoteDocument.empty(text, runs, replicas, mutableListOf(AttributeRun.plain(text.length)))
    }

    fun simple(text: String): NoteDocument = make(
        text,
        listOf(TextRun(CharId(1, 0), text.length, CharId(1, 0), false, mutableListOf(2))),
        listOf(text.length.toLong()),
    )

    fun run(replica: Int, clock: Long, length: Int, anchorClock: Long = 0, vararg children: Int) =
        TextRun(CharId(replica, clock), length, CharId(replica, anchorClock), false, children.toMutableList())

    /** Reconstructs the visible text from the runs alone. */
    fun visibleText(doc: NoteDocument): String {
        var position = 0
        val out = StringBuilder()
        for (run in doc.runs) {
            if (run.tombstone || run.isSentinel) continue
            out.append(doc.text, position, position + run.length)
            position += run.length
        }
        return out.toString()
    }

    fun reencodeAndDecode(doc: NoteDocument): String =
        NoteContent.decode(NoteCompression.compress(doc.encode())).text
}
