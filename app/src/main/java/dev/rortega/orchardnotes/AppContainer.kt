package dev.rortega.orchardnotes

import android.content.Context
import dev.rortega.orchardnotes.auth.ClientIdentity
import dev.rortega.orchardnotes.auth.SessionManager
import dev.rortega.orchardnotes.auth.WebViewCookieJar
import dev.rortega.orchardnotes.cloudkit.IcloudHeadersInterceptor
import dev.rortega.orchardnotes.cloudkit.SetupClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph for the app; one instance per process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Long-lived scope for work that must outlive a screen (sync, saves). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val identityPrefs = appContext.getSharedPreferences("client_identity", Context.MODE_PRIVATE)
    private val sessionPrefs = appContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    val clientIdentity = ClientIdentity(appContext, identityPrefs)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .addInterceptor(IcloudHeadersInterceptor(clientIdentity.userAgent))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val setupClient = SetupClient(httpClient, json, clientIdentity)

    val sessionManager = SessionManager(
        prefs = sessionPrefs,
        setupClient = setupClient,
        onSignedOut = { },
    )
}
