package dev.rortega.orchardnotes.notes.doc

import dev.rortega.orchardnotes.notes.proto.ProtoField
import dev.rortega.orchardnotes.notes.proto.ProtoMessage
import dev.rortega.orchardnotes.notes.proto.WireType

/**
 * A `topotext.AttributeRun`: formatting over [length] UTF-16 units of the visible
 * text. Backed by the raw message, so fields this app doesn't model (fonts, colors,
 * timestamps, unknown fields) survive edits untouched.
 *
 * Field numbers follow the schema documented by icloud-md (proto/topotext.proto).
 */
class AttributeRun(val msg: ProtoMessage = ProtoMessage()) {

    var length: Int
        get() = msg.varint(LENGTH)?.toInt() ?: 0
        set(value) = msg.setVarint(LENGTH, value.toLong())

    var paragraphStyle: ParagraphStyle?
        get() = msg.message(PARAGRAPH_STYLE)?.let(::ParagraphStyle)
        set(value) = if (value == null) msg.remove(PARAGRAPH_STYLE) else msg.setMessage(PARAGRAPH_STYLE, value.msg)

    /** `Font { 1: name, 2: pointSize, 3: fontHints }`. */
    var font: ProtoMessage?
        get() = msg.message(FONT)
        set(value) = if (value == null) msg.remove(FONT) else msg.setMessage(FONT, value)

    /** Style modifier bits: 1 = bold, 2 = italic. */
    var fontHints: Int
        get() = msg.varint(FONT_HINTS)?.toInt() ?: 0
        set(value) = msg.setVarint(FONT_HINTS, value.toLong())

    var underline: Int
        get() = msg.varint(UNDERLINE)?.toInt() ?: 0
        set(value) = msg.setVarint(UNDERLINE, value.toLong())

    var strikethrough: Int
        get() = msg.varint(STRIKETHROUGH)?.toInt() ?: 0
        set(value) = msg.setVarint(STRIKETHROUGH, value.toLong())

    /** Positive = superscript, negative = subscript. */
    val superscript: Int get() = msg.varint(SUPERSCRIPT)?.toInt() ?: 0

    var link: String
        get() = msg.string(LINK) ?: ""
        set(value) = if (value.isEmpty()) msg.remove(LINK) else msg.setString(LINK, value)

    /** RGBA in 0..1, or null when the run uses the default text color. */
    val color: FloatArray?
        get() = msg.message(COLOR)?.let { c ->
            floatArrayOf(c.float(1) ?: 0f, c.float(2) ?: 0f, c.float(3) ?: 0f, c.float(4) ?: 1f)
        }

    var attachmentInfo: AttachmentInfo?
        get() = msg.message(ATTACHMENT_INFO)?.let { AttachmentInfo(it.string(1), it.string(2)) }
        set(value) = if (value == null) msg.remove(ATTACHMENT_INFO) else msg.setMessage(ATTACHMENT_INFO, value.encode())

    /** Highlight ("emphasis") style; 0 or absent means none. */
    val emphasis: Int get() = msg.varint(EMPHASIS)?.toInt() ?: 0

    fun copy(): AttributeRun = AttributeRun(msg.copy())

    /** True when every field except the length encodes identically. */
    fun sameFormattingAs(other: AttributeRun): Boolean {
        val a = msg.copy().apply { remove(LENGTH) }
        val b = other.msg.copy().apply { remove(LENGTH) }
        return a.contentEquals(b)
    }

    companion object {
        const val LENGTH = 1
        const val PARAGRAPH_STYLE = 2
        const val FONT = 3
        const val FONT_HINTS = 5
        const val UNDERLINE = 6
        const val STRIKETHROUGH = 7
        const val SUPERSCRIPT = 8
        const val LINK = 9
        const val COLOR = 10
        const val ATTACHMENT_INFO = 12
        const val EMPHASIS = 14

        fun plain(length: Int) = AttributeRun().apply { this.length = length }

        fun fontNamed(name: String) = ProtoMessage(listOf(ProtoField(1, WireType.LEN, bytes = name.encodeToByteArray())))
    }
}

/** An inline attachment reference: the U+FFFC it covers stands for this object. */
data class AttachmentInfo(val identifier: String?, val typeUti: String?) {
    fun encode(): ProtoMessage = ProtoMessage().apply {
        identifier?.let { setString(1, it) }
        typeUti?.let { setString(2, it) }
    }
}

/** A checklist item's identity and state. */
class Todo(val uuid: ByteArray, val done: Boolean) {
    fun encode(): ProtoMessage = ProtoMessage().apply {
        setBytes(1, uuid)
        setVarint(2, if (done) 1 else 0)
    }
}

/** `topotext.ParagraphStyle`, backed by the raw message. */
class ParagraphStyle(val msg: ProtoMessage = ProtoMessage()) {
    val hasStyle: Boolean get() = msg.has(STYLE)

    var style: Int
        get() = msg.varint(STYLE)?.toInt() ?: 0
        set(value) = msg.setVarint(STYLE, value.toLong())

    var alignment: Int
        get() = msg.varint(ALIGNMENT)?.toInt() ?: 0
        set(value) = msg.setVarint(ALIGNMENT, value.toLong())

    /** List nesting depth. */
    var indent: Int
        get() = msg.varint(INDENT)?.toInt() ?: 0
        set(value) = msg.setVarint(INDENT, value.toLong())

    var todo: Todo?
        get() = msg.message(TODO)?.let { Todo(it.bytes(1) ?: ByteArray(0), (it.varint(2) ?: 0L) != 0L) }
        set(value) = if (value == null) msg.remove(TODO) else msg.setMessage(TODO, value.encode())

    val hasStartingListItemNumber: Boolean get() = msg.has(START_NUMBER)

    var startingListItemNumber: Int
        get() = msg.varint(START_NUMBER)?.toInt() ?: 0
        set(value) = msg.setVarint(START_NUMBER, value.toLong())

    fun clearStartingListItemNumber() = msg.remove(START_NUMBER)

    var blockQuoteLevel: Int
        get() = msg.varint(BLOCK_QUOTE)?.toInt() ?: 0
        set(value) = msg.setVarint(BLOCK_QUOTE, value.toLong())

    companion object {
        const val STYLE = 1
        const val ALIGNMENT = 2
        const val WRITING_DIRECTION = 3
        const val INDENT = 4
        const val TODO = 5
        const val PARAGRAPH_HINTS = 6
        const val START_NUMBER = 7
        const val BLOCK_QUOTE = 8
        const val UUID = 9
    }
}
