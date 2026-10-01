package dev.rortega.orchardnotes.cloudkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupClientTest {
    private fun parse(text: String) = SetupClient.parseValidateBody(Json.parseToJsonElement(text).jsonObject)

    @Test
    fun fullySignedInBodyYieldsAccount() {
        val result = parse(
            """
            {"dsInfo":{"dsid":"123","appleId":"me@example.com","fullName":"Sam Example","hsaChallengeRequired":false},
             "webservices":{"ckdatabasews":{"url":"https://p43-ckdatabasews.icloud.com:443/","status":"active"}},
             "hsaChallengeRequired":false}
            """,
        )
        assertEquals(
            ValidateResult.SignedIn(IcloudAccount("123", "me@example.com", "Sam Example", "https://p43-ckdatabasews.icloud.com:443")),
            result,
        )
    }

    @Test
    fun pendingTwoFactorIsNotSignedIn() {
        // The pre-2FA accountLogin response already lists web services; only the flag tells them apart.
        val result = parse(
            """
            {"dsInfo":{"dsid":"123","appleId":"me@example.com","hsaChallengeRequired":true},
             "webservices":{"ckdatabasews":{"url":"https://p43-ckdatabasews.icloud.com:443"}},
             "hsaChallengeRequired":true}
            """,
        )
        assertEquals(ValidateResult.ChallengePending, result)
    }

    @Test
    fun missingDsInfoIsNotSignedIn() {
        assertEquals(ValidateResult.NotSignedIn, parse("""{"error":"Missing X-APPLE-WEBAUTH-TOKEN cookie"}"""))
    }

    @Test(expected = UnexpectedResponseException::class)
    fun signedInWithoutCloudKitServiceIsRejected() {
        parse("""{"dsInfo":{"dsid":"1","appleId":"a"},"webservices":{}}""")
    }

    @Test
    fun appleIdFallsBackToPrimaryEmail() {
        val result = parse(
            """{"dsInfo":{"dsid":"9","primaryEmail":"x@icloud.com"},"webservices":{"ckdatabasews":{"url":"https://p1-ckdatabasews.icloud.com"}}}""",
        )
        assertTrue(result is ValidateResult.SignedIn && result.account.appleId == "x@icloud.com")
    }
}
