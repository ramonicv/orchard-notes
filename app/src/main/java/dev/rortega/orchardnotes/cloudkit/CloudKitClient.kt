package dev.rortega.orchardnotes.cloudkit

import dev.rortega.orchardnotes.auth.ClientParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** One page of `changes/zone` results. */
data class ZoneChangesPage(val records: List<CkRecord>, val syncToken: String?, val moreComing: Boolean)

/** Outcome of one `records/modify` operation. */
sealed interface ModifyResult {
    data class Saved(val record: CkRecord) : ModifyResult
    data class Deleted(val recordName: String) : ModifyResult
    data class Failed(val error: CkRecordError) : ModifyResult
}

/**
 * Client for the CloudKit databases of the `com.apple.notes` container: the same
 * endpoints and request shapes the www.icloud.com Notes web app uses. Calls default to
 * the account's own (private) Notes zone; notes shared with the account are read and
 * written in their sharer's zone of the shared database ([NotesZone]).
 */
class CloudKitClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val identity: ClientParams,
    private val account: () -> IcloudAccount?,
) {
    /**
     * Fetches one page of changes in a Notes zone since [syncToken] (null = from scratch).
     * Shared zones list notes without their body (`TextDataEncrypted`); [lookup] fetches it.
     */
    suspend fun changesZone(syncToken: String?, zone: NotesZone = NotesZone.Private): ZoneChangesPage {
        val request = buildJsonObject {
            put("zoneID", zone.zoneId())
            putJsonArray("desiredKeys") { DESIRED_KEYS.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("desiredRecordTypes") { DESIRED_RECORD_TYPES.forEach { add(JsonPrimitive(it)) } }
            // The shared database rejects `reverse` ("Reverse sync of share db is unsupported").
            if (!zone.isShared) put("reverse", true)
            if (syncToken != null) put("syncToken", syncToken)
        }
        val body = post("changes/zone", buildJsonObject { putJsonArray("zones") { add(request) } }, zone.database)
        val zoneResult = (body["zones"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw UnexpectedResponseException("changes/zone returned no zones")
        zoneResult.serverError()?.let { throw CloudKitServerException(it.serverErrorCode, it.reason) }
        return ZoneChangesPage(
            records = (zoneResult["records"] as? JsonArray).orEmpty().mapNotNull(CkRecord::parse),
            syncToken = (zoneResult["syncToken"] as? JsonPrimitive)?.contentOrNull,
            moreComing = (zoneResult["moreComing"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /**
     * Lists the zones of the shared database: one per person who shared notes with this
     * account. Like the web client, starts without a stored token and pages to the end,
     * since a cut-off listing would make later zones look unshared.
     */
    suspend fun sharedZones(): List<SharedZone> {
        val zones = linkedMapOf<String, SharedZone>()
        var token: String? = null
        do {
            val body = post("changes/database", buildJsonObject { token?.let { put("syncToken", it) } }, NotesZone.SHARED_DATABASE)
            val page = body["zones"] as? JsonArray ?: throw UnexpectedResponseException("changes/database returned no zones")
            for (entry in page) {
                val zone = entry as? JsonObject ?: continue
                val zoneId = zone["zoneID"] as? JsonObject ?: continue
                if ((zoneId["zoneName"] as? JsonPrimitive)?.contentOrNull != NotesZone.ZONE_NAME) continue
                val owner = (zoneId["ownerRecordName"] as? JsonPrimitive)?.contentOrNull ?: continue
                zones[owner] = SharedZone(owner, deleted = (zone["deleted"] as? JsonPrimitive)?.booleanOrNull == true)
            }
            val next = (body["syncToken"] as? JsonPrimitive)?.contentOrNull
            val moreComing = (body["moreComing"] as? JsonPrimitive)?.booleanOrNull == true
            if (moreComing && (next == null || next == token)) {
                throw UnexpectedResponseException("changes/database reported more zones without advancing its sync token")
            }
            token = next ?: token
        } while (moreComing)
        return zones.values.toList()
    }

    /** Fetches full records by name; records that no longer exist are omitted. */
    suspend fun lookup(recordNames: List<String>, zone: NotesZone = NotesZone.Private): List<CkRecord> {
        val result = mutableListOf<CkRecord>()
        for (batch in recordNames.distinct().chunked(LOOKUP_BATCH_SIZE)) {
            val body = post(
                "records/lookup",
                buildJsonObject {
                    putJsonArray("records") { batch.forEach { add(buildJsonObject { put("recordName", it) }) } }
                    put("zoneID", zone.zoneId())
                },
                zone.database,
            )
            (body["records"] as? JsonArray).orEmpty().mapNotNullTo(result) { entry ->
                CkRecord.parse(entry)?.takeUnless { it.deleted }
            }
        }
        return result
    }

    suspend fun lookup(recordName: String, zone: NotesZone = NotesZone.Private): CkRecord? =
        lookup(listOf(recordName), zone).firstOrNull()

    /**
     * Applies [operations] atomically in a Notes zone. Per-record failures (notably
     * `CONFLICT` when a recordChangeTag is stale) come back as [ModifyResult.Failed].
     */
    suspend fun modify(operations: List<JsonObject>, zone: NotesZone = NotesZone.Private): List<ModifyResult> {
        val body = post(
            "records/modify",
            buildJsonObject {
                put("operations", JsonArray(operations))
                put("zoneID", zone.zoneId())
            },
            zone.database,
        )
        val records = body["records"] as? JsonArray ?: throw UnexpectedResponseException("records/modify returned no records")
        return records.map { entry ->
            val obj = entry as? JsonObject ?: throw UnexpectedResponseException("Malformed records/modify entry")
            val error = obj.serverError()
            val parsed = CkRecord.parse(obj)
            when {
                error != null -> ModifyResult.Failed(error)
                parsed == null -> throw UnexpectedResponseException("Malformed records/modify entry")
                parsed.deleted && parsed.recordType.isEmpty() -> ModifyResult.Deleted(parsed.recordName)
                else -> ModifyResult.Saved(parsed)
            }
        }
    }

    /** Downloads an asset from its signed `downloadURL` (no cookies needed). */
    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
            response.body.bytes()
        }
    }

    private suspend fun post(operation: String, payload: JsonObject, database: String): JsonObject = withContext(Dispatchers.IO) {
        val account = account() ?: throw SessionExpiredException("Not signed in.")
        val url = "${account.ckDatabaseUrl}/database/1/com.apple.notes/production/$database/$operation".toHttpUrl()
            .newBuilder()
            .addQueryParameter("ckjsBuildVersion", CKJS_BUILD_VERSION)
            .addQueryParameter("ckjsVersion", CKJS_VERSION)
            .addQueryParameter("clientId", identity.clientId)
            .addQueryParameter("clientBuildNumber", identity.clientBuildNumber)
            .addQueryParameter("clientMasteringNumber", identity.clientMasteringNumber)
            .addQueryParameter("dsid", account.dsid)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            when {
                response.code == 401 || response.code == 421 -> throw SessionExpiredException()
                !response.isSuccessful -> {
                    val reason = runCatching {
                        json.parseToJsonElement(response.body.string()).jsonObject.serverError()?.toString()
                    }.getOrNull()
                    throw IOException("iCloud request $database/$operation failed (HTTP ${response.code})${reason?.let { ": $it" } ?: ""}")
                }
            }
            json.parseToJsonElement(response.body.string()) as? JsonObject
                ?: throw UnexpectedResponseException("$operation returned a non-object response")
        }
    }

    private fun JsonObject.serverError(): CkRecordError? {
        val code = (this["serverErrorCode"] as? JsonPrimitive)?.contentOrNull ?: return null
        return CkRecordError(
            recordName = (this["recordName"] as? JsonPrimitive)?.contentOrNull,
            serverErrorCode = code,
            reason = (this["reason"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    companion object {
        // Observed from the www.icloud.com Notes web client; may need bumping over time.
        private const val CKJS_BUILD_VERSION = "2310ProjectDev27"
        private const val CKJS_VERSION = "2.6.4"
        private const val LOOKUP_BATCH_SIZE = 200
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // Matches what the web client requests, so the server sees a familiar shape.
        private val DESIRED_KEYS = listOf(
            "TitleEncrypted", "SnippetEncrypted", "FirstAttachmentUTIEncrypted", "FirstAttachmentThumbnail",
            "FirstAttachmentThumbnailOrientation", "CreationDate", "ModificationDate", "Deleted", "Folders", "Folder",
            "Attachments", "ParentFolder", "Note", "LastViewedModificationDate", "MinimumSupportedNotesVersion",
            "DisplayTextEncrypted", "StandardizedContentEncrypted", "TokenContentIdentifierEncrypted",
            "AltTextEncrypted", "UTIEncrypted", "MergeableDataEncrypted", "IsPinned", "TextDataEncrypted",
            "TextDataAsset",
        )

        private val DESIRED_RECORD_TYPES = listOf(
            "AccountData", "Note", "SearchIndexes", "Folder", "PasswordProtectedNote", "User", "Users",
            "Note_UserSpecific", "PasswordProtectedNote_UserSpecific", "Folder_UserSpecific", "cloudkit.share",
            "Hashtag", "InlineAttachment",
        )

        /**
         * A CloudKit reference value as the web client writes it: a bare zoneName in the
         * private zone, qualified with the sharer in a shared one.
         */
        fun reference(recordName: String, zone: NotesZone = NotesZone.Private): JsonObject = buildJsonObject {
            put("recordName", recordName)
            put("action", "VALIDATE")
            put("zoneID", zone.zoneId())
        }
    }
}
