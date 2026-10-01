package dev.rortega.orchardnotes.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.rortega.orchardnotes.OrchardApplication
import dev.rortega.orchardnotes.ui.theme.OrchardTheme

/**
 * iCloud.com's own Notes web app, signed in with this app's session: the fallback for
 * anything Orchard doesn't do natively (locked notes, editing tables, adding
 * attachments). Changes made here sync back when the screen closes.
 */
class IcloudWebActivity : ComponentActivity() {
    private var webView: WebView? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val userAgent = (application as OrchardApplication).container.clientIdentity.userAgent
        setContent {
            OrchardTheme {
                var progress by remember { mutableIntStateOf(0) }
                var canGoBack by remember { mutableStateOf(false) }
                BackHandler(enabled = canGoBack) { webView?.goBack() }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("iCloud Notes") },
                            navigationIcon = { IconButton(onClick = ::finish) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                        )
                    },
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                createWebView(context, userAgent, onProgress = { progress = it }, onHistory = { canGoBack = it })
                                    .also { webView = it }
                                    .apply { if (savedInstanceState == null) loadUrl(NOTES_URL) else restoreState(savedInstanceState) }
                            },
                            onRelease = { it.destroy() },
                        )
                        if (progress in 1..99) {
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        if (isFinishing) (application as OrchardApplication).container.notesRepository.requestSync()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, userAgent: String, onProgress: (Int) -> Unit, onHistory: (Boolean) -> Unit): WebView =
        WebView(context).apply {
            // Not AndroidView's default WRAP_CONTENT height, which makes WebView lay pages out zero pixels tall.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = userAgent
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    openOutsideIcloud(this@IcloudWebActivity, request.url)

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = onHistory(view.canGoBack())
            }
        }

    companion object {
        private const val NOTES_URL = "https://www.icloud.com/notes/"

        fun open(context: Context) = context.startActivity(Intent(context, IcloudWebActivity::class.java))
    }
}
