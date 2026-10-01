package dev.rortega.orchardnotes.notes

import java.util.Base64

/** Real captured note payloads; see src/test/resources/fixtures/README.md. */
object Fixtures {
    fun compressed(name: String): ByteArray {
        val text = Fixtures::class.java.getResource("/fixtures/$name.b64")!!.readText().trim()
        return Base64.getDecoder().decode(text)
    }

    const val PLAIN = "real_plain_note"
    const val FORMATTED_MULTI_EDIT = "real_formatted_multi_edit_note"
    const val UNICODE = "real_unicode_note"
    const val FIRST_SAVE = "real_first_save_note"
    val ALL = listOf(PLAIN, FORMATTED_MULTI_EDIT, UNICODE, FIRST_SAVE)
}
