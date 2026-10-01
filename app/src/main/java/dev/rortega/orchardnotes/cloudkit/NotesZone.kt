package dev.rortega.orchardnotes.cloudkit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A Notes zone: the account's own, in the private database, or a sharer's, in the shared
 * database. Each person who shared notes with this account has one zone there, also named
 * "Notes" and told apart by its owner.
 */
data class NotesZone(
    /** The sharer's user record name; null for the account's own zone. */
    val owner: String?,
) {
    val isShared: Boolean get() = owner != null

    /** The database the zone lives in, as it appears in request paths. */
    val database: String get() = if (owner == null) PRIVATE_DATABASE else SHARED_DATABASE

    /** The zoneID requests and references name the zone by. */
    fun zoneId(): JsonObject = buildJsonObject {
        put("zoneName", ZONE_NAME)
        owner?.let { put("ownerRecordName", it) }
    }

    companion object {
        const val ZONE_NAME = "Notes"
        const val PRIVATE_DATABASE = "private"
        const val SHARED_DATABASE = "shared"
        val Private = NotesZone(null)
    }
}

/** One zone of the shared database: what one person shared with this account. */
data class SharedZone(
    val owner: String,
    /** The share was revoked or deleted; the zone is still listed but can't be read. */
    val deleted: Boolean,
)
