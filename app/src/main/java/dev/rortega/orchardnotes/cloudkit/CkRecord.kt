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
    /** The `cloudkit.share` this record is the root of, when it was shared (a note or folder). */
    val shareRecordName: String? = null,
    /** `cloudkit.share` records: this account's permission, `READ_WRITE` or `READ_ONLY`. */
    val currentUserPermission: String? = null,
    /** `cloudkit.share` records: everyone in the share, its owner included. */
    val participants: List<ShareParticipant> = emptyList(),
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
            val name = obj.string("recordName") ?: return null
            val type = obj.string("recordType")
            val fields = obj["fields"] as? JsonObject
            val deleted = (obj["deleted"] as? JsonPrimitive)?.booleanOrNull == true
            if (type == null || obj.containsKey("serverErrorCode") || (fields == null && type != SHARE_TYPE)) {
                // A tombstone (`{"recordName": "...", "deleted": true}`) or a per-record error entry.
                return if (deleted) CkRecord(name, "", JsonObject(emptyMap()), deleted = true) else null
            }
            return CkRecord(
                recordName = name,
                recordType = type,
                fields = fields ?: JsonObject(emptyMap()),
                recordChangeTag = obj.string("recordChangeTag"),
                deleted = deleted,
                parentRecordName = (obj["parent"] as? JsonObject).string("recordName"),
                shareRecordName = (obj["share"] as? JsonObject).string("recordName"),
                currentUserPermission = (obj["currentUserParticipant"] as? JsonObject).string("permission"),
                participants = (obj["participants"] as? JsonArray).orEmpty().mapNotNull { ShareParticipant.parse(it) },
            )
        }

        const val SHARE_TYPE = "cloudkit.share"
    }
}

/** One person in a `cloudkit.share`. */
data class ShareParticipant(
    /** `OWNER` for the person who shared; others are `PRIVATE_USER` (or `PUBLIC_USER`). */
    val type: String?,
    /** Given and family name, when iCloud knows them. */
    val name: String?,
    val email: String?,
    val phone: String?,
    /** `READ_WRITE` or `READ_ONLY`. */
    val permission: String?,
    /** `ACCEPTED`, `INVITED`, `PENDING` or `REMOVED`. */
    val acceptanceStatus: String?,
) {
    val isOwner: Boolean get() = type == "OWNER"

    /** Full name, else email, else phone number. */
    val displayName: String? get() = name ?: email ?: phone

    companion object {
        fun parse(element: JsonElement): ShareParticipant? {
            val participant = element as? JsonObject ?: return null
            val identity = participant["userIdentity"] as? JsonObject
            val names = identity?.get("nameComponents") as? JsonObject
            val lookupInfo = identity?.get("lookupInfo") as? JsonObject
            val fullName = listOfNotNull(names.string("givenName"), names.string("familyName"))
                .filter { it.isNotBlank() }
                .joinToString(" ")
            return ShareParticipant(
                type = participant.string("type"),
                name = fullName.ifBlank { null },
                email = lookupInfo.string("emailAddress")?.ifBlank { null },
                phone = lookupInfo.string("phoneNumber")?.ifBlank { null },
                permission = participant.string("permission"),
                acceptanceStatus = participant.string("acceptanceStatus"),
            )
        }
    }
}

private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

/** A per-record failure reported inside a successful HTTP response. */
data class CkRecordError(val recordName: String?, val serverErrorCode: String, val reason: String?)

class CloudKitServerException(val serverErrorCode: String, reason: String?) :
    IcloudException("CloudKit error $serverErrorCode${reason?.let { ": $it" } ?: ""}")
