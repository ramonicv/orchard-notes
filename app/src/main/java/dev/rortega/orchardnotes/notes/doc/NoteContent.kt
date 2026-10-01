package dev.rortega.orchardnotes.notes.doc

import dev.rortega.orchardnotes.notes.proto.ProtoMessage

/** The U+FFFC character Apple Notes uses as the placeholder for an inline attachment. */
const val OBJECT_REPLACEMENT_CHARACTER = '￼'

/**
 * The displayable content of a note body: its text and formatting runs. Decoding
 * is lenient (the CRDT history is ignored), so a note that can't be edited safely
 * can still be read.
 */
class NoteContent(val text: String, val attributeRuns: List<AttributeRun>) {

    fun format(): FormatResult = NoteFormat.decode(text, attributeRuns)

    /** Every inline attachment, in document order, with the text offset of its placeholder. */
    fun attachments(): List<PlacedAttachment> {
        val placed = mutableListOf<PlacedAttachment>()
        var offset = 0
        for (run in attributeRuns) {
            val info = run.attachmentInfo
            if (info?.identifier != null && offset < text.length && text[offset] == OBJECT_REPLACEMENT_CHARACTER) {
                placed += PlacedAttachment(offset, info.identifier, info.typeUti.orEmpty())
            }
            offset += run.length
        }
        return placed
    }

    companion object {
        /** Decodes a compressed `TextDataEncrypted` payload. */
        fun decode(compressed: ByteArray): NoteContent = decodeRaw(NoteCompression.decompress(compressed))

        fun decodeRaw(raw: ByteArray): NoteContent {
            val wrapper = ProtoMessage.parse(raw)
            val data = wrapper.all(2)
                .asSequence()
                .mapNotNull { it.bytes?.let(ProtoMessage::parse)?.bytes(3) }
                .firstOrNull()
                ?: throw NoteFormatException("Note body has no content")
            val string = ProtoMessage.parse(data)
            return NoteContent(
                text = string.string(2).orEmpty(),
                attributeRuns = string.all(5).mapNotNull { field -> field.bytes?.let { AttributeRun(ProtoMessage.parse(it)) } },
            )
        }
    }
}

data class PlacedAttachment(val offset: Int, val identifier: String, val typeUti: String)
