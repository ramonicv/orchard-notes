package dev.rortega.orchardnotes.notes.proto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoMessageTest {
    @Test
    fun parseEncodePreservesOrderUnknownFieldsAndExplicitZeros() {
        val writer = ProtoWriter().apply {
            varintField(5, 0) // explicit zero must survive
            stringField(2, "hé")
            writeTag(10, WireType.I32); writeFixed32(0x3f800000) // 1.0f
            varintField(8, -1) // negative int32: 10-byte varint
            varintField(2, 7) // duplicate number, out of order
        }
        val bytes = writer.toByteArray()
        val message = ProtoMessage.parse(bytes)
        assertArrayEquals(bytes, message.encode())
        assertEquals(1.0f, message.float(10))
        assertEquals(-1, message.varint(8)!!.toInt())
    }

    @Test
    fun setReplacesInPlaceOrInsertsAtCanonicalPosition() {
        val message = ProtoMessage().apply {
            setVarint(1, 1)
            setVarint(5, 5)
        }
        message.setVarint(3, 3)
        assertEquals(listOf(1, 3, 5), message.fields.map { it.number })
        message.setVarint(1, 9)
        assertEquals(listOf(1, 3, 5), message.fields.map { it.number })
        assertEquals(9L, message.varint(1))
        message.setVarint(7, 7)
        assertEquals(listOf(1, 3, 5, 7), message.fields.map { it.number })
    }
}
