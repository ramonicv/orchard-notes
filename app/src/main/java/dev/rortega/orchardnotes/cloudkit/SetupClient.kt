package dev.rortega.orchardnotes.cloudkit

import dev.rortega.orchardnotes.auth.ClientIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/** The signed-in Apple account and where its CloudKit data lives. */
data class IcloudAccount(
    val dsid: String,
    val appleId: String,
    val fullName: String?,
    /** Partition host for CloudKit database calls, e.g. `https://p43-ckdatabasews.icloud.com:443`. */
    val ckDatabaseUrl: String,
)

/** Outcome of checking whether the current cookies hold a fully signed-in session. */
sealed interface ValidateResult {
    data class SignedIn(val account: IcloudAccount) : ValidateResult

    /** Password accepted but two-factor authentication is still pending. */
    data object ChallengePending : ValidateResult

    /** No (or an expired) session. */
    data object NotSignedIn : ValidateResult
}

/**
 * Client for setup.icloud.com, the account bootstrap service the web client calls
 * on every page load. A successful `/validate` both proves the session is alive and
 * tells us the account's dsid and its CloudKit partition.
 */
class SetupClient(
    private val http: OkHttpClient,
    private val json: Json,
    private val identity: ClientIdentity,
) {
    suspend fun validate(): ValidateResult = withContext(Dispatchers.IO) {
        val url = "$SETUP_HOST/setup/ws/1/validate".toHttpUrl().newBuilder()
            .addQueryParameter("clientBuildNumber", identity.clientBuildNumber)
            .addQueryParameter("clientMasteringNumber", identity.clientMasteringNumber)
            .addQueryParameter("clientId", identity.clientId)
            .addQueryParameter("requestId", UUID.randomUUID().toString())
            .build()
        val request = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 421 || response.code == 403) {
                return@withContext ValidateResult.NotSignedIn
            }
            if (!response.isSuccessful) throw IOException("iCloud validation failed (HTTP ${response.code})")
            val body = json.parseToJsonElement(response.body.string()).jsonObject
            parseValidateBody(body)
        }
    }

    /** Best-effort server-side sign-out, mirroring the web client's own logout call. */
    suspend fun logout() = withContext(Dispatchers.IO) {
        val url = "$SETUP_HOST/setup/ws/1/logout".toHttpUrl().newBuilder()
            .addQueryParameter("clientBuildNumber", identity.clientBuildNumber)
            .addQueryParameter("clientMasteringNumber", identity.clientMasteringNumber)
            .addQueryParameter("clientId", identity.clientId)
            .build()
        val body = """{"trustBrowsers":false,"allBrowsers":false}""".toRequestBody(null)
        runCatching { http.newCall(Request.Builder().url(url).post(body).build()).execute().close() }
    }

    companion object {
        const val SETUP_HOST = "https://setup.icloud.com"

        /**
         * Interprets a `/validate` or `/accountLogin` response body. A body with
         * `hsaChallengeRequired: true` looks complete (it even lists web services)
         * but means 2FA is still pending, so it must not be treated as signed in.
         */
        fun parseValidateBody(body: JsonObject): ValidateResult {
            val dsInfo = body["dsInfo"] as? JsonObject ?: return ValidateResult.NotSignedIn
            val challenge = body.boolean("hsaChallengeRequired") || dsInfo.boolean("hsaChallengeRequired")
            if (challenge) return ValidateResult.ChallengePending
            val dsid = dsInfo.string("dsid") ?: return ValidateResult.NotSignedIn
            val appleId = dsInfo.string("appleId") ?: dsInfo.string("primaryEmail") ?: ""
            val fullName = dsInfo.string("fullName")
            val ckUrl = (body["webservices"] as? JsonObject)
                ?.let { it["ckdatabasews"] as? JsonObject }
                ?.string("url")
                ?: throw UnexpectedResponseException(
                    "iCloud didn't provide a CloudKit database service for this account.",
                )
            return ValidateResult.SignedIn(IcloudAccount(dsid, appleId, fullName, ckUrl.trimEnd('/')))
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.boolean(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
    }
}
