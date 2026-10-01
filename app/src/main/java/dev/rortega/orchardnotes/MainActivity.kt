package dev.rortega.orchardnotes

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.rortega.orchardnotes.auth.SessionState
import dev.rortega.orchardnotes.ui.OrchardRoot
import dev.rortega.orchardnotes.ui.theme.OrchardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Initialize the WebView cookie store on the main thread before any network call uses it.
        CookieManager.getInstance()
        val container = (application as OrchardApplication).container
        if (!DemoMode.handle(intent, container) && container.sessionManager.state.value == SessionState.Loading) {
            container.appScope.launch { container.sessionManager.restore() }
        }
        setContent {
            OrchardTheme {
                OrchardRoot()
            }
        }
    }
}
