package dev.rortega.orchardnotes

import android.content.Intent
import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.cloudkit.IcloudAccount
import dev.rortega.orchardnotes.data.SpecialFolders
import dev.rortega.orchardnotes.notes.ParagraphMerge
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.FormatReconcile
import dev.rortega.orchardnotes.notes.doc.InlineSpan
import dev.rortega.orchardnotes.notes.doc.InlineStyle
import dev.rortega.orchardnotes.notes.doc.NoteCompression
import dev.rortega.orchardnotes.notes.doc.NoteContent
import dev.rortega.orchardnotes.notes.NoteFields
import dev.rortega.orchardnotes.notes.doc.NoteEditing
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Debug builds only: `adb shell am start -n dev.rortega.orchardnotes/.MainActivity --ez demo true`
 * opens the app on sample notes without an iCloud account (it behaves as if offline).
 */
object DemoMode {
    fun handle(intent: Intent?, container: AppContainer): Boolean {
        if (intent?.getBooleanExtra("demo", false) != true) return false
        container.sessionManager.enterDemo(IcloudAccount("0", "demo@example.com", "Demo Account", "https://demo.invalid"))
        container.appScope.launch { seed(container) }
        return true
    }

    private suspend fun seed(container: AppContainer) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val records = mutableListOf<CkRecord>()
        fun folder(name: String, title: String, parent: String? = null) {
            records += CkRecord(name, "Folder", buildJsonObject {
                put("TitleEncrypted", field(b64(title)))
                if (parent != null) put("ParentFolder", buildJsonObject { put("value", buildJsonObject { put("recordName", parent) }) })
            })
        }
        fun note(name: String, folder: String, modified: Long, body: String, pinned: Boolean = false) {
            val text = NoteContent.decode(Base64.getDecoder().decode(body)).text
            records += CkRecord(name, "Note", buildJsonObject {
                put("TitleEncrypted", field(b64(NoteFields.title(text))))
                put("SnippetEncrypted", field(b64(NoteFields.snippet(text))))
                put("TextDataEncrypted", field(body))
                put("ModificationDate", buildJsonObject { put("value", modified) })
                put("CreationDate", buildJsonObject { put("value", modified) })
                put("IsPinned", buildJsonObject { put("value", if (pinned) 1 else 0) })
                put("Folder", buildJsonObject { put("value", buildJsonObject { put("recordName", folder) }) })
            }, recordChangeTag = "demo")
        }

        folder(SpecialFolders.DEFAULT, "Notes")
        folder("demo-travel", "Travel")
        folder("demo-work", "Work")
        folder("demo-projects", "Projects", parent = "demo-work")

        note("demo-welcome", SpecialFolders.DEFAULT, now - 60_000, build(
            "Welcome to Orchard" to ParagraphKind.Title,
            "Your Apple Notes, on Android. Tap anywhere in a note to edit it." to ParagraphKind.Body,
            "" to ParagraphKind.Body,
            "What works" to ParagraphKind.Heading,
            "Headings, lists and checklists" to ParagraphKind.Checklist.done(),
            "Bold, italic, underline and strikethrough" to ParagraphKind.Checklist.done(),
            "Edits that sync back to iCloud" to ParagraphKind.Checklist,
            "Bullet points" to ParagraphKind.BulletList,
            "Nested ones too" to ParagraphKind.BulletList.indented(),
            "First step" to ParagraphKind.NumberedList,
            "Second step" to ParagraphKind.NumberedList,
            inline = mapOf(1 to listOf(InlineSpan(10, InlineStyle(bold = true)), InlineSpan(50, InlineStyle.Plain))),
        ), pinned = true)
        note("demo-groceries", SpecialFolders.DEFAULT, now - 2 * 3_600_000, build(
            "Groceries" to ParagraphKind.Title,
            "Eggs" to ParagraphKind.Checklist.done(),
            "Sourdough" to ParagraphKind.Checklist,
            "Oat milk" to ParagraphKind.Checklist,
            "Basil" to ParagraphKind.Checklist.done(),
            "Lemons" to ParagraphKind.Checklist,
        ))
        note("demo-lisbon", "demo-travel", now - 3 * day, build(
            "Lisbon, April" to ParagraphKind.Title,
            "Flights" to ParagraphKind.Heading,
            "TP 1351 departs 07:40 from gate 12." to ParagraphKind.Body,
            "Neighborhoods" to ParagraphKind.Subheading,
            "Alfama" to ParagraphKind.BulletList,
            "Bairro Alto" to ParagraphKind.BulletList,
            "Belém" to ParagraphKind.BulletList,
            "Packing" to ParagraphKind.Subheading,
            "Comfortable shoes" to ParagraphKind.DashList,
            "Adapter (type F)" to ParagraphKind.DashList,
            "Wi-Fi: lisboa-guest / sardinhas2026" to ParagraphKind.Monospaced,
        ))
        note("demo-standup", "demo-projects", now - 12 * day, build(
            "Standup notes" to ParagraphKind.Title,
            "Ship the offline queue" to ParagraphKind.NumberedList,
            "Review sync conflict handling" to ParagraphKind.NumberedList,
            "Write the README" to ParagraphKind.NumberedList,
        ))
        note("demo-captured", "demo-work", now - 40 * day, asset(container, "demo/real_formatted_multi_edit_note.b64"))
        note("demo-unicode", SpecialFolders.DEFAULT, now - 400 * day, asset(container, "demo/real_unicode_note.b64"))

        container.notesSync.applyRecords(records)
    }

    private fun ParagraphKind.done() = this to true
    private fun ParagraphKind.indented() = this to false

    private fun build(vararg lines: Any, inline: Map<Int, List<InlineSpan>> = emptyMap()): String {
        val paragraphs = ParagraphMerge.withOffsets(
            lines.mapIndexed { i, line ->
                @Suppress("UNCHECKED_CAST")
                val (text, spec) = line as Pair<String, Any>
                val (kind, flag) = when (spec) {
                    is ParagraphKind -> spec to null
                    else -> (spec as Pair<ParagraphKind, Boolean>)
                }
                FormatParagraph(
                    kind = kind,
                    text = text,
                    done = flag == true && kind == ParagraphKind.Checklist,
                    indent = if (flag == false) 1 else 0,
                    spans = inline[i] ?: if (text.isEmpty()) emptyList() else listOf(InlineSpan(text.length, InlineStyle.Plain)),
                )
            },
        )
        val replica = ByteArray(16) { 0x5a }
        val doc = NoteEditing.buildInitialDocument(paragraphs.joinToString("\n") { it.text }, replica)
        FormatReconcile.reconcile(doc, paragraphs, replica)
        return Base64.getEncoder().encodeToString(NoteCompression.compress(doc.encode()))
    }

    private fun asset(container: AppContainer, path: String): String =
        container.appContext.assets.open(path).bufferedReader().readText().trim()

    private fun field(value: String): JsonObject = buildJsonObject { put("value", JsonPrimitive(value)) }

    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
}
