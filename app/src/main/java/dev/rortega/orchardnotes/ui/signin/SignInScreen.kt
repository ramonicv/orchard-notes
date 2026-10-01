package dev.rortega.orchardnotes.ui.signin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.rortega.orchardnotes.BuildConfig
import dev.rortega.orchardnotes.auth.SignInScripts
import dev.rortega.orchardnotes.ui.appContainer
import dev.rortega.orchardnotes.ui.signin.SignInDiagnostics.Companion.shortUrl
import dev.rortega.orchardnotes.ui.web.openOutsideIcloud
import java.util.Locale

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
    var menuOpen by remember { mutableStateOf(false) }
    var showingDiagnostics by remember { mutableStateOf(false) }
    var pageSnapshot by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign in to iCloud") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Reload page") },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                                onClick = { menuOpen = false; webView?.reload() },
                            )
                            DropdownMenuItem(
                                text = { Text("Troubleshooting info") },
                                leadingIcon = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    pageSnapshot = null
                                    showingDiagnostics = true
                                    webView?.evaluateJavascript(SignInScripts.PAGE_SNAPSHOT) { result ->
                                        pageSnapshot = viewModel.pageSnapshotText(result)
                                    }
                                },
                            )
                        }
                    }
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

    if (showingDiagnostics) {
        val environment = rememberEnvironment(container.clientIdentity.userAgent)
        DiagnosticsDialog(
            report = viewModel.diagnostics.report(environment, pageSnapshot),
            onDismiss = { showingDiagnostics = false },
        )
    }
}

/** The troubleshooting report, selectable and shareable (the share sheet also offers Copy). */
@Composable
private fun DiagnosticsDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Troubleshooting info") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Orchard Notes sign-in troubleshooting info")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(send, null))
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The app, device and WebView details at the top of the report. */
@Composable
private fun rememberEnvironment(userAgent: String): List<String> {
    val context = LocalContext.current
    return remember(userAgent) {
        val webViewPackage = WebViewCompat.getCurrentWebViewPackage(context)
        listOf(
            "Orchard Notes ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
            "WebView: " + (webViewPackage?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"),
            "Page scripts: document start " +
                (if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) "yes" else "no") +
                ", message listener " +
                (if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) "yes" else "no"),
            "User agent: $userAgent",
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createSignInWebView(
    context: Context,
    viewModel: SignInViewModel,
    userAgent: String,
    onCanGoBackChanged: (Boolean) -> Unit,
): WebView {
    val diagnostics = viewModel.diagnostics
    // Debug builds: the page can be inspected from chrome://inspect on a connected computer.
    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    val webView = WebView(context).apply {
        // AndroidView would give the WebView WRAP_CONTENT height, and a WebView that wraps its
        // content lays the page out zero pixels tall: iCloud's full-height page drew as blank.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
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
            val url = request.url
            when {
                url.host == "setup.icloud.com" -> {
                    viewModel.onSetupRequest(url)
                    diagnostics.record("request ${request.method} ${shortUrl(url.toString())}")
                }
                // Apple's sign-in form, loaded into a frame of the iCloud page.
                url.host == APPLE_SIGN_IN_HOST && url.path.orEmpty().startsWith("/appleauth/auth/authorize") ->
                    diagnostics.record("request ${request.method} ${shortUrl(url.toString())}")
            }
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val outside = openOutsideIcloud(context, request.url)
            val frame = if (request.isForMainFrame) "page" else "frame"
            diagnostics.record("$frame navigates to ${shortUrl(request.url.toString())}${if (outside) ", sent outside the app" else ""}")
            return outside
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            diagnostics.record("page started ${shortUrl(url)}")
            viewModel.onPageStarted()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            diagnostics.record("page finished ${shortUrl(url)}")
            viewModel.onPageFinished()
            onCanGoBackChanged(view.canGoBack())
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            diagnostics.record("address is now ${shortUrl(url)}")
            onCanGoBackChanged(view.canGoBack())
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            diagnostics.record("failed to load ${shortUrl(request.url.toString())}: ${error.description} (${error.errorCode})")
            if (request.isForMainFrame) viewModel.onLoadError(error.description.toString())
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            diagnostics.record("HTTP ${errorResponse.statusCode} for ${request.method} ${shortUrl(request.url.toString())}")
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            diagnostics.record("certificate error ${error.primaryError} for ${shortUrl(error.url)}")
            super.onReceivedSslError(view, handler, error)
        }
    }
    // Without a WebChromeClient, WebView silently cancels the page's alert/confirm dialogs.
    webView.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val level = consoleMessage.messageLevel().name.lowercase(Locale.ROOT)
            val source = "${shortUrl(consoleMessage.sourceId())}:${consoleMessage.lineNumber()}"
            diagnostics.record("console $level: ${consoleMessage.message()} ($source)")
            return super.onConsoleMessage(consoleMessage)
        }
    }
    diagnostics.record("opening $ICLOUD_ORIGIN/")
    webView.loadUrl("$ICLOUD_ORIGIN/")
    return webView
}

private const val ICLOUD_ORIGIN = "https://www.icloud.com"
private const val APPLE_SIGN_IN_HOST = "idmsa.apple.com"
private const val APPLE_SIGN_IN_ORIGIN = "https://$APPLE_SIGN_IN_HOST"
