package dev.rortega.orchardnotes.ui.signin

import java.util.Locale

/**
 * What the sign-in page did (loads, navigations, failed requests, console output),
 * for the "Troubleshooting info" report. Apple's page can't be inspected on most
 * phones, so this is how a sign-in failure gets reported.
 *
 * Keeps the first events (where a page that never loads fails) and the latest ones.
 * Callers pass URLs through [shortUrl], so query strings with tokens stay out.
 */
class SignInDiagnostics(private val clock: () -> Long) {
    private val start = clock()
    private val head = ArrayList<String>()
    private val tail = ArrayDeque<String>()
    private var skipped = 0

    /** Thread-safe: WebView reports requests from a background thread. */
    fun record(event: String) {
        val seconds = (clock() - start) / 1000.0
        val line = String.format(Locale.ROOT, "%6.1f  %s", seconds, event.take(MAX_EVENT_LENGTH))
        synchronized(this) {
            if (head.size < HEAD_CAPACITY) {
                head += line
            } else {
                tail.addLast(line)
                if (tail.size > TAIL_CAPACITY) {
                    tail.removeFirst()
                    skipped++
                }
            }
        }
    }

    fun events(): List<String> = synchronized(this) {
        if (skipped == 0) head + tail else head + "        … $skipped events skipped …" + tail
    }

    fun report(environment: List<String>, page: String?): String = buildString {
        environment.forEach(::appendLine)
        appendLine()
        appendLine("Page right now:")
        appendLine(page ?: "(still asking the page…)")
        appendLine()
        appendLine("Events (seconds since sign-in was first opened):")
        events().forEach(::appendLine)
    }

    companion object {
        const val HEAD_CAPACITY = 300
        const val TAIL_CAPACITY = 150
        private const val MAX_EVENT_LENGTH = 600

        /** Scheme, host and path only: query strings and fragments can carry tokens. */
        fun shortUrl(url: String?): String {
            if (url.isNullOrEmpty()) return "(none)"
            val scheme = url.substringBefore(':').lowercase(Locale.ROOT)
            if (scheme == "data" || scheme == "javascript") return if (url.length > 40) url.take(40) + "…" else url
            val bare = url.substringBefore('#').substringBefore('?')
            return if (bare.length < url.length) "$bare…" else bare
        }
    }
}
