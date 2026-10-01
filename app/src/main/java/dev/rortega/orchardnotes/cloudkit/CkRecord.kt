package dev.rortega.orchardnotes.cloudkit

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * One CloudKit record as returned by the web-services API. Field values are kept
 * as raw JSON (`{"value": ..., "type": ...}`) so updates can echo them back verbatim.
 */
data class CkRecord(
    val recordName: String,
    val recordType: String,
    val fields: JsonObject,
    val recordChangeTag: String? = null,
    /** A deletion tombstone from `changes/zone` (no type or fields). */
    val deleted: Boolean = false,
    val parentRecordName: String? = null,
) {
    fun value(name: String): JsonElement? = (fields[name] as? JsonObject)?.get("value")?.takeUnless { it is JsonNull }

    fun type(name: String): String? = ((fields[name] as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull

    fun long(name: String): Long? = (value(name) as? JsonPrimitive)?.longOrNull

    fun stringValue(name: String): String? = (value(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** The recordName a REFERENCE field points at. */
    fun reference(name: String): String? = ((value(name) as? JsonObject)?.get("recordName") as? JsonPrimitive)?.contentOrNull

    fun referenceList(name: String): List<String> = (value(name) as? JsonArray).orEmpty().mapNotNull {
        ((it as? JsonObject)?.get("recordName") as? JsonPrimitive)?.contentOrNull
    }

    /** Raw bytes of a BYTES / ENCRYPTED_BYTES field (base64 on the wire). */
    fun bytes(name: String): ByteArray? = stringValue(name)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    /**
     * Text stored in an `...Encrypted` field. Despite the name, accounts without
     * Advanced Data Protection receive these as plain base64 UTF-8 (the encryption is
     * server-side at rest); a field typed STRING is already plain text.
     */
    fun encryptedString(name: String): String? {
        val raw = stringValue(name) ?: return null
        if (type(name) == "STRING") return raw
        return runCatching { Base64.getDecoder().decode(raw).decodeToString() }.getOrDefault(raw)
    }

    companion object {
        fun parse(element: JsonElement): CkRecord? {
            val obj = element as? JsonObject ?: return null
            val name = (obj["recordName"] as? JsonPrimitive)?.contentOrNull ?: return null
            val type = (obj["recordType"] as? JsonPrimitive)?.contentOrNull
            val fields = obj["fields"] as? JsonObject
            val deleted = (obj["deleted"] as? JsonPrimitive)?.booleanOrNull == true
            if (type == null || fields == null) {
                // A tombstone (`{"recordName": "...", "deleted": true}`) or a per-record error entry.
                return if (deleted) CkRecord(name, "", JsonObject(emptyMap()), deleted = true) else null
            }
            return CkRecord(
                recordName = name,
                recordType = type,
                fields = fields,
                recordChangeTag = (obj["recordChangeTag"] as? JsonPrimitive)?.contentOrNull,
                deleted = deleted,
                parentRecordName = ((obj["parent"] as? JsonObject)?.get("recordName") as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}

/** A per-record failure reported inside a successful HTTP response. */
data class CkRecordError(val recordName: String?, val serverErrorCode: String, val reason: String?)

class CloudKitServerException(val serverErrorCode: String, reason: String?) :
    IcloudException("CloudKit error $serverErrorCode${reason?.let { ": $it" } ?: ""}")
