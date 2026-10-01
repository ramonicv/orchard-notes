package dev.rortega.orchardnotes

import android.content.Context
import dev.rortega.orchardnotes.auth.ClientIdentity
import dev.rortega.orchardnotes.auth.SessionManager
import dev.rortega.orchardnotes.auth.WebViewCookieJar
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.IcloudHeadersInterceptor
import dev.rortega.orchardnotes.cloudkit.SetupClient
import androidx.core.content.edit
import dev.rortega.orchardnotes.data.AttachmentImages
import dev.rortega.orchardnotes.data.NoteWriter
import dev.rortega.orchardnotes.data.NotesDatabase
import dev.rortega.orchardnotes.data.PushWorker
import dev.rortega.orchardnotes.notes.doc.FormatReconcile
import java.util.Base64
import dev.rortega.orchardnotes.data.NotesRepository
import dev.rortega.orchardnotes.data.NotesSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency graph for the app; one instance per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Long-lived scope for work that must outlive a screen (sync, saves). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val identityPrefs = appContext.getSharedPreferences("client_identity", Context.MODE_PRIVATE)
    private val sessionPrefs = appContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    val clientIdentity = ClientIdentity(identityPrefs)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .addInterceptor(IcloudHeadersInterceptor(clientIdentity.userAgent))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val setupClient = SetupClient(httpClient, json, clientIdentity)

    val sessionManager: SessionManager = SessionManager(
        prefs = sessionPrefs,
        setupClient = setupClient,
        onSignedOut = {
            notesRepository.clear()
            attachmentImages.clear()
        },
    )

    val cloudKit: CloudKitClient = CloudKitClient(httpClient, json, clientIdentity) { sessionManager.account }

    val attachmentImages: AttachmentImages by lazy { AttachmentImages(cloudKit, java.io.File(appContext.cacheDir, "attachments")) }

    private val database = NotesDatabase.create(appContext)
    private val syncPrefs = appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)

    val notesSync: NotesSync = NotesSync(cloudKit, database.notesDao(), syncPrefs)

    /**
     * This installation's identity in note CRDTs. Persisted forever: Apple keeps one clock
     * entry per replica in every note we touch. A random (v4) UUID, as Apple's clients use.
     */
    private val replicaId: ByteArray by lazy {
        syncPrefs.getString(KEY_REPLICA_ID, null)?.let { Base64.getDecoder().decode(it) }
            ?: FormatReconcile.randomUuidBytes().also { id ->
                syncPrefs.edit { putString(KEY_REPLICA_ID, Base64.getEncoder().encodeToString(id)) }
            }
    }

    private val noteWriter = NoteWriter(cloudKit, database.notesDao(), notesSync::applyRecords, json) { replicaId }

    val notesRepository: NotesRepository = NotesRepository(
        dao = database.notesDao(),
        sync = notesSync,
        writer = noteWriter,
        sessionManager = sessionManager,
        scope = appScope,
        schedulePushWhenOnline = { PushWorker.enqueue(appContext) },
    )

    private companion object {
        const val KEY_REPLICA_ID = "replica_id"
    }
}
