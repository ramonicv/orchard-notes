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
 * Client for the private CloudKit database of the `com.apple.notes` container: the
 * same endpoints and request shapes the www.icloud.com Notes web app uses.
 */
class CloudKitClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val identity: ClientParams,
    private val account: () -> IcloudAccount?,
) {
    /** Fetches one page of changes in the Notes zone since [syncToken] (null = from scratch). */
    suspend fun changesZone(syncToken: String?): ZoneChangesPage {
        val zone = buildJsonObject {
            put("zoneID", NOTES_ZONE)
            putJsonArray("desiredKeys") { DESIRED_KEYS.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("desiredRecordTypes") { DESIRED_RECORD_TYPES.forEach { add(JsonPrimitive(it)) } }
            put("reverse", true)
            if (syncToken != null) put("syncToken", syncToken)
        }
        val body = post("changes/zone", buildJsonObject { putJsonArray("zones") { add(zone) } })
        val zoneResult = (body["zones"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw UnexpectedResponseException("changes/zone returned no zones")
        zoneResult.serverError()?.let { throw CloudKitServerException(it.serverErrorCode, it.reason) }
        return ZoneChangesPage(
            records = (zoneResult["records"] as? JsonArray).orEmpty().mapNotNull(CkRecord::parse),
            syncToken = (zoneResult["syncToken"] as? JsonPrimitive)?.contentOrNull,
            moreComing = (zoneResult["moreComing"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    /** Fetches full records by name; records that no longer exist are omitted. */
    suspend fun lookup(recordNames: List<String>): List<CkRecord> {
        val result = mutableListOf<CkRecord>()
        for (batch in recordNames.distinct().chunked(LOOKUP_BATCH_SIZE)) {
            val body = post(
                "records/lookup",
                buildJsonObject {
                    putJsonArray("records") { batch.forEach { add(buildJsonObject { put("recordName", it) }) } }
                    put("zoneID", NOTES_ZONE)
                },
            )
            (body["records"] as? JsonArray).orEmpty().mapNotNullTo(result) { entry ->
                CkRecord.parse(entry)?.takeUnless { it.deleted }
            }
        }
        return result
    }

    suspend fun lookup(recordName: String): CkRecord? = lookup(listOf(recordName)).firstOrNull()

    /**
     * Applies [operations] atomically in the Notes zone. Per-record failures (notably
     * `CONFLICT` when a recordChangeTag is stale) come back as [ModifyResult.Failed].
     */
    suspend fun modify(operations: List<JsonObject>): List<ModifyResult> {
        val body = post(
            "records/modify",
            buildJsonObject {
                put("operations", JsonArray(operations))
                put("zoneID", NOTES_ZONE)
            },
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

    private suspend fun post(operation: String, payload: JsonObject): JsonObject = withContext(Dispatchers.IO) {
        val account = account() ?: throw SessionExpiredException("Not signed in.")
        val url = "${account.ckDatabaseUrl}/database/1/com.apple.notes/production/private/$operation".toHttpUrl()
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
                    throw IOException("iCloud request $operation failed (HTTP ${response.code})${reason?.let { ": $it" } ?: ""}")
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

        val NOTES_ZONE: JsonObject = buildJsonObject { put("zoneName", "Notes") }

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

        /** A CloudKit reference value as the web client writes it in the private zone. */
        fun reference(recordName: String): JsonObject = buildJsonObject {
            put("recordName", recordName)
            put("action", "VALIDATE")
            put("zoneID", NOTES_ZONE)
        }
    }
}
