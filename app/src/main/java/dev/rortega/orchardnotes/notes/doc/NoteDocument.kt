package dev.rortega.orchardnotes.notes.doc

import dev.rortega.orchardnotes.notes.proto.ProtoField
import dev.rortega.orchardnotes.notes.proto.ProtoFormatException
import dev.rortega.orchardnotes.notes.proto.ProtoMessage
import dev.rortega.orchardnotes.notes.proto.ProtoReader
import dev.rortega.orchardnotes.notes.proto.ProtoWriter
import dev.rortega.orchardnotes.notes.proto.WireType

/*
 * The editable model of a note body (`TextDataEncrypted`, decompressed).
 *
 * Ported from icloud-md's noteDocument.ts (https://github.com/coddingtonbear/icloud-md,
 * MIT License, Copyright (c) 2026 Adam Coddington), whose format knowledge was derived
 * from captured www.icloud.com traffic:
 *
 *   versioned_document.Document { 1: serializationVersion, 2: Version { 1, 2: minimumSupportedVersion, 3: data } }
 *   data = topotext.String {
 *     2: string                    the visible text (title is its first line)
 *     3: repeated Substring        the CRDT history behind the text
 *     4: VectorTimestamp           per-replica clocks
 *     5: repeated AttributeRun     formatting over the visible text
 *   }
 *   Substring { 1: charID {replica, clock}, 2: length, 3: style timestamp {replica, clock},
 *               4: tombstone, 5: repeated child (forward DAG edges, indexes into the list) }
 *
 * Invariants (checked by [validate]): lengths and clocks count UTF-16 units; the visible
 * text is the concatenation of non-tombstoned substrings in list order; each replica's
 * text clock covers every character it inserted; replica indexes are 1-based into the
 * clock table (0 is the origin run and the end sentinel); attribute-run lengths sum to
 * the text length; child edges point strictly forward.
 *
 * Parsing is strict: anything not understood throws, and callers must also confirm the
 * byte-for-byte round trip ([roundTrips]) before editing. A note that fails either check
 * stays read-only rather than risk a corrupting write.
 */

data class CharId(val replica: Int, val clock: Long)

class TextRun(
    var coord: CharId,
    var length: Int,
    /** The run's style timestamp (Apple's `Substring.timestamp`). */
    var anchor: CharId,
    var tombstone: Boolean,
    /** Outgoing child edges: indexes into the run list, always pointing forward. */
    var children: MutableList<Int>,
) {
    val isSentinel: Boolean get() = coord.clock == SENTINEL_CLOCK

    fun copy() = TextRun(coord, length, anchor, tombstone, children.toMutableList())

    companion object {
        const val SENTINEL_CLOCK = 0xFFFFFFFFL
    }
}

/** One replica's row in the clock table: [counters] is (text clock, style clock). */
class ReplicaEntry(val id: ByteArray, val counters: MutableList<Long>)

