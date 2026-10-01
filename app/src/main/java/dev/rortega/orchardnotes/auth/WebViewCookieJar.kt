package dev.rortega.orchardnotes.auth

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Bridges OkHttp to the WebView's cookie store, so the sign-in WebView and the
 * API client share one cookie jar.
 *
 * iCloud rotates `X-APPLE-WEBAUTH-TOKEN` on validation calls; with a single
 * shared store, a rotation made by either side is immediately visible to the
 * other, and the session persists across restarts the same way a browser's does
 * (in app-private storage).
 */
class WebViewCookieJar(
    private val cookieManager: () -> CookieManager = { CookieManager.getInstance() },
) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val manager = cookieManager()
        val target = url.toString()
        for (cookie in cookies) {
            manager.setCookie(target, cookie.toString())
        }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = cookieManager().getCookie(url.toString()) ?: return emptyList()
        return parseCookieHeader(header).mapNotNull { (name, value) ->
            runCatching { Cookie.Builder().name(name).value(value).domain(url.host).build() }.getOrNull()
        }
    }

    companion object {
        /** Splits a `Cookie:` header value (`a=1; b=2`) into name/value pairs, in order. */
        fun parseCookieHeader(header: String): List<Pair<String, String>> =
            header.split(';').mapNotNull { part ->
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) null else trimmed.substring(0, eq) to trimmed.substring(eq + 1)
            }
    }
}
