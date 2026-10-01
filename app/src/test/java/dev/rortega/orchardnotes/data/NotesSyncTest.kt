package dev.rortega.orchardnotes.data

import dev.rortega.orchardnotes.auth.ClientParams
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.IcloudAccount
import dev.rortega.orchardnotes.cloudkit.NotesZone
import dev.rortega.orchardnotes.notes.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class NotesSyncTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val server = MockWebServer()
    private val dao = FakeNotesDao()
    private val prefs = FakePrefs()
    private val serverNotes = mutableListOf<List<NoteEntity>>()
    private lateinit var sync: NotesSync

    private val body = Base64.getEncoder().encodeToString(Fixtures.compressed(Fixtures.PLAIN))

    /** What the fake iCloud answers. */
    private var ownZone = zone(null)
    private var sharedDatabase = """{"zones":[],"moreComing":false}"""
    private val sharedZones = mutableMapOf<String, String>()
    private var lookup: (List<String>) -> String = { names -> """{"records":[${names.joinToString(",") { note(it, withBody = true) }}]}""" }
    private val requests = mutableListOf<Pair<String, JsonObject>>()

    private object Params : ClientParams {
        override val clientId = "C"
        override val clientBuildNumber = "B"
        override val clientMasteringNumber = "M"
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val payload = json.parseToJsonElement(request.body!!.utf8()).jsonObject
                requests += path to payload
                val body = when {
                    path.endsWith("/private/changes/zone") -> ownZone
                    path.endsWith("/shared/changes/database") -> sharedDatabase
                    path.endsWith("/shared/changes/zone") -> {
                        val owner = payload["zones"]!!.jsonArray[0].jsonObject["zoneID"]!!.jsonObject["ownerRecordName"]!!.jsonPrimitive.content
                        sharedZones.getValue(owner)
                    }
                    path.endsWith("/shared/records/lookup") ->
                        lookup(payload["records"]!!.jsonArray.map { it.jsonObject["recordName"]!!.jsonPrimitive.content })
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().code(200).body(body).build()
            }
        }
        server.start()
        val account = IcloudAccount("1", "a", null, server.url("/").toString().trimEnd('/'))
        val client = CloudKitClient(OkHttpClient(), json, Params) { account }
        sync = NotesSync(client, dao, prefs) { serverNotes += it }
    }

    @After
    fun tearDown() = server.close()

    private fun zone(owner: String?, vararg records: String, token: String = "T1") =
        """{"zones":[{"zoneID":{"zoneName":"Notes"${owner?.let { ""","ownerRecordName":"$it"""" } ?: ""}},
            "syncToken":"$token","moreComing":false,"records":[${records.joinToString(",")}]}]}"""

    private fun note(name: String, folder: String = "FOLDER-1", withBody: Boolean = false, share: String? = null) = """
        {"recordName":"$name","recordType":"Note","recordChangeTag":"t-$name",
         ${share?.let { """"share":{"recordName":"$it"},""" } ?: ""}
         "fields":{"TitleEncrypted":{"type":"ENCRYPTED_BYTES","value":"VGl0bGU="},
                   "Folder":{"type":"REFERENCE","value":{"recordName":"$folder"}}
                   ${if (withBody) ""","TextDataEncrypted":{"type":"ENCRYPTED_BYTES","value":"$body"}""" else ""}}}
    """.trimIndent()

    private fun folder(name: String, share: String? = null) =
        """{"recordName":"$name","recordType":"Folder","fields":{"TitleEncrypted":{"type":"ENCRYPTED_BYTES","value":"VHJpcA=="}}${share?.let { ""","share":{"recordName":"$it"}""" } ?: ""}}"""

    private val shareRecord = """
        {"recordName":"SHARE-1","recordType":"cloudkit.share","fields":{},
         "currentUserParticipant":{"permission":"READ_WRITE"},
         "participants":[{"type":"OWNER","userIdentity":{"nameComponents":{"givenName":"Alex","familyName":"Kim"}}},
                         {"type":"PRIVATE_USER","acceptanceStatus":"ACCEPTED","userIdentity":{"lookupInfo":{"emailAddress":"me@example.com"}}}]}
    """.trimIndent()

    private fun shareWith(vararg owners: String, deleted: Set<String> = emptySet()) {
        sharedDatabase = """{"zones":[${owners.joinToString(",") { """{"zoneID":{"zoneName":"Notes","ownerRecordName":"$it"}${if (it in deleted) ""","deleted":true""" else ""}}""" }}],"moreComing":false}"""
    }

    @Test
    fun pullsTheAccountsOwnZoneAndEveryZoneSharedWithIt() = runTest {
        ownZone = zone(null, note("OWN-1", withBody = true, share = "MY-SHARE"))
        shareWith("_alex")
        sharedZones["_alex"] = zone("_alex", folder("FOLDER-1", share = "SHARE-1"), shareRecord, note("SHARED-1"))

        sync.sync()

        assertNull(dao.notes["OWN-1"]!!.zoneOwner)
        assertEquals("MY-SHARE", dao.notes["OWN-1"]!!.shareRecordName)
        val shared = dao.notes["SHARED-1"]!!
        assertEquals("_alex", shared.zoneOwner)
        assertEquals("FOLDER-1", shared.folderRecordName)
        assertTrue("the shared listing's missing body was looked up", shared.textData != null && !shared.bodyUnavailable)
        assertEquals(FolderEntity("FOLDER-1", "Trip", null, zoneOwner = "_alex", shareRecordName = "SHARE-1"), dao.folders["FOLDER-1"])
        assertEquals(ShareEntity("SHARE-1", "_alex", "Alex Kim", "READ_WRITE", "me@example.com"), dao.shares["SHARE-1"])
        assertEquals("T1", prefs.getString("shared_zone_sync_token:_alex", null))
        // Notes that came from iCloud are offered for rebasing local edits.
        assertEquals(setOf("OWN-1", "SHARED-1"), serverNotes.flatten().map { it.recordName }.toSet())
    }

    @Test
    fun aSharersOwnSpecialFoldersNeverMixWithTheAccounts() = runTest {
        shareWith("_alex")
        sharedZones["_alex"] = zone("_alex", note("SHARED-1", folder = SpecialFolders.DEFAULT))
        lookup = { """{"records":[${note("SHARED-1", folder = SpecialFolders.DEFAULT, withBody = true)}]}""" }

        sync.sync()

        assertEquals("_alex/${SpecialFolders.DEFAULT}", dao.notes["SHARED-1"]!!.folderRecordName)
    }

    @Test
    fun notesTheSharerMovedToTheirRecentlyDeletedDisappear() = runTest {
        shareWith("_alex")
        sharedZones["_alex"] = zone("_alex", note("SHARED-1"))
        sync.sync()
        sharedZones["_alex"] = zone("_alex", note("SHARED-1", folder = SpecialFolders.TRASH), token = "T2")

        sync.sync()

        assertNull(dao.notes["SHARED-1"])
    }

    @Test
    fun revokedSharesAreForgottenWithTheirUnsentEdits() = runTest {
        shareWith("_alex", "_sam")
        sharedZones["_alex"] = zone("_alex", note("FROM-ALEX"))
        sharedZones["_sam"] = zone("_sam", note("FROM-SAM"))
        sync.sync()
        dao.upsertPending(PendingEditEntity("FROM-SAM", false, null, "[]", "[]", "", "", "", 1))

        shareWith("_alex", "_sam", deleted = setOf("_sam"))
        sync.sync()

        assertNull(dao.notes["FROM-SAM"])
        assertNull(dao.pending["FROM-SAM"])
        assertNull(prefs.getString("shared_zone_sync_token:_sam", null))
        assertTrue(dao.notes["FROM-ALEX"] != null)
    }

    @Test
    fun aZoneThatVanishedBetweenListingAndFetchIsForgotten() = runTest {
        shareWith("_alex")
        sharedZones["_alex"] = zone("_alex", note("SHARED-1"))
        sync.sync()
        sharedZones["_alex"] = """{"zones":[{"zoneID":{"zoneName":"Notes","ownerRecordName":"_alex"},"serverErrorCode":"ZONE_NOT_FOUND"}]}"""

        sync.sync()

        assertNull(dao.notes["SHARED-1"])
    }

    @Test
    fun aPageWhoseBodiesCantBeFetchedIsRetriedLater() = runTest {
        shareWith("_alex")
        sharedZones["_alex"] = zone("_alex", note("SHARED-1"))
        lookup = { """{"records":[{"recordName":"SHARED-1","serverErrorCode":"NOT_FOUND"}]}""" }

        sync.sync()

        assertNull(dao.notes["SHARED-1"])
        assertNull(prefs.getString("shared_zone_sync_token:_alex", null))
    }

    @Test
    fun syncingOneZoneTouchesOnlyThatZone() = runTest {
        shareWith("_alex")
        sharedZones["_alex"] = zone("_alex", note("SHARED-1"))

        sync.syncZone(NotesZone("_alex"))

        assertEquals(listOf("changes/zone", "records/lookup"), requests.map { it.first.substringAfter("/shared/") })
        assertTrue(dao.notes["SHARED-1"] != null)
    }

    @Test
    fun writesWeMadeAreNotOfferedForRebasing() = runTest {
        sync.applyRecords(listOf(dev.rortega.orchardnotes.cloudkit.CkRecord.parse(json.parseToJsonElement(note("OWN-1", withBody = true)))!!))
        assertTrue(dao.notes["OWN-1"] != null)
        assertTrue(serverNotes.isEmpty())
    }

    @Test
    fun cachesFromBeforeSharedNotesAreRefetchedOnce() = runTest {
        prefs.edit().putString("notes_zone_sync_token", "OLD").apply()

        sync.sync()
        val first = requests.first { it.first.endsWith("/private/changes/zone") }.second
        assertFalse("syncToken" in first["zones"]!!.jsonArray[0].jsonObject)

        requests.clear()
        sync.sync()
        val second = requests.first { it.first.endsWith("/private/changes/zone") }.second
        assertEquals("T1", second["zones"]!!.jsonArray[0].jsonObject["syncToken"]!!.jsonPrimitive.content)
    }
}
