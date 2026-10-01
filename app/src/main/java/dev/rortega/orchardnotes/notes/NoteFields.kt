package dev.rortega.orchardnotes.notes

import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.NotesZone
import dev.rortega.orchardnotes.data.SpecialFolders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/*
 * Field sets for Note and Folder writes, matching what the www.icloud.com client sends.
 * Ported from icloud-md's encodeNoteRecord.ts / encodeFolderRecord.ts (MIT, Copyright (c)
 * 2026 Adam Coddington), which derived them from captured web-client requests.
 */
object NoteFields {

    /** The note's first line, cut back to a word boundary when long (as the web client does). */
    fun title(text: String): String {
        val firstLine = text.substringBefore('\n')
        if (firstLine.length <= TITLE_MAX_LENGTH) return firstLine
        val boundary = firstLine.lastIndexOf(' ', TITLE_MAX_LENGTH)
        return if (boundary > 0) firstLine.substring(0, boundary) else firstLine.substring(0, TITLE_MAX_LENGTH)
    }

    /** The first non-empty line after the title; the web client stores a placeholder when there is none. */
    fun snippet(text: String): String {
        val afterTitle = text.substring(title(text).length).trimStart()
        val snippet = afterTitle.substringBefore('\n').take(SNIPPET_MAX_LENGTH)
        return snippet.ifEmpty { EMPTY_SNIPPET }
    }

    /**
     * Fields for a brand-new note's `create` (the captured first save). In a sharer's zone
     * the folder references name that zone (the captured shared-note create).
     */
    fun create(textDataBase64: String, text: String, nowMs: Long, folderRecordName: String, zone: NotesZone = NotesZone.Private): JsonObject {
        val folder = CloudKitClient.reference(folderRecordName, zone)
        return buildJsonObject {
            put("CreationDate", value(JsonPrimitive(nowMs)))
            put("Folders", value(JsonArray(listOf(folder))))
            put("Folder", value(folder))
            put("ModificationDate", value(JsonPrimitive(nowMs)))
            put("TitleEncrypted", value(JsonPrimitive(base64(title(text)))))
            put("SnippetEncrypted", value(JsonPrimitive(base64(snippet(text)))))
            PLACEHOLDER_FIELDS.forEach { put(it, JsonObject(emptyMap())) }
            put("TextDataEncrypted", value(JsonPrimitive(textDataBase64)))
        }
    }

    /** Fields for a text/formatting update: new body and display metadata, everything else echoed verbatim. */
    fun update(current: CkRecord, textDataBase64: String, text: String, nowMs: Long): JsonObject = buildJsonObject {
        put("ModificationDate", value(JsonPrimitive(nowMs)))
        put("TitleEncrypted", value(JsonPrimitive(base64(title(text)))))
        ECHOED_FIELDS.forEach { name -> current.rawValue(name)?.let { put(name, value(it)) } }
        put("SnippetEncrypted", value(JsonPrimitive(base64(snippet(text)))))
        PLACEHOLDER_FIELDS.forEach { name -> put(name, value(current.rawValue(name) ?: JsonNull)) }
        put("TextDataEncrypted", value(JsonPrimitive(textDataBase64)))
    }

    /**
     * Moves a note to another folder. Apple's "delete" is the same move, to Recently
     * Deleted; [purge] additionally marks it `Deleted` so the server removes it for good.
     */
    fun relocate(current: CkRecord, folderRecordName: String, nowMs: Long, purge: Boolean = false): JsonObject {
        val folder = CloudKitClient.reference(folderRecordName)
        return buildJsonObject {
            current.rawValue("CreationDate")?.let { put("CreationDate", value(it)) }
            put("ModificationDate", value(JsonPrimitive(nowMs)))
            current.rawValue("TitleEncrypted")?.let { put("TitleEncrypted", value(it)) }
            put("Folders", value(JsonArray(listOf(folder))))
            put("FoldersModificationDate", value(JsonPrimitive(nowMs)))
            put("Folder", value(folder))
            current.rawValue("SnippetEncrypted")?.let { put("SnippetEncrypted", value(it)) }
            if (purge) put("Deleted", value(JsonPrimitive(1)))
            PLACEHOLDER_FIELDS.forEach { put(it, JsonObject(emptyMap())) }
            current.rawValue("TextDataEncrypted")?.let { put("TextDataEncrypted", value(it)) }
        }
    }

    fun trash(current: CkRecord, nowMs: Long) = relocate(current, SpecialFolders.TRASH, nowMs)

    /** A folder is just its title, plus a parent reference when nested. */
    fun folder(title: String, parentRecordName: String?): JsonObject = buildJsonObject {
        put("TitleEncrypted", value(JsonPrimitive(base64(title))))
        if (parentRecordName != null) put("ParentFolder", value(CloudKitClient.reference(parentRecordName)))
    }

    /** [createShortGuid]: asked for by the captured create of a note in a shared folder. */
    fun createOperation(
        recordType: String,
        recordName: String,
        fields: JsonObject,
        parent: String? = null,
        createShortGuid: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("operationType", "create")
        put(
            "record",
            buildJsonObject {
                put("recordName", recordName)
                put("recordType", recordType)
                put("fields", fields)
                if (parent != null) put("parent", buildJsonObject { put("recordName", parent) })
                if (createShortGuid) put("createShortGUID", true)
            },
        )
    }

    fun updateOperation(current: CkRecord, fields: JsonObject): JsonObject = buildJsonObject {
        put("operationType", "update")
        put(
            "record",
            buildJsonObject {
                put("recordName", current.recordName)
                put("recordType", current.recordType)
                current.recordChangeTag?.let { put("recordChangeTag", it) }
                put("fields", fields)
                current.parentRecordName?.let { parent -> put("parent", buildJsonObject { put("recordName", parent) }) }
            },
        )
    }

    private fun value(element: JsonElement) = buildJsonObject { put("value", element) }

    private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun CkRecord.rawValue(name: String): JsonElement? = (fields[name] as? JsonObject)?.get("value")

    private const val TITLE_MAX_LENGTH = 76
    private const val SNIPPET_MAX_LENGTH = 500
    private const val EMPTY_SNIPPET = "No additional text"

    private val ECHOED_FIELDS = listOf(
        "MinimumSupportedNotesVersion", "Folders", "Deleted", "Folder", "CreationDate",
        "ReplicaIDToNotesVersionDataEncrypted", "FoldersModificationDate", "AttachmentViewType",
        "PaperStyleType", "ReplicaIDToUserIDEncrypted",
    )

    private val PLACEHOLDER_FIELDS = listOf("FirstAttachmentThumbnail", "FirstAttachmentUTIEncrypted", "TextDataAsset")
}