class NoteDocument internal constructor(
    /** The versioned wrapper as parsed; only its `Version.data` is replaced on encode. */
    private val wrapper: ProtoMessage,
    private val version: ProtoMessage,
    var text: String,
    val runs: MutableList<TextRun>,
    val replicas: MutableList<ReplicaEntry>,
    var attributeRuns: MutableList<AttributeRun>,
    /** `topotext.String` fields outside the model, re-emitted after the known ones. */
    private val extraFields: List<ProtoField>,
) {

    fun encode(): ByteArray {
        val string = ProtoWriter()
        string.stringField(FIELD_STRING, text)
        runs.forEach { string.bytesField(FIELD_SUBSTRING, encodeRun(it)) }
        string.bytesField(FIELD_TIMESTAMP, encodeReplicas())
        attributeRuns.forEach { string.bytesField(FIELD_ATTRIBUTE_RUN, it.msg.encode()) }
        extraFields.forEach(string::field)

        val newVersion = version.copy().apply { setBytes(VERSION_DATA, string.toByteArray()) }
        return wrapper.copy().apply { setMessage(DOCUMENT_VERSION, newVersion) }.encode()
    }

    /** Throws if the CRDT structure is inconsistent; see the file header for the invariants. */
    fun validate() {
        val visible = runs.filter { !it.tombstone }.sumOf { it.length }
        if (visible != text.length) {
            throw NoteFormatException("Visible run lengths ($visible) do not match the note text length (${text.length})")
        }
        val attributed = attributeRuns.sumOf { it.length }
        if (attributed != text.length) {
            throw NoteFormatException("Attribute run lengths ($attributed) do not match the note text length (${text.length})")
        }
        for (run in runs) {
            if (run.isSentinel) continue
            if (run.coord.replica < 0 || run.coord.replica > replicas.size) {
                throw NoteFormatException("Run references replica ${run.coord.replica} outside the replica table")
            }
            if (run.coord.replica > 0) {
                val clock = replicas[run.coord.replica - 1].counters.getOrElse(0) { 0L }
                if (run.coord.clock + run.length > clock) {
                    throw NoteFormatException("Run clocks exceed replica ${run.coord.replica}'s counter")
                }
            }
        }
        validateChildEdges(runs)
    }

    private fun encodeReplicas(): ByteArray {
        val table = ProtoWriter()
        for (replica in replicas) {
            val clock = ProtoWriter()
            clock.bytesField(1, replica.id)
            for (counter in replica.counters) {
                clock.bytesField(2, ProtoWriter().apply { varintField(1, counter) }.toByteArray())
            }
            table.bytesField(1, clock.toByteArray())
        }
        return table.toByteArray()
    }

    companion object {
        private const val DOCUMENT_VERSION = 2
        private const val VERSION_DATA = 3
        private const val FIELD_STRING = 2
        private const val FIELD_SUBSTRING = 3
        private const val FIELD_TIMESTAMP = 4
        private const val FIELD_ATTRIBUTE_RUN = 5

        fun parse(raw: ByteArray): NoteDocument {
            val wrapper = ProtoMessage.parse(raw)
            val versions = wrapper.all(DOCUMENT_VERSION)
            if (versions.size != 1) {
                throw NoteFormatException("Versioned document has ${versions.size} versions; exactly one is understood")
            }
            val version = ProtoMessage.parse(versions.single().bytes ?: throw NoteFormatException("Malformed version"))
            val data = version.bytes(VERSION_DATA) ?: throw NoteFormatException("Versioned document carries no data")
            val string = ProtoMessage.parse(data)

            var text: String? = null
            val runs = mutableListOf<TextRun>()
            var replicas: MutableList<ReplicaEntry>? = null
            val attributeRuns = mutableListOf<AttributeRun>()
            val extras = mutableListOf<ProtoField>()
            for (field in string.fields) {
                when (field.number) {
                    FIELD_STRING -> text = field.lenBytes().decodeToString()
                    FIELD_SUBSTRING -> runs += parseRun(field.lenBytes())
                    FIELD_TIMESTAMP -> replicas = parseReplicas(field.lenBytes())
                    FIELD_ATTRIBUTE_RUN -> attributeRuns += AttributeRun(ProtoMessage.parse(field.lenBytes()))
                    else -> extras += field
                }
            }
            return NoteDocument(
                wrapper = wrapper,
                version = version,
                text = text ?: throw NoteFormatException("Note document has no text"),
                runs = runs,
                replicas = replicas ?: throw NoteFormatException("Note document is missing its replica clock table"),
                attributeRuns = attributeRuns,
                extraFields = extras,
            )
        }

        /** The round-trip gate: true only if re-encoding the parsed model reproduces [raw] exactly. */
        fun roundTrips(raw: ByteArray): Boolean =
            runCatching { parse(raw).encode().contentEquals(raw) }.getOrDefault(false)

        private fun ProtoField.lenBytes(): ByteArray =
            if (wireType == WireType.LEN) bytes!! else throw NoteFormatException("Field $number is not length-delimited")

        private fun parseCharId(bytes: ByteArray): CharId {
            var replica: Long? = null
            var clock: Long? = null
            for (field in ProtoMessage.parse(bytes).fields) {
                when {
                    field.number == 1 && field.wireType == WireType.VARINT -> replica = field.value
                    field.number == 2 && field.wireType == WireType.VARINT -> clock = field.value
                    else -> throw NoteFormatException("Unexpected field ${field.number} in CharID")
                }
            }
            return CharId(
                (replica ?: throw NoteFormatException("CharID without replica")).toInt(),
                clock ?: throw NoteFormatException("CharID without clock"),
            )
        }

        private fun parseRun(bytes: ByteArray): TextRun {
            var coord: CharId? = null
            var length: Long? = null
            var anchor: CharId? = null
            var tombstone = false
            val children = mutableListOf<Int>()
            for (field in ProtoMessage.parse(bytes).fields) {
                when (field.number) {
                    1 -> coord = parseCharId(field.lenBytes())
                    2 -> length = field.varintValue()
                    3 -> anchor = parseCharId(field.lenBytes())
                    4 -> {
                        // Kept strict so an unexpected value is refused instead of being normalized to 1.
                        if (field.varintValue() != 1L) throw NoteFormatException("Unexpected tombstone value ${field.value}")
                        tombstone = true
                    }
                    5 -> when (field.wireType) {
                        WireType.VARINT -> children += field.value.toInt()
                        // Packed encoding is accepted on read; we write unpacked, so the round-trip gate decides.
                        WireType.LEN -> {
                            val reader = ProtoReader(field.bytes!!)
                            while (!reader.isAtEnd) children += reader.readVarint().toInt()
                        }
                        else -> throw NoteFormatException("Unexpected wire type for Substring.child")
                    }
                    else -> throw NoteFormatException("Unexpected field ${field.number} in Substring")
                }
            }
            return TextRun(
                coord = coord ?: throw NoteFormatException("Substring without charID"),
                length = (length ?: throw NoteFormatException("Substring without length")).toInt(),
                anchor = anchor ?: throw NoteFormatException("Substring without timestamp"),
                tombstone = tombstone,
                children = children,
            )
        }

        private fun parseReplicas(bytes: ByteArray): MutableList<ReplicaEntry> {
            val replicas = mutableListOf<ReplicaEntry>()
            for (clockField in ProtoMessage.parse(bytes).fields) {
                if (clockField.number != 1) throw NoteFormatException("Unexpected field ${clockField.number} in VectorTimestamp")
                var id: ByteArray? = null
                val counters = mutableListOf<Long>()
                for (field in ProtoMessage.parse(clockField.lenBytes()).fields) {
                    when (field.number) {
                        1 -> id = field.lenBytes()
                        2 -> {
                            val counter = ProtoMessage.parse(field.lenBytes())
                            if (counter.has(2)) {
                                throw NoteFormatException("Replica clock carries a subclock this app doesn't understand")
                            }
                            if (counter.fields.any { it.number != 1 }) {
                                throw NoteFormatException("Unexpected field in ReplicaClock")
                            }
                            counters += counter.varint(1) ?: throw NoteFormatException("ReplicaClock without clock")
                        }
                        else -> throw NoteFormatException("Unexpected field ${field.number} in VectorTimestamp.Clock")
                    }
                }
                if (id == null || id.size != 16) throw NoteFormatException("Replica entry without a 16-byte UUID")
                replicas += ReplicaEntry(id, counters)
            }
            return replicas
        }

        private fun ProtoField.varintValue(): Long =
            if (wireType == WireType.VARINT) value else throw NoteFormatException("Field $number is not a varint")

        internal fun encodeRun(run: TextRun): ByteArray {
            val writer = ProtoWriter()
            writer.bytesField(1, encodeCharId(run.coord))
            writer.varintField(2, run.length.toLong())
            writer.bytesField(3, encodeCharId(run.anchor))
            if (run.tombstone) writer.varintField(4, 1)
            run.children.forEach { writer.varintField(5, it.toLong()) }
            return writer.toByteArray()
        }

        private fun encodeCharId(id: CharId): ByteArray = ProtoWriter().apply {
            varintField(1, id.replica.toLong())
            varintField(2, id.clock)
        }.toByteArray()

        /**
         * Child-edge sanity: every edge in range and strictly forward (Apple saves in
         * topological order, so forward edges can't form cycles), and every run except
         * the end sentinel linked into the graph.
         */
        internal fun validateChildEdges(runs: List<TextRun>) {
            runs.forEachIndexed { index, run ->
                if (run.isSentinel) return@forEachIndexed
                if (run.children.isEmpty()) throw NoteFormatException("Run $index has no child edge")
                for (child in run.children) {
                    if (child <= index || child >= runs.size) {
                        throw NoteFormatException("Run $index has a child edge to $child, outside the forward range")
                    }
                }
            }
        }

        /** A fresh `versioned_document` wrapper (serialization versions 0, minimum supported version 0). */
        internal fun empty(
            text: String,
            runs: MutableList<TextRun>,
            replicas: MutableList<ReplicaEntry>,
            attributeRuns: MutableList<AttributeRun>,
        ): NoteDocument {
            val version = ProtoMessage().apply {
                setVarint(1, 0)
                setVarint(2, 0)
                setBytes(VERSION_DATA, ByteArray(0))
            }
            val wrapper = ProtoMessage().apply {
                setVarint(1, 0)
                setMessage(DOCUMENT_VERSION, version)
            }
            return NoteDocument(wrapper, version, text, runs, replicas, attributeRuns, emptyList())
        }
    }
}

class NoteFormatException(message: String) : ProtoFormatException(message)
