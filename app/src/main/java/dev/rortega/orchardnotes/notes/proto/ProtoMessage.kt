package dev.rortega.orchardnotes.notes.proto

/**
 * One field occurrence as it appeared on the wire. [value] holds the varint or the
 * raw fixed32/fixed64 bits; [bytes] holds length-delimited payloads. Instances are
 * immutable (the byte array is never modified after construction).
 */
class ProtoField(val number: Int, val wireType: Int, val value: Long = 0, val bytes: ByteArray? = null)

/**
 * A schemaless protobuf message that keeps every field in its original wire order.
 *
 * Note bodies are rewritten by Apple's devices after we save them, so anything this
 * app doesn't understand (unknown fields, explicit zero values, field order) must
 * come back out exactly as it went in. Setters replace a field in place, or insert a
 * new one at its canonical (field-number-ordered) position, the way protobuf
 * encoders lay messages out.
 */
class ProtoMessage(fields: List<ProtoField> = emptyList()) {
    private val _fields = fields.toMutableList()
    val fields: List<ProtoField> get() = _fields

    fun encode(): ByteArray {
        val writer = ProtoWriter()
        _fields.forEach(writer::field)
        return writer.toByteArray()
    }

    fun copy(): ProtoMessage = ProtoMessage(_fields)

    fun has(number: Int): Boolean = _fields.any { it.number == number }

    fun first(number: Int): ProtoField? = _fields.firstOrNull { it.number == number }

    fun all(number: Int): List<ProtoField> = _fields.filter { it.number == number }

    fun varint(number: Int): Long? = first(number)?.takeIf { it.wireType == WireType.VARINT }?.value

    fun bytes(number: Int): ByteArray? = first(number)?.takeIf { it.wireType == WireType.LEN }?.bytes

    fun string(number: Int): String? = bytes(number)?.decodeToString()

    fun message(number: Int): ProtoMessage? = bytes(number)?.let(::parse)

    fun float(number: Int): Float? =
        first(number)?.takeIf { it.wireType == WireType.I32 }?.let { Float.fromBits(it.value.toInt()) }

    fun set(field: ProtoField) {
        val index = _fields.indexOfFirst { it.number == field.number }
        if (index >= 0) {
            _fields[index] = field
            // A singular field: drop any later duplicates so the new value wins.
            for (i in _fields.lastIndex downTo index + 1) {
                if (_fields[i].number == field.number) _fields.removeAt(i)
            }
        } else {
            val insertAt = _fields.indexOfFirst { it.number > field.number }.let { if (it < 0) _fields.size else it }
            _fields.add(insertAt, field)
        }
    }

    fun setVarint(number: Int, value: Long) = set(ProtoField(number, WireType.VARINT, value))

    fun setBytes(number: Int, bytes: ByteArray) = set(ProtoField(number, WireType.LEN, bytes = bytes))

    fun setString(number: Int, value: String) = setBytes(number, value.encodeToByteArray())

    fun setMessage(number: Int, message: ProtoMessage) = setBytes(number, message.encode())

    fun remove(number: Int) {
        _fields.removeAll { it.number == number }
    }

    /** True when both messages encode to identical bytes. */
    fun contentEquals(other: ProtoMessage): Boolean = encode().contentEquals(other.encode())

    companion object {
        fun parse(bytes: ByteArray): ProtoMessage {
            val reader = ProtoReader(bytes)
            val fields = mutableListOf<ProtoField>()
            while (!reader.isAtEnd) {
                val (number, wireType) = reader.readTag()
                fields += when (wireType) {
                    WireType.VARINT -> ProtoField(number, wireType, reader.readVarint())
                    WireType.I64 -> ProtoField(number, wireType, reader.readFixed64())
                    WireType.I32 -> ProtoField(number, wireType, reader.readFixed32())
                    WireType.LEN -> ProtoField(number, wireType, bytes = reader.readLengthDelimited())
                    else -> throw ProtoFormatException("Unsupported wire type $wireType for field $number")
                }
            }
            return ProtoMessage(fields)
        }
    }
}
