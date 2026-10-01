package dev.rortega.orchardnotes.ui.signin

import android.net.Uri
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rortega.orchardnotes.auth.ClientIdentity
import dev.rortega.orchardnotes.auth.SessionManager
import dev.rortega.orchardnotes.cloudkit.SetupClient
import dev.rortega.orchardnotes.cloudkit.ValidateResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SignInUiState(
    val pageLoading: Boolean = true,
    /** Apple reported a completed sign-in; we're confirming it and loading the account. */
    val finishing: Boolean = false,
    val error: String? = null,
)

/**
 * Watches the sign-in WebView for the moment Apple's page finishes signing in.
 *
 * Primary signal: the injected hook relays the web client's own `accountLogin` /
 * `validate` responses. Fallback: once iCloud's auth cookie exists, a slow poll of
 * `/validate` (for WebView builds without document-start scripts).
 */
class SignInViewModel(
    private val sessionManager: SessionManager,
    private val identity: ClientIdentity,
    private val json: Json,
) : ViewModel() {

    private val _state = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = _state.asStateFlow()

    private val checkMutex = Mutex()
    private var pendingCheck: Job? = null
    private var completed = false

    init {
        viewModelScope.launch {
            while (!completed) {
                delay(POLL_INTERVAL_MS)
                if (hasAuthCookie()) check()
            }
        }
    }

    fun onPageStarted() = _state.update { it.copy(pageLoading = true, error = null) }

    fun onPageFinished() = _state.update { it.copy(pageLoading = false) }

    fun onLoadError(description: String) = _state.update {
        it.copy(pageLoading = false, error = "Couldn't reach iCloud ($description). Check your connection and try again.")
    }

    /** Called from the WebView's network thread for every setup.icloud.com request. */
    fun onSetupRequest(url: Uri) {
        identity.captureFrom(url)
    }

    /** A setup response relayed by the injected hook. */
    fun onBridgeMessage(message: String) {
        val body = runCatching {
            val envelope = json.parseToJsonElement(message).jsonObject
            val text = envelope["body"]?.jsonPrimitive?.contentOrNull ?: return
            json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: return
        val result = runCatching { SetupClient.parseValidateBody(body) }.getOrNull()
        if (result is ValidateResult.SignedIn) {
            _state.update { it.copy(finishing = true) }
            scheduleCheck(delayMs = 300)
        }
    }

    private fun scheduleCheck(delayMs: Long) {
        if (pendingCheck?.isActive == true) return
        pendingCheck = viewModelScope.launch {
            delay(delayMs)
            check()
        }
    }

    private suspend fun check() = checkMutex.withLock {
        if (completed) return@withLock
        val result = runCatching { sessionManager.completeSignIn() }
        result.onSuccess {
            when (it) {
                is ValidateResult.SignedIn -> completed = true
                // The page may still be finishing; the hook or poll will retry.
                else -> _state.update { state -> state.copy(finishing = false) }
            }
        }.onFailure { error ->
            _state.update { it.copy(finishing = false, error = error.message ?: "Sign-in check failed.") }
        }
    }

    private fun hasAuthCookie(): Boolean =
        CookieManager.getInstance().getCookie("https://www.icloud.com/")?.contains("X-APPLE-WEBAUTH-TOKEN") == true

    private companion object {
        const val POLL_INTERVAL_MS = 6_000L
    }
}
