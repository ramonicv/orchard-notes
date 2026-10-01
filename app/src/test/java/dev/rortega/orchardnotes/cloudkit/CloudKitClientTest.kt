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
}
