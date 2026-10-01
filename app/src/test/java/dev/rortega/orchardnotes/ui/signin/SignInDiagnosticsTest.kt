package dev.rortega.orchardnotes.ui.signin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInDiagnosticsTest {
    private var now = 10_000L
    private val diagnostics = SignInDiagnostics { now }

    @Test
    fun timesEventsFromTheStart() {
        now += 1_500
        diagnostics.record("page started https://www.icloud.com/")
        assertEquals(listOf("   1.5  page started https://www.icloud.com/"), diagnostics.events())
    }

    @Test
    fun keepsTheFirstAndLatestEvents() {
        val total = SignInDiagnostics.HEAD_CAPACITY + SignInDiagnostics.TAIL_CAPACITY + 25
        repeat(total) { diagnostics.record("event $it") }
        val events = diagnostics.events()
        assertEquals(SignInDiagnostics.HEAD_CAPACITY + SignInDiagnostics.TAIL_CAPACITY + 1, events.size)
        assertTrue(events.first().endsWith("event 0"))
        assertTrue(events[SignInDiagnostics.HEAD_CAPACITY].contains("25 events skipped"))
        assertTrue(events.last().endsWith("event ${total - 1}"))
    }

    @Test
    fun reportListsEnvironmentPageAndEvents() {
        diagnostics.record("opening https://www.icloud.com/")
        val report = diagnostics.report(listOf("Android 16"), "url: https://www.icloud.com/")
        assertEquals(
            """
            Android 16

            Page right now:
            url: https://www.icloud.com/

            Events (seconds since sign-in was first opened):
               0.0  opening https://www.icloud.com/

            """.trimIndent(),
            report,
        )
    }

    @Test
    fun shortUrlDropsQueriesAndFragments() {
        assertEquals("https://www.icloud.com/", SignInDiagnostics.shortUrl("https://www.icloud.com/"))
        assertEquals(
            "https://setup.icloud.com/setup/ws/1/validate…",
            SignInDiagnostics.shortUrl("https://setup.icloud.com/setup/ws/1/validate?clientId=ABC&dsid=123"),
        )
        assertEquals("https://www.icloud.com/notes/…", SignInDiagnostics.shortUrl("https://www.icloud.com/notes/#token"))
        assertEquals("blob:https://www.icloud.com/1234", SignInDiagnostics.shortUrl("blob:https://www.icloud.com/1234"))
        assertEquals("data:text/html,short", SignInDiagnostics.shortUrl("data:text/html,short"))
        assertEquals("data:text/html;base64," + "A".repeat(18) + "…", SignInDiagnostics.shortUrl("data:text/html;base64," + "A".repeat(100)))
        assertEquals("(none)", SignInDiagnostics.shortUrl(null))
    }
}
