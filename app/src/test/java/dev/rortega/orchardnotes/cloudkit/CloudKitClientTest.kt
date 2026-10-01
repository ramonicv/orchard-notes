package dev.rortega.orchardnotes.cloudkit

import dev.rortega.orchardnotes.auth.ClientParams
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudKitClientTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var client: CloudKitClient

    private object Params : ClientParams {
        override val clientId = "CLIENT-ID"
        override val clientBuildNumber = "2624Build27"
        override val clientMasteringNumber = "2624Build27"
    }

    @Before
    fun setUp() {
        server.start()
        val account = IcloudAccount("12345", "me@example.com", null, server.url("/").toString().trimEnd('/'))
        client = CloudKitClient(OkHttpClient(), json, Params) { account }
    }

    @After
    fun tearDown() = server.close()

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    @Test
    fun changesZoneSendsTheWebClientShapeAndParsesRecordsAndTombstones() = runTest {
        respond(
            """
            {"zones":[{"zoneID":{"zoneName":"Notes"},"syncToken":"TOKEN-2","moreComing":true,"records":[
              {"recordName":"NOTE-1","recordType":"Note","recordChangeTag":"a1",
               "fields":{"TitleEncrypted":{"type":"ENCRYPTED_BYTES","value":"R3JvY2VyaWVz"},
                         "Folder":{"type":"REFERENCE","value":{"recordName":"DefaultFolder-CloudKit","action":"VALIDATE"}}}},
              {"recordName":"NOTE-GONE","deleted":true}
            ]}]}
            """,
        )
        val page = client.changesZone("TOKEN-1")

        val request = server.takeRequest()
        assertEquals("/database/1/com.apple.notes/production/private/changes/zone", request.url.encodedPath)
        assertEquals("12345", request.url.queryParameter("dsid"))
        assertEquals("CLIENT-ID", request.url.queryParameter("clientId"))
        val zone = json.parseToJsonElement(request.body!!.utf8()).jsonObject["zones"]!!.jsonArray[0].jsonObject
        assertEquals("TOKEN-1", zone["syncToken"]!!.jsonPrimitive.content)
        assertEquals("Notes", zone["zoneID"]!!.jsonObject["zoneName"]!!.jsonPrimitive.content)

        assertEquals("TOKEN-2", page.syncToken)
        assertTrue(page.moreComing)
        assertEquals(2, page.records.size)
        val note = page.records[0]
        assertEquals("Groceries", note.encryptedString("TitleEncrypted"))
        assertEquals("DefaultFolder-CloudKit", note.reference("Folder"))
        assertEquals("a1", note.recordChangeTag)
        assertTrue(page.records[1].deleted)
    }

    @Test(expected = CloudKitServerException::class)
    fun zoneLevelErrorsInsideHttp200AreThrown() = runTest {
        respond("""{"zones":[{"zoneID":{"zoneName":"Notes"},"serverErrorCode":"BAD_REQUEST","reason":"Unknown sync continuation type"}]}""")
        client.changesZone("bogus")
    }

    @Test(expected = SessionExpiredException::class)
    fun misdirectedRequestMeansTheSessionExpired() = runTest {
        respond("{}", code = 421)
        client.changesZone(null)
    }

    @Test
    fun modifyReportsPerRecordConflictsAndSavedRecords() = runTest {
        respond(
            """
            {"records":[
              {"recordName":"NOTE-1","recordType":"Note","recordChangeTag":"b2","fields":{}},
              {"recordName":"NOTE-2","serverErrorCode":"CONFLICT","reason":"oplock error"}
            ]}
            """,
        )
        val results = client.modify(listOf(buildJsonObject { put("operationType", "update") }))
        assertEquals("b2", (results[0] as ModifyResult.Saved).record.recordChangeTag)
        assertEquals("CONFLICT", (results[1] as ModifyResult.Failed).error.serverErrorCode)
    }

    @Test
    fun lookupSkipsMissingRecords() = runTest {
        respond(
            """
            {"records":[
              {"recordName":"A","recordType":"Note","fields":{}},
              {"recordName":"B","serverErrorCode":"NOT_FOUND"}
            ]}
            """,
        )
        assertEquals(listOf("A"), client.lookup(listOf("A", "B")).map { it.recordName })
    }

    @Test
    fun sharedZonesPageThroughChangesDatabaseAndKeepRevokedZonesMarked() = runTest {
        respond(
            """
            {"syncToken":"DB-1","moreComing":true,"zones":[
              {"zoneID":{"zoneName":"Notes","ownerRecordName":"_alex"}},
              {"zoneID":{"zoneName":"Other","ownerRecordName":"_skip"}}
            ]}
            """,
        )
        respond("""{"syncToken":"DB-2","moreComing":false,"zones":[{"zoneID":{"zoneName":"Notes","ownerRecordName":"_sam"},"deleted":true}]}""")

        val zones = client.sharedZones()

        assertEquals(listOf(SharedZone("_alex", deleted = false), SharedZone("_sam", deleted = true)), zones)
        val first = server.takeRequest()
        assertEquals("/database/1/com.apple.notes/production/shared/changes/database", first.url.encodedPath)
        assertEquals("{}", first.body!!.utf8())
        val second = server.takeRequest()
        assertEquals("DB-1", json.parseToJsonElement(second.body!!.utf8()).jsonObject["syncToken"]!!.jsonPrimitive.content)
    }

    @Test(expected = UnexpectedResponseException::class)
    fun sharedZonesRefuseToReplayTheSamePage() = runTest {
        respond("""{"syncToken":"DB-1","moreComing":true,"zones":[]}""")
        respond("""{"syncToken":"DB-1","moreComing":true,"zones":[]}""")
        client.sharedZones()
    }

    @Test
    fun sharedZoneCallsUseTheSharedDatabaseAndTheOwnersZone() = runTest {
        val zone = NotesZone("_alex")
        respond("""{"zones":[{"zoneID":{"zoneName":"Notes","ownerRecordName":"_alex"},"syncToken":"Z-1","moreComing":false,"records":[]}]}""")
        respond("""{"records":[{"recordName":"NOTE-1","recordType":"Note","fields":{}}]}""")
        respond("""{"records":[{"recordName":"NOTE-1","recordType":"Note","recordChangeTag":"c3","fields":{}}]}""")

        client.changesZone(null, zone)
        client.lookup("NOTE-1", zone)
        client.modify(listOf(buildJsonObject { put("operationType", "update") }), zone)

        val changes = server.takeRequest()
        assertEquals("/database/1/com.apple.notes/production/shared/changes/zone", changes.url.encodedPath)
        val request = json.parseToJsonElement(changes.body!!.utf8()).jsonObject["zones"]!!.jsonArray[0].jsonObject
        assertEquals("_alex", request["zoneID"]!!.jsonObject["ownerRecordName"]!!.jsonPrimitive.content)
        // The shared database rejects reverse sync.
        assertFalse("reverse" in request)
        for (path in listOf("records/lookup", "records/modify")) {
            val call = server.takeRequest()
            assertEquals("/database/1/com.apple.notes/production/shared/$path", call.url.encodedPath)
            val zoneId = json.parseToJsonElement(call.body!!.utf8()).jsonObject["zoneID"]!!.jsonObject
            assertEquals("Notes", zoneId["zoneName"]!!.jsonPrimitive.content)
            assertEquals("_alex", zoneId["ownerRecordName"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun privateZoneCallsKeepTheBareZoneAndReverseSync() = runTest {
        respond("""{"zones":[{"zoneID":{"zoneName":"Notes"},"syncToken":"Z-1","moreComing":false,"records":[]}]}""")
        client.changesZone(null)
        val request = json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["zones"]!!.jsonArray[0].jsonObject
        assertEquals("true", request["reverse"]!!.jsonPrimitive.content)
        assertFalse("ownerRecordName" in request["zoneID"]!!.jsonObject)
    }

    @Test
    fun parsesShareRootsAndShareRecords() = runTest {
        respond(
            """
            {"zones":[{"zoneID":{"zoneName":"Notes","ownerRecordName":"_alex"},"syncToken":"Z","moreComing":false,"records":[
              {"recordName":"FOLDER-1","recordType":"Folder","fields":{},"share":{"recordName":"SHARE-1","zoneID":{"zoneName":"Notes"}}},
              {"recordName":"SHARE-1","recordType":"cloudkit.share","fields":{},
               "currentUserParticipant":{"permission":"READ_ONLY","type":"PRIVATE_USER"},
               "participants":[
                 {"type":"OWNER","permission":"READ_WRITE","acceptanceStatus":"ACCEPTED",
                  "userIdentity":{"nameComponents":{"givenName":"Alex","familyName":"Kim"},"lookupInfo":{"emailAddress":"alex@example.com"}}},
                 {"type":"PRIVATE_USER","permission":"READ_ONLY","acceptanceStatus":"ACCEPTED",
                  "userIdentity":{"lookupInfo":{"emailAddress":"me@example.com"}}}
               ]},
              {"recordName":"SHARE-2","recordType":"cloudkit.share","participants":[]}
            ]}]}
            """,
        )
        val records = client.changesZone(null, NotesZone("_alex")).records

        assertEquals("SHARE-1", records[0].shareRecordName)
        val share = records[1]
        assertEquals("READ_ONLY", share.currentUserPermission)
        val owner = share.participants.single { it.isOwner }
        assertEquals("Alex Kim", owner.displayName)
        assertEquals("me@example.com", share.participants.single { !it.isOwner }.displayName)
        // A share record without fields is still a record, not an error entry.
        assertEquals("SHARE-2", records[2].recordName)
        assertNull(records[2].currentUserPermission)
    }

    @Test
    fun sharedReferencesNameTheSharersZone() {
        val shared = CloudKitClient.reference("FOLDER-1", NotesZone("_alex"))
        assertEquals("_alex", shared["zoneID"]!!.jsonObject["ownerRecordName"]!!.jsonPrimitive.content)
        val own = CloudKitClient.reference("FOLDER-1")
        assertFalse("ownerRecordName" in own["zoneID"]!!.jsonObject)
    }
}
