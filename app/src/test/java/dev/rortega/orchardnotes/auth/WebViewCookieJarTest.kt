package dev.rortega.orchardnotes.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class WebViewCookieJarTest {
    @Test
    fun parsesCookieHeaderPreservingOrderAndQuotedValues() {
        val parsed = WebViewCookieJar.parseCookieHeader(
            "X-APPLE-WEBAUTH-USER=\"v=1:s=0:d=123\"; X-APPLE-WEBAUTH-TOKEN=\"v=2:t=AB==\";  dslang=US-EN; broken",
        )
        assertEquals(
            listOf(
                "X-APPLE-WEBAUTH-USER" to "\"v=1:s=0:d=123\"",
                "X-APPLE-WEBAUTH-TOKEN" to "\"v=2:t=AB==\"",
                "dslang" to "US-EN",
            ),
            parsed,
        )
    }
}
