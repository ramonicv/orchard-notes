package dev.rortega.orchardnotes.notes.doc

import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Note bodies arrive gzip- or zlib-compressed depending on which client last wrote
 * them. We always write zlib, matching the www.icloud.com client.
 */
object NoteCompression {
    fun decompress(bytes: ByteArray): ByteArray {
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val stream = if (isGzip) GZIPInputStream(bytes.inputStream()) else InflaterInputStream(bytes.inputStream())
        return stream.use { it.readBytes() }
    }

    fun compress(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
