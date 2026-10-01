package dev.rortega.orchardnotes.ui.web

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.Locale

/**
 * `shouldOverrideUrlLoading` for the WebViews that host www.icloud.com. Apple's pages stay
 * in the WebView, and so do the in-page schemes (about:, blob:, data:, javascript:), which
 * have no host; refusing those would break frames the page builds for itself. Anything
 * else opens in the app that handles it.
 *
 * @return true if the navigation was taken out of the WebView.
 */
internal fun openOutsideIcloud(context: Context, url: Uri): Boolean {
    when (url.scheme?.lowercase(Locale.ROOT)) {
        "http", "https" -> if (url.host?.let(::isAppleHost) == true) return false
        "about", "blob", "data", "javascript" -> return false
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
    return true
}

private fun isAppleHost(host: String): Boolean {
    val name = host.lowercase(Locale.ROOT)
    return APPLE_DOMAINS.any { name == it || name.endsWith(".$it") }
}

private val APPLE_DOMAINS = listOf("apple.com", "icloud.com", "cdn-apple.com", "apple-cloudkit.com", "icloud-content.com")
