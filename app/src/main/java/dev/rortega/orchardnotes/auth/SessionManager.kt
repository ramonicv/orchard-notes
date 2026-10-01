package dev.rortega.orchardnotes.auth

import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.edit
import dev.rortega.orchardnotes.cloudkit.IcloudAccount
import dev.rortega.orchardnotes.cloudkit.SetupClient
import dev.rortega.orchardnotes.cloudkit.ValidateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

sealed interface SessionState {
    data object Loading : SessionState
    data object SignedOut : SessionState

    /**
     * Signed in (possibly from the cached account while offline). [expired] means
     * iCloud rejected the stored session: cached notes stay readable, but syncing
     * needs a fresh sign-in.
     */
    data class SignedIn(val account: IcloudAccount, val expired: Boolean = false) : SessionState
}

/**
 * Owns the signed-in state. The cookies themselves live in the WebView cookie
 * store (see [WebViewCookieJar]); this class keeps the account metadata and
 * decides when the session counts as signed in.
 */
class SessionManager(
    private val prefs: SharedPreferences,
    private val setupClient: SetupClient,
    private val onSignedOut: suspend () -> Unit,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val account: IcloudAccount? get() = (state.value as? SessionState.SignedIn)?.account

    /** Restores the cached account immediately (offline-first), then re-checks the session in the background. */
    suspend fun restore() {
        val cached = loadAccount()
        if (cached == null) {
            _state.value = SessionState.SignedOut
            return
        }
        _state.value = SessionState.SignedIn(cached)
        revalidate()
    }

    /** Confirms the stored session is still alive; marks it expired if iCloud says otherwise. */
    suspend fun revalidate(): Boolean {
        val result = runCatching { setupClient.validate() }.getOrElse { return false } // offline: keep cached state
        return when (result) {
            is ValidateResult.SignedIn -> {
                saveAccount(result.account)
                _state.value = SessionState.SignedIn(result.account)
                true
            }
            else -> {
                markExpired()
                false
            }
        }
    }

    fun markExpired() {
        _state.update { current -> if (current is SessionState.SignedIn) current.copy(expired = true) else current }
    }

    /**
     * Called while the sign-in page is open. Succeeds only once iCloud reports a
     * fully authenticated session (two-factor authentication included).
     */
    suspend fun completeSignIn(): ValidateResult {
        val result = setupClient.validate()
        if (result is ValidateResult.SignedIn) {
            val previous = loadAccount()
            if (previous != null && previous.dsid != result.account.dsid) {
                // A different Apple ID: drop the previous account's cached notes.
                onSignedOut()
            }
            withContext(Dispatchers.Main) { CookieManager.getInstance().flush() }
            saveAccount(result.account)
            _state.value = SessionState.SignedIn(result.account)
        }
        return result
    }

    suspend fun signOut() {
        runCatching { setupClient.logout() }
        withContext(Dispatchers.Main) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        prefs.edit { clear() }
        onSignedOut()
        _state.value = SessionState.SignedOut
    }

    private fun loadAccount(): IcloudAccount? {
        val dsid = prefs.getString(KEY_DSID, null) ?: return null
        val ckUrl = prefs.getString(KEY_CK_URL, null) ?: return null
        return IcloudAccount(
            dsid = dsid,
            appleId = prefs.getString(KEY_APPLE_ID, "") ?: "",
            fullName = prefs.getString(KEY_FULL_NAME, null),
            ckDatabaseUrl = ckUrl,
        )
    }

    private fun saveAccount(account: IcloudAccount) {
        prefs.edit {
            putString(KEY_DSID, account.dsid)
            putString(KEY_APPLE_ID, account.appleId)
            putString(KEY_FULL_NAME, account.fullName)
            putString(KEY_CK_URL, account.ckDatabaseUrl)
        }
    }

    private companion object {
        const val KEY_DSID = "dsid"
        const val KEY_APPLE_ID = "apple_id"
        const val KEY_FULL_NAME = "full_name"
        const val KEY_CK_URL = "ck_database_url"
    }
}
