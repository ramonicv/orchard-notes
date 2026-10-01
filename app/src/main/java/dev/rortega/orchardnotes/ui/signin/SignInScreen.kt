package dev.rortega.orchardnotes.ui.signin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.rortega.orchardnotes.auth.SignInScripts
import dev.rortega.orchardnotes.ui.appContainer

/** Apple's own iCloud sign-in page, hosted in a WebView. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(onClose: () -> Unit) {
    val container = appContainer()
    val viewModel: SignInViewModel = viewModel {
        SignInViewModel(container.sessionManager, container.clientIdentity, container.json)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to iCloud") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    createSignInWebView(context, viewModel, container.clientIdentity.userAgent) {
                        canGoBack = it
                    }.also { webView = it }
                },
                onRelease = { it.destroy() },
            )
            if (state.pageLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.error?.let { message ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { webView?.reload() }) { Text("Retry") }
                    }
                }
            }
            if (state.finishing) {
                Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Finishing sign-in…",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createSignInWebView(
    context: Context,
    viewModel: SignInViewModel,
    userAgent: String,
    onCanGoBackChanged: (Boolean) -> Unit,
): WebView {
    val webView = WebView(context)
    with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        userAgentString = userAgent
        allowFileAccess = false
        allowContentAccess = false
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        // Apple's sign-in form lives in an idmsa.apple.com iframe and needs its own cookies.
        setAcceptThirdPartyCookies(webView, true)
    }

    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        WebViewCompat.addWebMessageListener(
            webView,
            SignInScripts.BRIDGE_NAME,
            setOf(ICLOUD_ORIGIN),
        ) { _, message, _, _, _ ->
            message.data?.let(viewModel::onBridgeMessage)
        }
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(webView, SignInScripts.SESSION_HOOK, setOf(ICLOUD_ORIGIN))
        WebViewCompat.addDocumentStartJavaScript(webView, SignInScripts.KEEP_ME_SIGNED_IN, setOf(APPLE_SIGN_IN_ORIGIN))
    }

    webView.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.url.host == "setup.icloud.com") viewModel.onSetupRequest(request.url)
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val host = request.url.host ?: return true
            if (isAppleHost(host)) return false
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            viewModel.onPageStarted()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            viewModel.onPageFinished()
            onCanGoBackChanged(view.canGoBack())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            onCanGoBackChanged(view.canGoBack())
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) viewModel.onLoadError(error.description.toString())
        }
    }
    webView.loadUrl("$ICLOUD_ORIGIN/")
    return webView
}

private const val ICLOUD_ORIGIN = "https://www.icloud.com"
private const val APPLE_SIGN_IN_ORIGIN = "https://idmsa.apple.com"

private fun isAppleHost(host: String): Boolean =
    listOf("apple.com", "icloud.com", "cdn-apple.com", "apple-cloudkit.com", "icloud-content.com")
        .any { host == it || host.endsWith(".$it") }
