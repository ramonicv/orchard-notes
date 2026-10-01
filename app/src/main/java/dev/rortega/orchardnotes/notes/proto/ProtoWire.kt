package dev.rortega.orchardnotes.notes.proto

import java.io.ByteArrayOutputStream

open class ProtoFormatException(message: String) : Exception(message)

object WireType {
    const val VARINT = 0
    const val I64 = 1
    const val LEN = 2
    const val I32 = 5
}

/** Minimal protobuf wire-format reader. Groups (wire types 3/4) are not supported. */
class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    val isAtEnd: Boolean get() = pos >= end

    fun readTag(): Pair<Int, Int> {
        val tag = readVarint()
        val number = (tag ushr 3).toInt()
        val wireType = (tag and 0x7).toInt()
        if (number <= 0) throw ProtoFormatException("Invalid field number $number")
        return number to wireType
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw ProtoFormatException("Truncated varint")
            val b = buf[pos++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw ProtoFormatException("Varint too long")
        }
    }

    fun readFixed32(): Long {
        if (end - pos < 4) throw ProtoFormatException("Truncated fixed32")
        var value = 0L
        for (i in 0 until 4) value = value or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
        pos += 4
        return value
    }

    fun readFixed64(): Long {
        if (end - pos < 8) throw ProtoFormatException("Truncated fixed64")
        var value = 0L
        for (i in 0 until 8) value = value or ((buf[pos + i].toLong() and 0xff) shl (8 * i))
        pos += 8
        return value
    }

    fun readLengthDelimited(): ByteArray {
        val length = readVarint()
        if (length < 0 || length > end - pos) throw ProtoFormatException("Length $length exceeds remaining ${end - pos}")
        val bytes = buf.copyOfRange(pos, pos + length.toInt())
        pos += length.toInt()
        return bytes
    }
}

/** Minimal protobuf wire-format writer. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun writeVarint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7fL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
    }

    fun writeTag(number: Int, wireType: Int) = writeVarint(((number shl 3) or wireType).toLong())

    fun writeFixed32(value: Long) {
        for (i in 0 until 4) out.write(((value ushr (8 * i)) and 0xff).toInt())
    }

    fun writeFixed64(value: Long) {
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xff).toInt())
    }

    fun writeLengthDelimited(bytes: ByteArray) {
        writeVarint(bytes.size.toLong())
        out.write(bytes)
    }

    fun varintField(number: Int, value: Long) {
        writeTag(number, WireType.VARINT)
        writeVarint(value)
    }

    fun bytesField(number: Int, bytes: ByteArray) {
        writeTag(number, WireType.LEN)
        writeLengthDelimited(bytes)
    }

    fun stringField(number: Int, value: String) = bytesField(number, value.encodeToByteArray())

    fun field(field: ProtoField) {
        writeTag(field.number, field.wireType)
        when (field.wireType) {
            WireType.VARINT -> writeVarint(field.value)
            WireType.I64 -> writeFixed64(field.value)
            WireType.I32 -> writeFixed32(field.value)
            WireType.LEN -> writeLengthDelimited(field.bytes!!)
            else -> throw ProtoFormatException("Unsupported wire type ${field.wireType}")
        }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
