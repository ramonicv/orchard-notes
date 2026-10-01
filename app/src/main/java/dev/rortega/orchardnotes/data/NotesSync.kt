package dev.rortega.orchardnotes.data

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.CloudKitServerException
import dev.rortega.orchardnotes.cloudkit.NotesZone
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Incremental sync of Notes zones into the local cache, using CloudKit's `changes/zone`
 * sync-token model (the same one the web client uses): the account's own zone, plus one
 * zone per person who shared notes with the account (in the shared database).
 */
class NotesSync(
    private val cloudKit: CloudKitClient,
    private val dao: NotesDao,
    private val prefs: SharedPreferences,
    /** Notes that just arrived from iCloud (not from our own writes): local edits to them get rebased. */
    private val onServerNotes: suspend (List<NoteEntity>) -> Unit = {},
) {
    private val mutex = Mutex()

    /** Pulls every change since the last sync, in every zone. Returns the number of records applied. */
    suspend fun sync(): Int = mutex.withLock {
        upgradeCache()
        pullZone(NotesZone.Private) + pullSharedZones()
    }

    /** Pulls changes in one zone: enough to keep a note that's open on screen live. */
    suspend fun syncZone(zone: NotesZone): Int = mutex.withLock {
        if (zone.isShared) pullSharedZone(zone.owner!!) else pullZone(zone)
    }

    /** Applies records that came back from our own writes, without waiting for the next sync. */
    suspend fun applyRecords(records: List<CkRecord>, zone: NotesZone = NotesZone.Private) = apply(records, zone, fromServer = false)

    /** Forgets everything, including unsaved local edits (used on sign-out). */
    suspend fun reset() = mutex.withLock {
        prefs.edit {
            prefs.all.keys.filter { it == KEY_SYNC_TOKEN || it.startsWith(SHARED_TOKEN_PREFIX) }.forEach(::remove)
        }
        dao.clearAll(includingPending = true)
    }

    /** Caches from before shared notes lack the share links of the account's own notes: refetch once. */
    private fun upgradeCache() {
        if (prefs.getInt(KEY_CACHE_FORMAT, 1) >= CACHE_FORMAT) return
        prefs.edit {
            remove(KEY_SYNC_TOKEN)
            putInt(KEY_CACHE_FORMAT, CACHE_FORMAT)
        }
    }

    private suspend fun pullSharedZones(): Int {
        val zones = cloudKit.sharedZones()
        val live = zones.filterNot { it.deleted }.map { it.owner }.toSet()
        // No longer shared with this account: revoked, deleted, or not listed at all.
        (dao.sharedZoneOwners().toSet() - live).forEach { forgetZone(it) }
        return live.sumOf { pullSharedZone(it) }
    }

    private suspend fun pullSharedZone(owner: String): Int = try {
        pullZone(NotesZone(owner))
    } catch (e: CloudKitServerException) {
        // Still listed, but the share was revoked or deleted in the meantime.
        if (e.serverErrorCode != "ZONE_NOT_FOUND") throw e
        forgetZone(owner)
        0
    }

    private suspend fun forgetZone(owner: String) {
        dao.forgetZone(owner)
        prefs.edit { remove(tokenKey(NotesZone(owner))) }
    }

    private suspend fun pullZone(zone: NotesZone): Int {
        val key = tokenKey(zone)
        val storedToken = prefs.getString(key, null)
        return try {
            pull(zone, storedToken)
        } catch (e: CloudKitServerException) {
            // An unusable sync token surfaces as a zone-level BAD_REQUEST: refetch from scratch.
            if (storedToken == null || e.serverErrorCode != "BAD_REQUEST") throw e
            prefs.edit { remove(key) }
            pull(zone, null)
        }
    }

    private suspend fun pull(zone: NotesZone, startToken: String?): Int {
        val key = tokenKey(zone)
        var token = startToken
        var applied = 0
        // A listing from scratch has no tombstones: whatever it doesn't mention is gone.
        val seen = if (startToken == null) mutableSetOf<String>() else null
        do {
            val page = cloudKit.changesZone(token, zone)
            // Bodies that couldn't be fetched: leave this page (and its token) for the next sync.
            val records = withBodies(page.records, zone) ?: return applied
            apply(records, zone, fromServer = true)
            seen?.addAll(records.map { it.recordName })
            applied += records.size
            if (page.moreComing && (page.syncToken == null || page.syncToken == token)) {
                throw IllegalStateException("CloudKit reported more changes without advancing the sync token")
            }
            token = page.syncToken ?: token
            // Safe to persist per page: everything up to this token is already in the cache.
            prefs.edit { putString(key, token) }
        } while (page.moreComing)
        if (seen != null) sweep(zone, seen)
        return applied
    }

    /**
     * Shared zones list notes without their body; this fetches them. Null if some are still
     * missing (a failed lookup, or one racing a deletion): better to retry the page later
     * than to cache notes without their content.
     */
    private suspend fun withBodies(records: List<CkRecord>, zone: NotesZone): List<CkRecord>? {
        if (!zone.isShared) return records
        val missing = records.filter {
            it.recordType == "Note" && !NoteRecords.isGone(it, zone) && it.value("TextDataEncrypted") == null
        }
        if (missing.isEmpty()) return records
        val found = cloudKit.lookup(missing.map { it.recordName }, zone).associateBy { it.recordName }
        if (missing.any { it.recordName !in found }) return null
        return records.map { listed ->
            found[listed.recordName]?.let { full -> full.copy(recordChangeTag = full.recordChangeTag ?: listed.recordChangeTag) } ?: listed
        }
    }

    /** After a full listing: drops what the zone no longer has, keeping anything with unsent local changes. */
    private suspend fun sweep(zone: NotesZone, seen: Set<String>) {
        val goneNotes = dao.noteNamesInZone(zone.owner).filter { it !in seen && dao.getPending(it) == null }
        val newFolders = dao.pushableOps().filter { it.type == PendingOpEntity.CREATE_FOLDER }.map { it.recordName }.toSet()
        val goneFolders = dao.folderKeysInZone(zone.owner)
            .filter { it !in newFolders }
            .map(NoteRecords::folderRecordName)
            .filter { it !in seen }
        if (goneNotes.isNotEmpty() || goneFolders.isNotEmpty()) {
            dao.applyChanges(zone.owner, emptyList(), emptyList(), emptyList(), goneNotes + goneFolders)
        }
    }

    private suspend fun apply(records: List<CkRecord>, zone: NotesZone, fromServer: Boolean) {
        val notes = mutableListOf<NoteEntity>()
        val folders = mutableListOf<FolderEntity>()
        val shares = mutableListOf<ShareEntity>()
        val deleted = mutableListOf<String>()
        for (record in records) {
            when {
                record.deleted -> deleted += record.recordName
                record.recordType == "Note" || record.recordType == "PasswordProtectedNote" ->
                    if (NoteRecords.isGone(record, zone)) {
                        deleted += record.recordName
                    } else {
                        notes += NoteRecords.toNoteEntity(record, zone).copy(syncedAt = LocalClock.next())
                    }
                record.recordType == "Folder" ->
                    if ((record.long("Deleted") ?: 0L) != 0L) deleted += record.recordName else folders += NoteRecords.toFolderEntity(record, zone)
                record.recordType == CkRecord.SHARE_TYPE -> shares += NoteRecords.toShareEntity(record, zone)
            }
        }
        dao.applyChanges(zone.owner, notes, folders, shares, deleted)
        if (fromServer && notes.isNotEmpty()) onServerNotes(notes)
    }

    private fun tokenKey(zone: NotesZone): String = zone.owner?.let { SHARED_TOKEN_PREFIX + it } ?: KEY_SYNC_TOKEN

    private companion object {
        const val KEY_SYNC_TOKEN = "notes_zone_sync_token"
        const val SHARED_TOKEN_PREFIX = "shared_zone_sync_token:"
        const val KEY_CACHE_FORMAT = "cache_format"

        /** 2: notes and folders know their zone and share. */
        const val CACHE_FORMAT = 2
    }
}
