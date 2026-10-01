package dev.rortega.orchardnotes.auth

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale
import java.util.UUID

/** The identifying query parameters the www.icloud.com web client sends with every call. */
interface ClientParams {
    val clientId: String
    val clientBuildNumber: String
    val clientMasteringNumber: String
}

/**
 * How this app identifies itself to iCloud's web services: the same query
 * parameters the www.icloud.com web client sends, plus a desktop Safari
 * User-Agent shared by the sign-in WebView and every API call (Apple ties
 * sessions loosely to the client that created them).
 *
 * The build/mastering numbers are refreshed from whatever the sign-in page itself
 * sends, so they follow Apple's web client releases; the defaults are only used
 * until the first sign-in.
 */
class ClientIdentity(private val prefs: SharedPreferences) : ClientParams {

    val userAgent: String = USER_AGENT

    override val clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().uppercase(Locale.ROOT).also {
            prefs.edit { putString(KEY_CLIENT_ID, it) }
        }

    override val clientBuildNumber: String
        get() = prefs.getString(KEY_BUILD, null) ?: DEFAULT_CLIENT_BUILD_NUMBER

    override val clientMasteringNumber: String
        get() = prefs.getString(KEY_MASTERING, null) ?: DEFAULT_CLIENT_MASTERING_NUMBER

    /** Records the web client's identifiers observed on a setup.icloud.com request URL. */
    fun captureFrom(url: android.net.Uri) {
        val build = url.getQueryParameter("clientBuildNumber")
        val mastering = url.getQueryParameter("clientMasteringNumber")
        val id = url.getQueryParameter("clientId")
        if (build == null && mastering == null && id == null) return
        prefs.edit {
            build?.let { putString(KEY_BUILD, it) }
            mastering?.let { putString(KEY_MASTERING, it) }
            id?.let { putString(KEY_CLIENT_ID, it) }
        }
    }

    companion object {
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_BUILD = "client_build_number"
        private const val KEY_MASTERING = "client_mastering_number"

        // Observed from the www.icloud.com web client (2026); replaced by live values on sign-in.
        const val DEFAULT_CLIENT_BUILD_NUMBER = "2624Build27"
        const val DEFAULT_CLIENT_MASTERING_NUMBER = "2624Build27"

        /**
         * Desktop Safari, the browser www.icloud.com is built for, and the identity other apps
         * use to host iCloud's sign-in in an Android WebView. The page is still laid out at the
         * WebView's width, so it fits the phone.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.6 Safari/605.1.15"
    }
}
